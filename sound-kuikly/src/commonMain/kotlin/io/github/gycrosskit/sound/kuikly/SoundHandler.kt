package io.github.gycrosskit.sound.kuikly

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import io.github.gycrosskit.sound.SoundPhase
import io.github.gycrosskit.sound.SoundPlayer
import kotlinx.coroutines.*

/** Android/iOS 共用接收协议，播放器和回执只归当前 Renderer，入口在 Main。 */
internal class SoundHandler(private val createPlayer: (String) -> SoundPlayer?) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: SoundPlayer? = null
    private var callback: ((String) -> Unit)? = null
    private var disposed = false

    fun call(method: String, params: String, callback: (String) -> Unit) {
        if (disposed) return
        if (method == "release") { dispose(); return }
        val args = try { JSONObject(params) } catch (_: Exception) { callback(FAILED); return }
        if ((args.has("url") && args.opt("url") !is String) ||
            (args.has("rawfile") && args.opt("rawfile") !is String)) { callback(FAILED); return }
        if (method != "prepare" && method != "play") { callback(FAILED); return }
        if (player == null) {
            val rawfile = (args.opt("rawfile") as? String)?.takeIf { it.isNotBlank() }
            if (rawfile == null) { callback(FAILED); return }
            val created = try { createPlayer(rawfile) } catch (_: Exception) { null }
            if (created == null) { callback(FAILED); return }
            player = created
            scope.launch {
                created.state.collect { state ->
                    if (!disposed && player === created) when (state.phase) {
                        SoundPhase.REMOTE_READY -> this@SoundHandler.callback?.invoke(READY)
                        SoundPhase.REMOTE_FAILED -> this@SoundHandler.callback?.invoke(FAILED)
                        else -> Unit
                    }
                }
            }
        }
        if (method == "prepare") {
            this.callback = callback
            player?.prepare(args.opt("url") as? String)
        } else player?.play()
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        callback = null
        scope.cancel()
        player?.release()
        player = null
    }

    private companion object {
        const val READY = "{\"status\":\"ready\"}"
        const val FAILED = "{\"status\":\"failed\"}"
    }
}
