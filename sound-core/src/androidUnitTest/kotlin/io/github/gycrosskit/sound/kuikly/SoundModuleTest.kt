package io.github.gycrosskit.sound.kuikly

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import io.github.gycrosskit.sound.SoundPhase
import kotlin.test.*

class SoundModuleTest {
    private fun status(value: String) = JSONObject().apply { put("status", value) }

    @Test fun replacementAndReleaseDiscardOldPreparationAndRevokeCallbacks() {
        val module = SoundModule("host.wav")
        module.prepare("https://example.test/old.wav")
        val old = module.calls.single()
        module.prepare("https://example.test/new.wav")
        val current = module.calls.last()
        assertEquals(1, module.liveCallbacks.size)
        old.deliver(status("ready"))
        assertEquals(SoundPhase.PREPARING, module.state.value.phase)
        current.deliver(status("ready"))
        assertEquals(SoundPhase.REMOTE_READY, module.state.value.phase)
        module.release()
        module.release()
        module.play()
        module.prepare("https://example.test/after.wav")
        current.deliver(status("failed"))
        assertEquals(SoundPhase.RELEASED, module.state.value.phase)
        assertEquals(1, module.calls.count { it.method == "release" })
        assertEquals(3, module.calls.size)
        assertTrue(module.liveCallbacks.isEmpty())
    }

    @Test fun failedPreparationRetriesWhileDuplicateReadyPreparationIsSkipped() {
        val module = SoundModule("host.wav")
        val url = "https://example.test/a.wav"
        module.prepare(url)
        module.prepare(url)
        assertEquals(1, module.calls.size)
        module.calls.last().deliver(status("failed"))
        assertEquals(SoundPhase.REMOTE_FAILED, module.state.value.phase)
        module.prepare(url)
        module.calls.last().deliver(status("ready"))
        module.prepare(url)
        assertEquals(2, module.calls.size)
        module.prepare(null)
        assertEquals(SoundPhase.LOCAL_ONLY, module.state.value.phase)
        assertTrue(module.liveCallbacks.isEmpty())
        module.release()
    }
}
