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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.flow.collect
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import platform.Foundation.NSThread
import platform.Foundation.NSBundle
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioPlayer
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToFile
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.addressOf

class IosSoundPlayerTest {
    @OptIn(ExperimentalCoroutinesApi::class, BetaInteropApi::class, ExperimentalForeignApi::class)
    @Test fun releaseFromFailureObserverPreventsFallbackReentry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val path = NSTemporaryDirectory() + NSUUID().UUIDString + ".bundle"
        val files = NSFileManager.defaultManager
        files.createDirectoryAtPath(path, true, null, null)
        val bytes = testWave()
        bytes.usePinned { NSData.create(it.addressOf(0), bytes.size.toULong()).writeToFile("$path/host_resource.wav", true) }
        val bundle = checkNotNull(NSBundle.bundleWithPath(path))
        assertTrue(bundle.URLForResource("host_resource", "wav") != null, "fixture must provide an actual fallback resource")
        val client = HttpClient(MockEngine.create {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { respond(bytes) }
        })
        val player = IosSoundPlayer("host_resource", "wav", bundle, client, ::PreparedAudioPlayer)
        val observer = launch(UnconfinedTestDispatcher(testScheduler)) {
            player.state.collect { if (it.phase == SoundPhase.REMOTE_FAILED) player.release() }
        }
        try {
            player.prepare(URL); runCurrent(); player.play()
            val remote = checkNotNull(player.remotePlayer)
            checkNotNull(remote.delegate).audioPlayerDecodeErrorDidOccur(remote, null)
            assertEquals(SoundPhase.RELEASED, player.state.value.phase)
            assertNull(player.localPlayer, "synchronous release must prevent even creating the native fallback")
        } finally {
            observer.cancel(); player.release(); Dispatchers.resetMain()
            files.removeItemAtPath(path, null)
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class, BetaInteropApi::class)
    @Test fun asynchronousDecoderFailuresIgnoreReplacedAndReleasedPlayers() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = HttpClient(MockEngine.create {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { respond(testWave()) }
        })
        // 原生音频输出需要应用 AudioSession；这里只替换 SDK 准备/播放，不替换状态机或 delegate。
        val player = IosSoundPlayer("host_resource", "wav", NSBundle.mainBundle, client, ::PreparedAudioPlayer)
        try {
            player.prepare(URL)
            runCurrent()
            assertEquals(SoundPhase.REMOTE_READY, player.state.value.phase, "first prepared WAV")
            val old = checkNotNull(player.remotePlayer)
            val oldDelegate = checkNotNull(old.delegate)
            oldDelegate.audioPlayerDecodeErrorDidOccur(old, null)
            assertEquals(SoundPhase.REMOTE_FAILED, player.state.value.phase)
            player.prepare(URL)
            runCurrent()
            assertEquals(SoundPhase.REMOTE_READY, player.state.value.phase, "retry prepared WAV")
            oldDelegate.audioPlayerDidFinishPlaying(old, successfully = false)
            assertEquals(SoundPhase.REMOTE_READY, player.state.value.phase, "late old decoder cannot fail the new player")
            val current = checkNotNull(player.remotePlayer)
            val delegate = checkNotNull(current.delegate)
            delegate.audioPlayerDidFinishPlaying(current, successfully = false)
            assertEquals(SoundPhase.REMOTE_FAILED, player.state.value.phase)
            player.release()
            delegate.audioPlayerDecodeErrorDidOccur(current, null)
            assertEquals(SoundPhase.RELEASED, player.state.value.phase)
        } finally { player.release(); Dispatchers.resetMain() }
    }
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

private fun testWave(): ByteArray = ByteArray(44 + 1600).apply {
    "RIFF".encodeToByteArray().copyInto(this, 0)
    "WAVEfmt ".encodeToByteArray().copyInto(this, 8)
    "data".encodeToByteArray().copyInto(this, 36)
    fun put(offset: Int, value: Int, length: Int = 4) {
        repeat(length) { index -> this[offset + index] = (value ushr (8 * index)).toByte() }
    }
    put(4, size - 8); put(16, 16); put(20, 1, 2); put(22, 1, 2)
    put(24, 8000); put(28, 16000); put(32, 2, 2); put(34, 16, 2); put(40, 1600)
}

@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
private class PreparedAudioPlayer(data: NSData) : AVAudioPlayer(data = data, fileTypeHint = "wav", error = null) {
    override fun prepareToPlay(): Boolean = true
    override fun play(): Boolean = true
    override fun stop() = Unit
}
