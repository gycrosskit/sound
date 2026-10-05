package io.github.gycrosskit.sound

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import platform.Foundation.NSThread

class IosSoundPlayerTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun backgroundCommandsWaitForMainAndRemainClosedAfterScopeCancellation() = runTest {
        assertTrue(NSThread.isMainThread)
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val player = IosSoundPlayer("host_resource", "wav")
        try {
            // 只在测试阻塞 Main 等后台提交；避免 runTest 提前消费 Main 队列。
            runBlocking(Dispatchers.Default) { player.prepare("http://example.test/rejected.wav") }
            assertEquals(SoundState(), player.state.value)
            runCurrent()
            assertEquals(SoundPhase.REMOTE_FAILED, player.state.value.phase)
            runBlocking(Dispatchers.Default) { player.release() }
            assertEquals(SoundPhase.REMOTE_FAILED, player.state.value.phase)
            runCurrent()
            assertEquals(SoundState(SoundPhase.RELEASED), player.state.value)
            runBlocking(Dispatchers.Default) {
                player.prepare(URL)
                player.play()
                player.release()
            }
            runCurrent()
            assertEquals(SoundState(SoundPhase.RELEASED), player.state.value)
        } finally {
            player.release()
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun queuedPreparationAndReleaseCannotReviveThePlayer() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val player = IosSoundPlayer("host_resource", "wav")
        try {
            runBlocking(Dispatchers.Default) {
                player.prepare(URL)
                player.play()
                player.release()
                player.prepare("https://example.test/after-release.wav")
                player.play()
                player.release()
            }
            assertEquals(SoundState(), player.state.value)
            runCurrent()
            assertEquals(SoundState(SoundPhase.RELEASED), player.state.value)
            runCurrent()
            assertEquals(SoundState(SoundPhase.RELEASED), player.state.value)
        } finally {
            player.release()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun releaseIsTerminalAndIdempotent() {
        val player = IosSoundPlayer("host_resource", "wav")
        player.release()
        player.prepare("https://example.test/a.wav")
        player.play()
        player.release()
        assertEquals(SoundState(SoundPhase.RELEASED), player.state.value)
    }

    @Test
    fun acceptsAudioWithoutContentLengthAndAtTheLimit() = runTest {
        val bytes = ByteArray(MAXIMUM_BYTES) { (it % 256).toByte() }
        HttpClient(MockEngine { respond(bytes) }).use { client ->
            assertContentEquals(bytes, client.loadSound(URL))
        }
    }

    @Test
    fun rejectsEmptyAndNonSuccessfulResponses() = runTest {
        HttpClient(MockEngine { respond(ByteArray(0)) }).use { client ->
            assertNull(client.loadSound(URL))
        }
        HttpClient(MockEngine { respond(byteArrayOf(1), HttpStatusCode.NotFound) }).use { client ->
            assertNull(client.loadSound(URL))
        }
    }

    @Test
    fun rejectsDeclaredOversizeBeforeWaitingForBody() = runTest {
        val body = ByteChannel(autoFlush = true)
        HttpClient(MockEngine {
            respond(body, headers = headersOf(HttpHeaders.ContentLength, (MAXIMUM_BYTES + 1).toString()))
        }).use { client ->
            assertNull(client.loadSound(URL))
        }
    }

    @Test
    fun rejectsOversizeWithoutWaitingForTheUnboundedStreamToEnd() = runTest {
        val body = ByteChannel(autoFlush = true)
        val writer = launch { body.writeFully(ByteArray(MAXIMUM_BYTES + 1)) }
        HttpClient(MockEngine { respond(body) }).use { client ->
            assertNull(client.loadSound(URL))
        }
        writer.join()
    }

    @Test
    fun doesNotTrustAnUnderstatedContentLength() = runTest {
        HttpClient(MockEngine {
            respond(
                ByteReadChannel(ByteArray(MAXIMUM_BYTES + 1)),
                headers = headersOf(HttpHeaders.ContentLength, "1"),
            )
        }).use { client ->
            assertNull(client.loadSound(URL))
        }
    }

    private companion object {
        const val URL = "https://example.com/sound.wav"
        const val MAXIMUM_BYTES = 2 * 1_024 * 1_024
    }
}
