package sound.consumer

import com.tencent.kuikly.core.render.android.IKuiklyRenderExport
import io.github.gycrosskit.sound.kuikly.registerGycSoundModule

fun registerSoundNative(export: IKuiklyRenderExport, hostRawId: Int) = export.registerGycSoundModule(hostRawId)
