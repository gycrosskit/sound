package io.github.gycrosskit.sound

import android.media.MediaPlayer
import android.os.Looper
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class AndroidSoundPlayerTest {
    @Test
    fun rendererEntrypointsUseMainAndMainCallsRemainImmediate() {
        val created = recordPlayers()
        val player = AndroidSoundPlayer(RuntimeEnvironment.getApplication(), 1)
        background { player.prepare(URL) }
        assertEquals(SoundPhase.LOCAL_ONLY, player.state.value.phase)
        assertTrue(created.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SoundPhase.PREPARING, player.state.value.phase)
        val media = shadowOf(created.single())
        assertSame(Looper.getMainLooper(), media.handler.looper)
        media.invokePreparedListener()
        background { player.play() }
        assertEquals(ShadowMediaPlayer.State.PREPARED, media.state)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(ShadowMediaPlayer.State.STARTED, media.state)
        background { player.release() }
        assertEquals(SoundPhase.REMOTE_READY, player.state.value.phase)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(ShadowMediaPlayer.State.END, media.state)
        assertEquals(SoundPhase.RELEASED, player.state.value.phase)

        val mainPlayer = AndroidSoundPlayer(RuntimeEnvironment.getApplication(), 1)
        mainPlayer.prepare(URL)
        assertEquals(SoundPhase.PREPARING, mainPlayer.state.value.phase)
        val mainMedia = shadowOf(created.last())
        mainMedia.invokePreparedListener()
        mainPlayer.play()
        assertEquals(ShadowMediaPlayer.State.STARTED, mainMedia.state)
        mainPlayer.release()
        assertEquals(ShadowMediaPlayer.State.END, mainMedia.state)
        assertEquals(SoundPhase.RELEASED, mainPlayer.state.value.phase)
    }

    @Test
    fun timeoutAndReleaseDiscardLateCallbacksAndQueuedWork() {
        val created = recordPlayers()
        val player = AndroidSoundPlayer(RuntimeEnvironment.getApplication(), 1)
        player.prepare(URL)
        val media = shadowOf(created.single())
        val latePrepared = checkNotNull(media.onPreparedListener)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(SoundPhase.REMOTE_FAILED, player.state.value.phase)
        assertEquals(ShadowMediaPlayer.State.END, media.state)
        latePrepared.onPrepared(created.single())
        assertEquals(SoundPhase.REMOTE_FAILED, player.state.value.phase)
        player.release()

        val closingPlayer = AndroidSoundPlayer(RuntimeEnvironment.getApplication(), 1)
        closingPlayer.prepare(URL)
        val closingMedia = created.last()
        val closingPrepared = checkNotNull(shadowOf(closingMedia).onPreparedListener)
        background { closingPlayer.prepare("https://example.test/new.wav"); closingPlayer.play() }
        closingPlayer.release()
        closingPrepared.onPrepared(closingMedia)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(2, created.size, "Main 释放先于排队准备/播放时不得创建新播放器")
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(closingMedia).state)
        assertEquals(SoundPhase.RELEASED, closingPlayer.state.value.phase)
    }

    private fun recordPlayers(): MutableList<MediaPlayer> {
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(URL), ShadowMediaPlayer.MediaInfo(60_000, -1))
        val created = mutableListOf<MediaPlayer>()
        ShadowMediaPlayer.setCreateListener { player, _ ->
            assertSame(Looper.getMainLooper(), Looper.myLooper(), "必须在 Main 创建实际 MediaPlayer")
            created += player
        }
        return created
    }

    private fun background(action: () -> Unit) {
        var failure: Throwable? = null
        Thread { try { action() } catch (error: Throwable) { failure = error } }.apply { start(); join() }
        failure?.let { throw it }
    }

    private companion object { const val URL = "https://example.test/sound.wav" }
}
