package io.github.gycrosskit.sound

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import platform.AVFAudio.AVAudioPlayer
import platform.AVFoundation.*
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSBundle
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.darwin.NSObjectProtocol
import kotlin.coroutines.EmptyCoroutineContext

/** 远端由 AVPlayer 原生预缓冲；请求或播放失败时继续播放宿主 Bundle 音效。
 *
 * prepare/play/release 自动切回 Main；后台 release 以 RELEASED 状态确认关闭已执行。
 * @param fallbackResourceName 宿主 Bundle 的无扩展名音效文件名。
 * @param fallbackResourceExtension 音效扩展名；默认 Bundle 为 mainBundle。
 */
@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
class IosSoundPlayer internal constructor(
    private val fallbackResourceName: String,
    private val fallbackResourceExtension: String,
    private val bundle: NSBundle,
    private val createRemoteItem: (NSURL) -> AVPlayerItem,
) : SoundPlayer {
    constructor(fallbackResourceName: String, fallbackResourceExtension: String, bundle: NSBundle = NSBundle.mainBundle) :
        this(fallbackResourceName, fallbackResourceExtension, bundle, { AVPlayerItem(it) })
    init { require(fallbackResourceName.isNotBlank() && fallbackResourceExtension.isNotBlank()) }
    private val mutableState = MutableStateFlow(SoundState())
    override val state: StateFlow<SoundState> = mutableState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var requestGeneration = 0
    internal var remoteJob: Job? = null
        private set
    internal var remotePlayer: AVPlayer? = null
        private set
    private var observedItem: AVPlayerItem? = null
    private val notifications = mutableListOf<NSObjectProtocol>()
    private var remotePlaybackRequested = false
    private var playbackGeneration = 0
    internal var localPlayer: AVAudioPlayer? = null
        private set

    override fun prepare(remoteUrl: String?) = onMain {
        val nextState = mutableState.value.nextPreparationState(remoteUrl) ?: return@onMain
        releaseRemote()
        val generation = requestGeneration
        mutableState.value = nextState
        if (generation != requestGeneration || mutableState.value.phase != SoundPhase.PREPARING) return@onMain
        val value = nextState.remoteUrl
        try {
            val item = createRemoteItem(checkNotNull(NSURL.URLWithString(value)))
            observedItem = item
            remotePlayer = AVPlayer(playerItem = item)
            val center = NSNotificationCenter.defaultCenter
            notifications += center.addObserverForName(AVPlayerItemDidPlayToEndTimeNotification, item, NSOperationQueue.mainQueue) {
                if (generation == requestGeneration && observedItem == item) remotePlaybackRequested = false
            }
            notifications += center.addObserverForName(AVPlayerItemFailedToPlayToEndTimeNotification, item, NSOperationQueue.mainQueue) {
                remotePlaybackFailed(item, generation, value)
            }
            val job = scope.launch {
                val ready = withTimeoutOrNull(5_000L) {
                    while (item.status != AVPlayerItemStatusReadyToPlay && item.status != AVPlayerItemStatusFailed) delay(50L)
                    item.status == AVPlayerItemStatusReadyToPlay
                } == true
                if (generation != requestGeneration || observedItem != item) return@launch
                if (!ready) {
                    markRemoteFailed(generation, value)
                    return@launch
                }
                mutableState.value = SoundState(SoundPhase.REMOTE_READY, value)
                // ponytail: 单播放器每秒检查一次，补足 READY 后但尚未 play 时没有失败通知的状态；大量实例时再用原生 KVO 桥。
                while (generation == requestGeneration && observedItem == item) {
                    delay(1_000L)
                    if (item.status == AVPlayerItemStatusFailed) remotePlaybackFailed(item, generation, value)
                }
            }
            if (generation == requestGeneration && observedItem == item) remoteJob = job else job.cancel()
        } catch (_: Exception) {
            markRemoteFailed(generation, value)
        }
    }

    override fun play() = onMain {
        if (mutableState.value.phase == SoundPhase.RELEASED) return@onMain
        val remote = remotePlayer
        if (mutableState.value.phase == SoundPhase.REMOTE_READY && remote != null) {
            localPlayer?.stop()
            localPlayer = null
            remotePlaybackRequested = true
            val playback = ++playbackGeneration
            val generation = requestGeneration
            val value = mutableState.value.remoteUrl
            val item = observedItem ?: return@onMain
            // seek 异步结束后再播放；换址、再次播放或 release 不能复活旧请求。
            remote.pause()
            remote.seekToTime(CMTimeMake(0, 1), toleranceBefore = CMTimeMake(0, 1), toleranceAfter = CMTimeMake(0, 1)) { finished -> onMain {
                if (generation == requestGeneration && playback == playbackGeneration && remotePlayer == remote && remotePlaybackRequested) {
                    if (finished) remote.play() else remotePlaybackFailed(item, generation, value)
                }
            } }
        } else {
            playLocal()
        }
    }

    private fun playLocal() {
        // StateFlow 的同步观察者可能在 REMOTE_FAILED 回执里 release；回退不能越过终态。
        if (mutableState.value.phase == SoundPhase.RELEASED) return
        val url = bundle.URLForResource(fallbackResourceName, fallbackResourceExtension) ?: return
        localPlayer?.stop()
        localPlayer = null
        runCatching {
            localPlayer = AVAudioPlayer(contentsOfURL = url, error = null).also {
                it.prepareToPlay()
                it.play()
            }
        }
    }

    private fun markRemoteFailed(generation: Int, remoteUrl: String) {
        if (generation != requestGeneration) return
        releaseRemote()
        mutableState.value = SoundState(SoundPhase.REMOTE_FAILED, remoteUrl)
    }

    private fun remotePlaybackFailed(item: AVPlayerItem, generation: Int, remoteUrl: String) {
        if (observedItem != item || generation != requestGeneration) return
        val fallback = remotePlaybackRequested
        markRemoteFailed(generation, remoteUrl)
        // markRemoteFailed 会递增代次；同步观察者换址后，旧失败不能启动回退。
        if (fallback && requestGeneration == generation + 1 &&
            mutableState.value == SoundState(SoundPhase.REMOTE_FAILED, remoteUrl)) playLocal()
    }

    private fun releaseRemote() {
        requestGeneration += 1
        remoteJob?.cancel()
        remoteJob = null
        remotePlaybackRequested = false
        playbackGeneration += 1
        observedItem?.cancelPendingSeeks()
        observedItem = null
        notifications.forEach { NSNotificationCenter.defaultCenter.removeObserver(it) }
        notifications.clear()
        remotePlayer?.pause()
        remotePlayer?.replaceCurrentItemWithPlayerItem(null)
        remotePlayer = null
    }

    override fun release() = onMain {
        if (mutableState.value.phase == SoundPhase.RELEASED) return@onMain
        releaseRemote()
        localPlayer?.stop()
        localPlayer = null
        scope.cancel()
        mutableState.value = SoundState(SoundPhase.RELEASED)
    }

    // 入口与预载共用 Main；不能用预载 scope 排队，因为 release 会取消它。
    private inline fun onMain(crossinline action: () -> Unit) {
        if (NSThread.isMainThread) action() else Dispatchers.Main.dispatch(EmptyCoroutineContext) { action() }
    }
}
