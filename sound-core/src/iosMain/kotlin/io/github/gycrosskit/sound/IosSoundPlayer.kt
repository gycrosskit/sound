package io.github.gycrosskit.sound

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioPlayer
import platform.Foundation.NSBundle
import platform.Foundation.NSThread
import kotlin.coroutines.EmptyCoroutineContext

/** 将远端短音效预加载到进程内播放器；请求或解码失败时继续播放宿主 Bundle 音效。
 *
 * prepare/play/release 自动切回 Main；后台 release 以 RELEASED 状态确认关闭已执行。
 * @param fallbackResourceName 宿主 Bundle 的无扩展名音效文件名。
 * @param fallbackResourceExtension 音效扩展名；默认 Bundle 为 mainBundle。
 */
class IosSoundPlayer(
    private val fallbackResourceName: String,
    private val fallbackResourceExtension: String,
    private val bundle: NSBundle = NSBundle.mainBundle,
) : SoundPlayer {
    init { require(fallbackResourceName.isNotBlank() && fallbackResourceExtension.isNotBlank()) }
    private val mutableState = MutableStateFlow(SoundState())
    override val state: StateFlow<SoundState> = mutableState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client = HttpClient(Darwin) {
        install(HttpTimeout) {
            connectTimeoutMillis = REMOTE_AUDIO_TIMEOUT_MILLIS
            requestTimeoutMillis = REMOTE_AUDIO_TIMEOUT_MILLIS
            socketTimeoutMillis = REMOTE_AUDIO_TIMEOUT_MILLIS
        }
    }
    private var requestGeneration = 0
    private var preloadJob: Job? = null
    private var remotePlayer: AVAudioPlayer? = null
    private var localPlayer: AVAudioPlayer? = null

    @OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
    override fun prepare(remoteUrl: String?) = onMain {
        val nextState = mutableState.value.nextPreparationState(remoteUrl) ?: return@onMain
        releaseRemote()
        mutableState.value = nextState
        if (nextState.phase != SoundPhase.PREPARING) return@onMain
        val generation = requestGeneration
        val value = nextState.remoteUrl
        preloadJob = scope.launch {
            try {
                val bytes = client.loadSound(value)
                if (bytes == null) {
                    markRemoteFailed(generation, value)
                    return@launch
                }
                val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
                val prepared = AVAudioPlayer(data = data, error = null)
                if (prepared.prepareToPlay() && generation == requestGeneration) {
                    remotePlayer = prepared
                    preloadJob = null
                    mutableState.value = SoundState(
                        phase = SoundPhase.REMOTE_READY,
                        remoteUrl = value,
                    )
                } else {
                    prepared.stop()
                    markRemoteFailed(generation, value)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                markRemoteFailed(generation, value)
            }
        }
    }

    @OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
    override fun play() = onMain {
        if (mutableState.value.phase == SoundPhase.RELEASED) return@onMain
        val remote = remotePlayer
        if (mutableState.value.phase == SoundPhase.REMOTE_READY && remote != null) {
            localPlayer?.stop()
            localPlayer = null
            remote.currentTime = 0.0
            if (!remote.play()) {
                markRemoteFailed(requestGeneration, mutableState.value.remoteUrl)
                playLocal()
            }
        } else {
            playLocal()
        }
    }

    @OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
    private fun playLocal() {
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
        mutableState.value = SoundState(
            phase = SoundPhase.REMOTE_FAILED,
            remoteUrl = remoteUrl,
        )
    }

    private fun releaseRemote() {
        requestGeneration += 1
        preloadJob?.cancel()
        preloadJob = null
        remotePlayer?.stop()
        remotePlayer = null
    }

    override fun release() = onMain {
        if (mutableState.value.phase == SoundPhase.RELEASED) return@onMain
        releaseRemote()
        localPlayer?.stop()
        localPlayer = null
        client.close()
        scope.cancel()
        mutableState.value = SoundState(SoundPhase.RELEASED)
    }

    // 入口与预载共用 Main；不能用预载 scope 排队，因为 release 会取消它。
    private inline fun onMain(crossinline action: () -> Unit) {
        if (NSThread.isMainThread) action() else Dispatchers.Main.dispatch(EmptyCoroutineContext) { action() }
    }

    private companion object {
        const val REMOTE_AUDIO_TIMEOUT_MILLIS = 5_000L
    }
}

/** 流式读取且最多多读一个字节来判定超限，不能信任缺失或错误的 Content-Length。 */
internal suspend fun HttpClient.loadSound(url: String): ByteArray? = prepareGet(url).execute { response ->
    val maximumBytes = 2 * 1_024 * 1_024
    if (!response.status.isSuccess() || (response.contentLength() ?: 0L) > maximumBytes) {
        return@execute null
    }
    val channel = response.bodyAsChannel()
    val buffer = ByteArray(maximumBytes + 1)
    var size = 0
    while (size < buffer.size) {
        val read = channel.readAvailable(buffer, size, buffer.size - size)
        if (read == -1) break
        size += read
    }
    channel.closedCause?.let { throw it }
    if (size == 0 || size > maximumBytes) null else buffer.copyOf(size)
}
