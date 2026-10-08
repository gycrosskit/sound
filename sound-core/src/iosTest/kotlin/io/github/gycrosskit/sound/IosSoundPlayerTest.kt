package io.github.gycrosskit.sound

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
import kotlin.test.assertNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.*
import platform.Foundation.*
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.addressOf

class IosSoundPlayerTest {
    @OptIn(ExperimentalCoroutinesApi::class, BetaInteropApi::class, ExperimentalForeignApi::class)
    @Test fun nativeFailureFallsBackUnlessFailureObserverReleasesOrReplacesRequest() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val path = NSTemporaryDirectory() + NSUUID().UUIDString + ".bundle"
        val files = NSFileManager.defaultManager
        files.createDirectoryAtPath(path, true, null, null)
        val bytes = testWave()
        bytes.usePinned { NSData.create(it.addressOf(0), bytes.size.toULong()).writeToFile("$path/host_resource.wav", true) }
        val bundle = checkNotNull(NSBundle.bundleWithPath(path))
        assertTrue(bundle.URLForResource("host_resource", "wav") != null, "fixture must provide an actual fallback resource")
        val fallbackPlayer = IosSoundPlayer("host_resource", "wav", bundle, ::PreparedStreamItem)
        fallbackPlayer.prepare(URL); runCurrent(); fallbackPlayer.play()
        NSNotificationCenter.defaultCenter.postNotificationName(AVPlayerItemFailedToPlayToEndTimeNotification, fallbackPlayer.remotePlayer?.currentItem)
        assertEquals(SoundPhase.REMOTE_FAILED, fallbackPlayer.state.value.phase)
        assertTrue(fallbackPlayer.localPlayer != null, "a failed requested stream must create the actual bundle fallback")
        fallbackPlayer.release()
        val player = IosSoundPlayer("host_resource", "wav", bundle, ::PreparedStreamItem)
        val observer = launch(UnconfinedTestDispatcher(testScheduler)) {
            player.state.collect { if (it.phase == SoundPhase.REMOTE_FAILED) player.release() }
        }
        try {
            player.prepare(URL); runCurrent(); player.play()
            val remote = checkNotNull(player.remotePlayer)
            NSNotificationCenter.defaultCenter.postNotificationName(AVPlayerItemFailedToPlayToEndTimeNotification, remote.currentItem)
            assertEquals(SoundPhase.RELEASED, player.state.value.phase)
            assertNull(player.localPlayer, "synchronous release must prevent even creating the native fallback")

            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val replacementPlayer = IosSoundPlayer("host_resource", "wav", bundle, ::PreparedStreamItem)
            val secondUrl = "$URL?second"
            var replacementPlayed = false
            val replacementObserver = launch(UnconfinedTestDispatcher(testScheduler)) {
                replacementPlayer.state.collect {
                    if (it.phase == SoundPhase.REMOTE_FAILED) replacementPlayer.prepare(secondUrl)
                    if (it.phase == SoundPhase.REMOTE_READY && it.remoteUrl == secondUrl) {
                        assertNull(replacementPlayer.localPlayer, "old failure must not start fallback before the replacement READY observer plays")
                        replacementPlayer.play()
                        replacementPlayed = true
                    }
                }
            }
            try {
                replacementPlayer.prepare(URL)
                replacementPlayer.play()
                val oldItem = checkNotNull(replacementPlayer.remotePlayer?.currentItem)
                NSNotificationCenter.defaultCenter.postNotificationName(AVPlayerItemFailedToPlayToEndTimeNotification, oldItem)
                assertEquals(SoundState(SoundPhase.REMOTE_READY, secondUrl), replacementPlayer.state.value)
                assertTrue(replacementPlayer.remotePlayer?.currentItem !== oldItem)
                assertNull(replacementPlayer.localPlayer, "the old failure must not start fallback after a synchronous replacement and play")
                runCurrent()
                assertTrue(replacementPlayed, "the new READY observer must exercise play after replacing the failed request")
            } finally { replacementObserver.cancel(); replacementPlayer.release() }
        } finally {
            observer.cancel(); player.release(); Dispatchers.resetMain()
            files.removeItemAtPath(path, null)
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class, BetaInteropApi::class, ExperimentalForeignApi::class)
    @Test fun nativeStreamingIgnoresOldFailuresAndRemainsReleasedAfterPlay() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val candidates = mutableListOf<PreparedStreamItem>()
        val player = IosSoundPlayer("host_resource", "wav", NSBundle.mainBundle) { url ->
            assertEquals(URL, url.absoluteString, "production preparation passes the HTTPS URL directly to AVPlayerItem")
            PreparedStreamItem(url).also(candidates::add)
        }
        try {
            player.prepare(URL); runCurrent()
            assertEquals(SoundPhase.REMOTE_READY, player.state.value.phase)
            val old = candidates.last()
            old.preparedStatus = AVPlayerItemStatusFailed
            advanceTimeBy(1_000); runCurrent()
            assertEquals(SoundPhase.REMOTE_FAILED, player.state.value.phase)
            player.prepare(URL); runCurrent()
            val current = candidates.last()
            assertEquals(SoundPhase.REMOTE_READY, player.state.value.phase)
            NSNotificationCenter.defaultCenter.postNotificationName(AVPlayerItemFailedToPlayToEndTimeNotification, old)
            assertEquals(SoundPhase.REMOTE_READY, player.state.value.phase, "old item cannot fail its replacement")
            player.play()
            player.play()
            player.release()
            current.preparedStatus = AVPlayerItemStatusFailed
            NSNotificationCenter.defaultCenter.postNotificationName(AVPlayerItemFailedToPlayToEndTimeNotification, current)
            advanceTimeBy(2_000); runCurrent()
            assertEquals(SoundPhase.RELEASED, player.state.value.phase)
            assertNull(player.remotePlayer)
        } finally { player.release(); Dispatchers.resetMain() }
    }

    @OptIn(ExperimentalCoroutinesApi::class, BetaInteropApi::class, ExperimentalForeignApi::class)
    @Test fun nativePreparationTimesOutAfterFiveSecondsAndIgnoresLateReadiness() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val candidate = PreparedStreamItem(checkNotNull(NSURL.URLWithString(URL)))
        candidate.preparedStatus = AVPlayerItemStatusUnknown
        val player = IosSoundPlayer("host_resource", "wav", NSBundle.mainBundle) { candidate }
        try {
            player.prepare(URL); runCurrent()
            advanceTimeBy(4_999); runCurrent()
            assertEquals(SoundPhase.PREPARING, player.state.value.phase)
            advanceTimeBy(1); runCurrent()
            assertEquals(SoundPhase.REMOTE_FAILED, player.state.value.phase)
            candidate.preparedStatus = AVPlayerItemStatusReadyToPlay
            advanceTimeBy(1_000); runCurrent()
            assertEquals(SoundPhase.REMOTE_FAILED, player.state.value.phase)
        } finally { player.release(); Dispatchers.resetMain() }
    }

    @OptIn(ExperimentalCoroutinesApi::class, BetaInteropApi::class, ExperimentalForeignApi::class)
    @Test fun releaseFromPreparingObserverPreventsNativePlayerCreation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var created = false
        val player = IosSoundPlayer("host_resource", "wav", NSBundle.mainBundle) { url ->
            created = true
            PreparedStreamItem(url)
        }
        val observer = launch(UnconfinedTestDispatcher(testScheduler)) {
            player.state.collect { if (it.phase == SoundPhase.PREPARING) player.release() }
        }
        try {
            player.prepare(URL)
            assertEquals(SoundPhase.RELEASED, player.state.value.phase)
            assertTrue(!created, "synchronous release must stop native streaming setup")
        } finally { observer.cancel(); player.release(); Dispatchers.resetMain() }
    }
    @OptIn(ExperimentalCoroutinesApi::class, BetaInteropApi::class, ExperimentalForeignApi::class)
    @Test fun readyObserverReentryKeepsTheNewPreparationJobOwnedAndCancellable() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val second = "$URL?second"
        val third = "$URL?third"
        val player = IosSoundPlayer("host_resource", "wav", NSBundle.mainBundle) { url ->
            PreparedStreamItem(url).also { if (url.absoluteString == third) it.preparedStatus = AVPlayerItemStatusUnknown }
        }
        var reentrantJob: kotlinx.coroutines.Job? = null
        val observer = launch(UnconfinedTestDispatcher(testScheduler)) {
            player.state.collect { if (it.phase == SoundPhase.REMOTE_READY && it.remoteUrl == URL) {
                player.prepare(second)
                reentrantJob = player.remoteJob
            } }
        }
        try {
            player.prepare(URL)
            assertEquals(SoundState(SoundPhase.REMOTE_READY, second), player.state.value)
            val secondJob = checkNotNull(player.remoteJob)
            assertTrue(secondJob === reentrantJob, "the first launch must not overwrite the reentrant second launch handle")
            assertTrue(secondJob.isActive, "reentrant preparation must retain the second monitor, not the completed first job")
            player.prepare(third)
            assertTrue(secondJob.isCancelled, "replacement must cancel the monitor owned by the second request")
            val thirdJob = checkNotNull(player.remoteJob)
            player.release()
            assertTrue(thirdJob.isCancelled)
            assertNull(player.remoteJob)
        } finally { observer.cancel(); player.release(); Dispatchers.resetMain() }
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

    private companion object {
        const val URL = "https://example.com/sound.wav"
    }
}

private fun testWave(payloadSize: Int = 1600): ByteArray = ByteArray(44 + payloadSize).apply {
    "RIFF".encodeToByteArray().copyInto(this, 0)
    "WAVEfmt ".encodeToByteArray().copyInto(this, 8)
    "data".encodeToByteArray().copyInto(this, 36)
    fun put(offset: Int, value: Int, length: Int = 4) {
        repeat(length) { index -> this[offset + index] = (value ushr (8 * index)).toByte() }
    }
    put(4, size - 8); put(16, 16); put(20, 1, 2); put(22, 1, 2)
    put(24, 8000); put(28, 16000); put(32, 2, 2); put(34, 16, 2); put(40, payloadSize)
}

@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
private class PreparedStreamItem(url: NSURL) : AVPlayerItem(AVURLAsset(url, null), automaticallyLoadedAssetKeys = null) {
    var preparedStatus = AVPlayerItemStatusReadyToPlay
    override fun status(): AVPlayerItemStatus = preparedStatus
}
