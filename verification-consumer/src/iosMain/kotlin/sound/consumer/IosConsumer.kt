package sound.consumer
import io.github.gycrosskit.sound.IosSoundPlayer
import io.github.gycrosskit.sound.SoundPlayer
fun iosSound(): SoundPlayer = IosSoundPlayer("host_sound", "wav")
