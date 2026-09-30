package sound.consumer
import io.github.gycrosskit.sound.SoundPlayer
import io.github.gycrosskit.sound.SoundPhase
fun prepareSound(player: SoundPlayer, url: String?) {
    if (player.state.value.phase != SoundPhase.RELEASED) player.prepare(url)
}
