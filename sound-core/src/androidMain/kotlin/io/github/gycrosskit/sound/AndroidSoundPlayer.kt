package io.github.gycrosskit.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 在进程内预缓冲远端短音效，远端尚未就绪或播放异常时立即回退安装包内音效。
 *
 * @param context 仅保留 applicationContext，不持有页面。
 * @param fallbackSoundResId 宿主非零 raw 音效资源；远端 5 秒未就绪时继续本地播放。
 */
class AndroidSoundPlayer @JvmOverloads constructor(
    context: Context,
    private val fallbackSoundResId: Int,
    private val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build(),
) : SoundPlayer {
    init { require(fallbackSoundResId != 0) { "A host raw resource is required" } }
    private val appContext = context.applicationContext
    private val mutableState = MutableStateFlow(SoundState())
    override val state: StateFlow<SoundState> = mutableState.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var prepareTimeout: Runnable? = null
    private var remotePlaybackRequested = false
    private var remotePlayer: MediaPlayer? = null
    private var localPlayer: MediaPlayer? = null
    private var remoteGeneration = 0
    private var playGeneration = 0
    private var seeking = false

    override fun prepare(remoteUrl: String?) = onMain {
        val nextState = mutableState.value.nextPreparationState(remoteUrl) ?: return@onMain
        releaseRemote()
        val generation = remoteGeneration
        mutableState.value = nextState
        if (generation != remoteGeneration || nextState.phase != SoundPhase.PREPARING) return@onMain
        val normalizedUrl = nextState.remoteUrl
        val candidate = MediaPlayer()
        remotePlayer = candidate
        runCatching {
            candidate.setAudioAttributes(audioAttributes)
            candidate.setDataSource(normalizedUrl)
            candidate.setOnPreparedListener { prepared ->
                if (remotePlayer === prepared) {
                    cancelPrepareTimeout()
                    mutableState.value = SoundState(
                        phase = SoundPhase.REMOTE_READY,
                        remoteUrl = normalizedUrl,
                    )
                }
            }
            candidate.setOnCompletionListener { completed ->
                if (remotePlayer === completed) remotePlaybackRequested = false
            }
            candidate.setOnErrorListener { failed, _, _ ->
                if (remotePlayer === failed) {
                    failRemote(failed, normalizedUrl, remotePlaybackRequested)
                }
                true
            }
            Runnable { failRemote(candidate, normalizedUrl) }.also { timeout ->
                prepareTimeout = timeout
                mainHandler.postDelayed(timeout, REMOTE_AUDIO_TIMEOUT_MILLIS)
            }
            candidate.prepareAsync()
        }.onFailure {
            failRemote(candidate, normalizedUrl)
        }
    }

    override fun play() = onMain {
        if (mutableState.value.phase == SoundPhase.RELEASED) return@onMain
        val remote = remotePlayer
        if (mutableState.value.phase == SoundPhase.REMOTE_READY && remote != null) {
            localPlayer?.release()
            localPlayer = null
            runCatching {
                remotePlaybackRequested = true
                playGeneration++
                if (remote.isPlaying) remote.pause()
                if (!seeking) seekRemote(remote, playGeneration)
            }.onFailure {
                failRemote(remote, mutableState.value.remoteUrl, fallback = true)
            }
        } else {
            playLocal()
        }
    }

    private fun seekRemote(player: MediaPlayer, generation: Int) {
        seeking = true
        player.setOnSeekCompleteListener { completed ->
            if (remotePlayer !== completed) return@setOnSeekCompleteListener
            seeking = false
            runCatching {
                if (generation != playGeneration) seekRemote(completed, playGeneration)
                else completed.start()
            }.onFailure { failRemote(completed, mutableState.value.remoteUrl, fallback = true) }
        }
        player.seekTo(0)
    }

    private fun playLocal() {
        if (mutableState.value.phase == SoundPhase.RELEASED) return
        localPlayer?.release()
        localPlayer = null
        runCatching {
            val player = MediaPlayer.create(appContext, fallbackSoundResId, audioAttributes, AudioManager.AUDIO_SESSION_ID_GENERATE) ?: return
            localPlayer = player
            player.setOnCompletionListener { completed ->
                if (localPlayer === completed) {
                    localPlayer = null
                    completed.release()
                }
            }
            player.setOnErrorListener { failed, _, _ ->
                if (localPlayer === failed) {
                    localPlayer = null
                    failed.release()
                }
                true
            }
            player.start()
        }.onFailure {
            localPlayer?.release()
            localPlayer = null
        }
    }

    private fun failRemote(candidate: MediaPlayer, remoteUrl: String, fallback: Boolean = false) {
        if (remotePlayer !== candidate) return
        val generation = remoteGeneration
        releaseRemote()
        mutableState.value = SoundState(
            phase = SoundPhase.REMOTE_FAILED,
            remoteUrl = remoteUrl,
        )
        // 同步状态观察者可能换址或关闭，旧失败只能给自己的请求回退。
        if (fallback && remoteGeneration == generation + 1 &&
            mutableState.value == SoundState(SoundPhase.REMOTE_FAILED, remoteUrl)) playLocal()
    }

    private fun releaseRemote() {
        remoteGeneration++
        playGeneration++
        seeking = false
        cancelPrepareTimeout()
        remotePlaybackRequested = false
        val player = remotePlayer
        remotePlayer = null
        player?.apply {
            setOnSeekCompleteListener(null)
            setOnPreparedListener(null)
            setOnCompletionListener(null)
            setOnErrorListener(null)
            release()
        }
    }

    private fun cancelPrepareTimeout() {
        prepareTimeout?.let(mainHandler::removeCallbacks)
        prepareTimeout = null
    }

    override fun release() = onMain {
        releaseRemote()
        localPlayer?.release()
        localPlayer = null
        mutableState.value = SoundState(SoundPhase.RELEASED)
    }

    // MediaPlayer 在 Main 创建，系统回调、准备超时和所有入口共用同一线程，避免 Kuikly Renderer 与释放交叉。
    private inline fun onMain(crossinline action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post { action() }
    }

    private companion object {
        const val REMOTE_AUDIO_TIMEOUT_MILLIS = 5_000L

    }
}
