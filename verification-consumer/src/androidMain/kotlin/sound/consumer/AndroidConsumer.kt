package sound.consumer
import android.content.Context
import io.github.gycrosskit.sound.AndroidSoundPlayer
import io.github.gycrosskit.sound.SoundPlayer
fun androidSound(context: Context, hostRawResource: Int): SoundPlayer = AndroidSoundPlayer(context, hostRawResource)
