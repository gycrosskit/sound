package io.github.gycrosskit.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 在进程内预缓冲远端短音效，远端尚未就绪或播放异常时立即回退安装包内音效。 */
class AndroidSoundPlayer(
    context: Context,
    private val fallbackSoundResId: Int,
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

    override fun prepare(remoteUrl: String?) {
        val nextState = mutableState.value.nextPreparationState(remoteUrl) ?: return
        releaseRemote()
        mutableState.value = nextState
        if (nextState.phase != SoundPhase.PREPARING) return
        val normalizedUrl = nextState.remoteUrl
        val candidate = MediaPlayer()
        remotePlayer = candidate
        runCatching {
            candidate.setAudioAttributes(SOUND_ATTRIBUTES)
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
                    val shouldFallback = remotePlaybackRequested
                    releaseRemote()
                    mutableState.value = SoundState(
                        phase = SoundPhase.REMOTE_FAILED,
                        remoteUrl = normalizedUrl,
                    )
                    if (shouldFallback) playLocal()
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

    override fun play() {
        if (mutableState.value.phase == SoundPhase.RELEASED) return
        val remote = remotePlayer
        if (mutableState.value.phase == SoundPhase.REMOTE_READY && remote != null) {
            localPlayer?.release()
            localPlayer = null
            runCatching {
                remotePlaybackRequested = true
                remote.seekTo(0)
                remote.start()
            }.onFailure {
                failRemote(remote, mutableState.value.remoteUrl)
                playLocal()
            }
        } else {
            playLocal()
        }
    }

    private fun playLocal() {
        if (mutableState.value.phase == SoundPhase.RELEASED) return
        localPlayer?.release()
        localPlayer = null
        runCatching {
            val player = MediaPlayer.create(appContext, fallbackSoundResId) ?: return
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

    private fun failRemote(candidate: MediaPlayer, remoteUrl: String) {
        if (remotePlayer !== candidate) return
        releaseRemote()
        mutableState.value = SoundState(
            phase = SoundPhase.REMOTE_FAILED,
            remoteUrl = remoteUrl,
        )
    }

    private fun releaseRemote() {
        cancelPrepareTimeout()
        remotePlaybackRequested = false
        val player = remotePlayer
        remotePlayer = null
        player?.apply {
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

    override fun release() {
        releaseRemote()
        localPlayer?.release()
        localPlayer = null
        mutableState.value = SoundState(SoundPhase.RELEASED)
    }

    private companion object {
        const val REMOTE_AUDIO_TIMEOUT_MILLIS = 5_000L

        val SOUND_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}
