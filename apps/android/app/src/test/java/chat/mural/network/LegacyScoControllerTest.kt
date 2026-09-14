package chat.mural.network

import android.media.AudioDeviceInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LegacyScoControllerTest {
    private class Audio : LegacyScoAudio {
        var state = LegacyAudioDevices(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER), emptySet(), false, true)
        var speaker = true
        var starts = 0
        var stops = 0
        var failStart = false
        var failDeviceRead = false
        val speakerWrites = mutableListOf<Boolean>()
        override fun devices(): LegacyAudioDevices {
            if (failDeviceRead) error("Audio service unavailable")
            return state
        }
        override fun speakerphone() = speaker
        override fun setSpeakerphone(enabled: Boolean) { speaker = enabled; speakerWrites += enabled }
        override fun startSco() { starts++; if (failStart) error("Bluetooth service failed") }
        override fun stopSco() { stops++; state = state.copy(scoOn = false) }
        fun headset(id: Int = 12) {
            state = state.copy(outputTypes = listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_SCO),
                scoDeviceIDs = setOf(id))
        }
    }
    private fun TestScope.controller(audio: Audio, previousSpeaker: Boolean = audio.speaker) =
        LegacyScoController(audio, backgroundScope, previousSpeaker, timeoutMillis = 8_000)

    @Test fun successfulConnectionWaitsForBroadcastAndStopsItsOwnRequestOnce() = runTest {
        val audio = Audio().apply { headset() }
        val controller = controller(audio)
        controller.start(LegacyScoConnection.Disconnected); runCurrent()
        assertEquals(1, audio.starts); assertFalse(audio.speaker)
        controller.stateChanged(LegacyScoConnection.Connecting, LegacyScoConnection.Disconnected)
        audio.state = audio.state.copy(scoOn = true)
        controller.stateChanged(LegacyScoConnection.Connected, LegacyScoConnection.Connecting)
        advanceTimeBy(20_000); runCurrent()
        assertEquals(0, audio.stops)
        controller.close(restoreSpeakerphone = true)
        controller.close(restoreSpeakerphone = true)
        assertEquals(1, audio.stops); assertTrue(audio.speaker)
    }

    @Test fun timeoutFallsBackWithoutRetryingUntilTheHeadsetIsReconnected() = runTest {
        val audio = Audio().apply { headset() }
        val controller = controller(audio)
        controller.start(LegacyScoConnection.Disconnected); runCurrent()
        advanceTimeBy(8_001); runCurrent()
        assertEquals(1, audio.starts); assertEquals(1, audio.stops); assertTrue(audio.speaker)
        repeat(3) { controller.refresh(); advanceTimeBy(10_000); runCurrent() }
        assertEquals(1, audio.starts)
        audio.state = audio.state.copy(scoDeviceIDs = emptySet())
        controller.refresh()
        audio.headset(); controller.refresh(); runCurrent()
        assertEquals(2, audio.starts)
        controller.close(restoreSpeakerphone = true)
        assertEquals(2, audio.stops)
    }

    @Test fun stickyDisconnectedIsNotMistakenForTheNewRequestFailing() = runTest {
        val audio = Audio().apply { headset() }
        val controller = controller(audio)
        controller.start(LegacyScoConnection.Disconnected)
        controller.stateChanged(LegacyScoConnection.Disconnected, LegacyScoConnection.Disconnected)
        runCurrent(); advanceTimeBy(7_999); runCurrent()
        assertEquals(1, audio.starts); assertEquals(0, audio.stops)
        controller.stateChanged(LegacyScoConnection.Connecting, LegacyScoConnection.Disconnected)
        controller.stateChanged(LegacyScoConnection.Disconnected, LegacyScoConnection.Connecting)
        assertEquals(1, audio.stops); assertTrue(audio.speaker)
        controller.refresh(); assertEquals(1, audio.starts)
        controller.close(restoreSpeakerphone = true)
    }

    @Test fun preExistingScoAndPendingExternalConnectionsAreNeverStoppedOrClaimed() = runTest {
        for (state in listOf(LegacyScoConnection.Connecting, LegacyScoConnection.Connected)) {
            val audio = Audio().apply { headset(); this.state = this.state.copy(scoOn = state == LegacyScoConnection.Connected) }
            val controller = controller(audio)
            controller.start(state); runCurrent(); advanceTimeBy(20_000); runCurrent()
            controller.close(restoreSpeakerphone = true)
            assertEquals(0, audio.starts); assertEquals(0, audio.stops); assertFalse(audio.speaker)
        }
    }

    @Test fun lostHeadsetAndNewWiredRouteReleaseScoAndPreserveTheExternalRoute() = runTest {
        for (replacement in listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID)) {
            val audio = Audio().apply { headset() }
            val controller = controller(audio)
            controller.start(LegacyScoConnection.Disconnected); runCurrent()
            audio.state = audio.state.copy(outputTypes = listOf(replacement), scoDeviceIDs = emptySet())
            controller.refresh()
            assertEquals(1, audio.stops); assertFalse(audio.speaker)
            controller.close(restoreSpeakerphone = true)
            assertFalse(audio.speaker)
        }
    }

    @Test fun losingAConnectedHeadsetUsesTheFreshRouteAfterReleasingSco() = runTest {
        val audio = Audio().apply { headset() }
        val controller = controller(audio)
        controller.start(LegacyScoConnection.Disconnected); runCurrent()
        audio.state = audio.state.copy(scoOn = true)
        controller.stateChanged(LegacyScoConnection.Connected, LegacyScoConnection.Connecting)
        audio.state = audio.state.copy(scoDeviceIDs = emptySet(), outputTypes = listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        controller.refresh()
        assertEquals(1, audio.stops); assertTrue(audio.speaker)
        controller.close(restoreSpeakerphone = true)
    }

    @Test fun startFailureReleasesPossiblePartialRequestAndUsesTheSpeaker() = runTest {
        val audio = Audio().apply { headset(); failStart = true }
        val controller = controller(audio)
        controller.start(LegacyScoConnection.Disconnected); runCurrent()
        assertEquals(1, audio.starts); assertEquals(1, audio.stops); assertTrue(audio.speaker)
        controller.refresh(); assertEquals(1, audio.starts)
        controller.close(restoreSpeakerphone = true)
    }

    @Test fun closeCancelsTimeoutAndRejectsLateConnectionBroadcasts() = runTest {
        val audio = Audio().apply { headset() }
        val controller = controller(audio)
        controller.start(LegacyScoConnection.Disconnected); runCurrent()
        controller.close(restoreSpeakerphone = true)
        val writes = audio.speakerWrites.toList()
        controller.stateChanged(LegacyScoConnection.Connected, LegacyScoConnection.Connecting)
        controller.refresh(); advanceTimeBy(20_000); runCurrent()
        assertEquals(1, audio.starts); assertEquals(1, audio.stops); assertEquals(writes, audio.speakerWrites)
    }

    @Test fun focusLossAndAnotherCallerChangingTheSpeakerPreventRouteRestoration() = runTest {
        val audio = Audio().apply { headset() }
        val controller = controller(audio)
        controller.start(LegacyScoConnection.Disconnected); runCurrent()
        controller.close(restoreSpeakerphone = false)
        assertEquals(1, audio.stops); assertFalse(audio.speaker)
        val other = Audio().apply { headset() }
        val second = controller(other, previousSpeaker = false)
        second.start(LegacyScoConnection.Disconnected); runCurrent()
        other.speaker = true // A different caller changed the route after our last write.
        second.close(restoreSpeakerphone = true)
        assertTrue(other.speaker)
    }

    @Test fun audioServiceFailureDuringTimeoutReportsFailureWithoutCrashingTheWorker() = runTest {
        val audio = Audio().apply { headset() }
        var failures = 0
        val controller = LegacyScoController(audio, backgroundScope, true, 8_000, onFailure = { failures++ })
        controller.start(LegacyScoConnection.Disconnected); runCurrent()
        audio.failDeviceRead = true
        advanceTimeBy(8_001); runCurrent()
        assertEquals(1, failures); assertEquals(1, audio.stops)
        controller.close(restoreSpeakerphone = false)
    }

    @Test fun devicesWithoutOffCallScoAndWiredHeadsetsNeverStartBluetooth() = runTest {
        val audio = Audio().apply { headset(); state = state.copy(canUseScoOffCall = false) }
        val controller = controller(audio)
        controller.start(LegacyScoConnection.Disconnected); runCurrent()
        assertEquals(0, audio.starts); assertTrue(audio.speaker)
        audio.state = audio.state.copy(canUseScoOffCall = true,
            outputTypes = audio.state.outputTypes + AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        controller.refresh(); assertEquals(0, audio.starts); assertFalse(audio.speaker)
        controller.close(restoreSpeakerphone = true)
    }
}
