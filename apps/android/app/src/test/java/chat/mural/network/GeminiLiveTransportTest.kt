package chat.mural.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class GeminiLiveTransportTest {
    @Test fun webSocketRequestUsesHttpsAndPreservesTheApiKey() {
        val key = "fake-test-key +/?&=#"
        val request = GeminiLiveTransport.webSocketRequest(key)

        assertEquals("GET", request.method)
        assertEquals("https", request.url.scheme)
        assertEquals("generativelanguage.googleapis.com", request.url.host)
        assertEquals("/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent", request.url.encodedPath)
        assertEquals(setOf("key"), request.url.queryParameterNames)
        assertEquals(key, request.url.queryParameter("key"))
    }

    @Test fun requestSupportsOkHttpWebSocketUpgrade() {
        val server = MockWebServer()
        val client = OkHttpClient()
        var socket: WebSocket? = null
        try {
            server.start()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("ready")
                }
            }))
            val request = GeminiLiveTransport.webSocketRequest("fake-test-key")
            // Keep the production path and query, but connect only to the local test server.
            val localRequest = request.newBuilder().url(request.url.newBuilder()
                .scheme("http").host(server.hostName).port(server.port).build()).build()
            val completed = CountDownLatch(1)
            val received = AtomicReference<String?>(null)
            val failure = AtomicReference<Throwable?>(null)
            socket = client.newWebSocket(localRequest, object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    received.set(text)
                    completed.countDown()
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    failure.set(t)
                    completed.countDown()
                }
            })

            assertTrue("WebSocket exchange timed out", completed.await(5, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertEquals("ready", received.get())
            val handshake = server.takeRequest(2, TimeUnit.SECONDS) ?: throw AssertionError("No handshake request")
            assertEquals("websocket", handshake.getHeader("Upgrade")?.lowercase())
            assertEquals(request.url.encodedPath, handshake.requestUrl?.encodedPath)
            assertEquals("fake-test-key", handshake.requestUrl?.queryParameter("key"))
        } finally {
            socket?.cancel()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            server.shutdown()
        }
    }
}
