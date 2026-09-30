package io.github.gycrosskit.sound

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SoundPlayerTest {

    @Test
    fun rejectsCredentialsWhitespaceAndPreparationAfterRelease() {
        assertNull(normalizeSoundUrl("https://user:password@example.test/a.wav"))
        assertNull(normalizeSoundUrl("https://example .test/a.wav"))
        assertNull(normalizeSoundUrl("https://example.test/a b.wav"))
        val released = SoundState(SoundPhase.RELEASED)
        assertNull(released.nextPreparationState(null))
        assertNull(released.nextPreparationState("https://example.test/a.wav"))
    }

    @Test
    fun `normalizes valid https sound URL`() {
        assertEquals(
            "https://cdn.example.com/audio/sound.mp3?v=2",
            normalizeSoundUrl("  https://cdn.example.com/audio/sound.mp3?v=2  "),
        )
    }

    @Test
    fun `rejects blank non-https and hostless sound URLs`() {
        assertNull(normalizeSoundUrl(null))
        assertNull(normalizeSoundUrl("  "))
        assertNull(normalizeSoundUrl("http://cdn.example.com/sound.mp3"))
        assertNull(normalizeSoundUrl("https:///sound.mp3"))
        assertNull(normalizeSoundUrl("not a URL"))
    }

    @Test
    fun `preparation state handles rejection deduplication and retry`() {
        val idle = SoundState()
        assertEquals(SoundState(), idle.nextPreparationState(" "))
        assertEquals(
            SoundState(SoundPhase.REMOTE_FAILED, "http://example.test/sound.mp3"),
            idle.nextPreparationState(" http://example.test/sound.mp3 "),
        )

        val preparing = checkNotNull(idle.nextPreparationState("https://example.test/sound.mp3"))
        assertEquals(SoundPhase.PREPARING, preparing.phase)
        assertNull(preparing.nextPreparationState(preparing.remoteUrl))
        assertNull(
            preparing.copy(phase = SoundPhase.REMOTE_READY)
                .nextPreparationState(preparing.remoteUrl),
        )
        assertEquals(
            preparing,
            preparing.copy(phase = SoundPhase.REMOTE_FAILED)
                .nextPreparationState(preparing.remoteUrl),
        )
    }
}
