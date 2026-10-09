package io.github.gycrosskit.sound.kuikly

import android.os.Handler
import android.os.Looper
import com.tencent.kuikly.core.render.android.IKuiklyRenderExport
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import io.github.gycrosskit.sound.AndroidSoundPlayer

/** 宿主传自己的 raw ID；每个 Renderer 拥有播放器，音频会话仍归宿主。 */
class AndroidSoundModule(private val fallbackSoundResId: Int) : KuiklyRenderBaseModule() {
    init { require(fallbackSoundResId != 0) }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var destroyed = false
    private var handler: SoundHandler? = null

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        val action = {
            if (!destroyed) {
                val current = handler ?: SoundHandler {
                    context?.let { AndroidSoundPlayer(it, fallbackSoundResId) }
                }.also { handler = it }
                current.call(method, params.orEmpty()) { result -> if (!destroyed) callback?.invoke(result) }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
        return null
    }

    override fun onDestroy() {
        destroyed = true
        val cleanup = { handler?.dispose(); handler = null; kuiklyRenderContext = null }
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup() else main.post { cleanup() }
    }
}

/** 在宿主既有 registerExternalModule 中注册，不共享 Renderer 播放器实例。 */
fun IKuiklyRenderExport.registerGycSoundModule(fallbackSoundResId: Int) {
    require(fallbackSoundResId != 0)
    moduleExport(SoundModule.NAME) { AndroidSoundModule(fallbackSoundResId) }
}
