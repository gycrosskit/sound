package io.github.gycrosskit.sound.kuikly

import io.github.gycrosskit.sound.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SoundHandlerTest {
    private class Player : SoundPlayer {
        override val state = MutableStateFlow(SoundState())
        var released = 0
        var played = 0
        override fun prepare(remoteUrl: String?) { state.value = SoundState(SoundPhase.PREPARING, remoteUrl.orEmpty()) }
        override fun play() { played++ }
        override fun release() { released++; state.value = SoundState(SoundPhase.RELEASED) }
    }

    @Test fun validatesInputAndRetainsReadyThenFailedCallbackUntilRelease() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val player = Player()
        val handler = SoundHandler { assertEquals("host.wav", it); player }
        val replies = mutableListOf<String>()
        try {
            handler.call("prepare", "{\"rawfile\":3}", replies::add)
            assertEquals(listOf("{\"status\":\"failed\"}"), replies)
            replies.clear()
            handler.call("prepare", "{\"rawfile\":\"host.wav\",\"url\":\"https://example.test/a\"}", replies::add)
            player.state.value = SoundState(SoundPhase.REMOTE_READY, "https://example.test/a")
            player.state.value = SoundState(SoundPhase.REMOTE_FAILED, "https://example.test/a")
            assertEquals(listOf("{\"status\":\"ready\"}", "{\"status\":\"failed\"}"), replies)
            handler.call("play", "{}", replies::add)
            assertEquals(1, player.played)
            handler.call("release", "", replies::add)
            handler.dispose()
            player.state.value = SoundState(SoundPhase.REMOTE_READY, "late")
            handler.call("play", "{}", replies::add)
            assertEquals(1, player.played)
            assertEquals(1, player.released)
            assertEquals(2, replies.size)
        } finally { handler.dispose(); Dispatchers.resetMain() }
    }

    @Test fun replacementCallbackAndRendererOwnersAreIndependent() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val firstPlayer = Player()
        val secondPlayer = Player()
        val first = SoundHandler { firstPlayer }
        val second = SoundHandler { secondPlayer }
        val old = mutableListOf<String>()
        val replacement = mutableListOf<String>()
        val other = mutableListOf<String>()
        try {
            first.call("prepare", "{\"rawfile\":\"host.wav\",\"url\":\"https://example.test/a\"}", old::add)
            first.call("prepare", "{\"url\":\"https://example.test/b\"}", replacement::add)
            second.call("prepare", "{\"rawfile\":\"host.wav\",\"url\":\"https://example.test/c\"}", other::add)
            firstPlayer.state.value = SoundState(SoundPhase.REMOTE_READY, "https://example.test/b")
            assertTrue(old.isEmpty())
            assertEquals(1, replacement.size)
            first.dispose()
            secondPlayer.state.value = SoundState(SoundPhase.REMOTE_READY, "https://example.test/c")
            assertEquals(1, other.size)
            assertEquals(0, secondPlayer.released)
        } finally { first.dispose(); second.dispose(); Dispatchers.resetMain() }
    }
}
