package chat.mural.network

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.*

@RunWith(AndroidJUnit4::class)
class NativeCompatibilityTest {
    @Test fun keystoreEncryptsAndDeletesOnlyIsolatedTestCredentials() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = UUID.randomUUID().toString()
        val store = CredentialStore(context, "mural_test_$namespace", "chat.mural.test.$namespace")
        val fake = "sk-offline-test-credential-never-sent"
        try {
            store.save(fake)
            val raw = context.getSharedPreferences("mural_test_$namespace", Context.MODE_PRIVATE).all.values.joinToString()
            assertFalse(raw.contains(fake))
            assertTrue(store.hasKey)
            assertEquals(fake, CredentialStore(context, "mural_test_$namespace", "chat.mural.test.$namespace").read())
            assertThrows(CredentialStore.CredentialException.Invalid::class.java) { store.save("bad") }
            assertEquals(fake, store.read())
            store.delete(); assertFalse(store.hasKey)
        } finally { store.delete() }
    }

    @Test fun nativeWebRtcBuildsAnAudioAndDataOfferWithoutMicrophoneOrInternet() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        val peer = factory.createPeerConnection(PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(candidate: IceCandidate) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
        })!!
        var channel: DataChannel? = null
        try {
            peer.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
            channel = peer.createDataChannel("oai-events", DataChannel.Init().apply { ordered = true })
            val ready = CountDownLatch(1)
            var result: SessionDescription? = null
            var failure: String? = null
            peer.createOffer(object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) { result = description; ready.countDown() }
                override fun onCreateFailure(message: String) { failure = message; ready.countDown() }
                override fun onSetSuccess() {}
                override fun onSetFailure(message: String) {}
            }, MediaConstraints())
            assertTrue("SDP timed out", ready.await(10, TimeUnit.SECONDS))
            assertNull(failure)
            assertTrue(result!!.description.contains("m=audio"))
            assertTrue(result!!.description.contains("m=application"))
        } finally {
            channel?.close(); channel?.dispose(); peer.close(); peer.dispose(); factory.dispose()
        }
    }
}
