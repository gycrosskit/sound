package io.github.gycrosskit.sound.kuikly

import io.github.gycrosskit.sound.SoundPhase
import io.github.gycrosskit.sound.SoundPlayer
import io.github.gycrosskit.sound.SoundState
import io.github.gycrosskit.sound.nextPreparationState
import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 页面拥有播放器；所有入口沿 Kuikly 线程执行，根会话退出和页面销毁均调用 release。 */
class SoundModule(private val fallbackRawFile: String) : Module(), SoundPlayer {
    init { require(fallbackRawFile.isNotBlank()) }
    private val mutableState = MutableStateFlow(SoundState())
    override val state = mutableState.asStateFlow()
    private var generation = 0
    private var callbackRef: CallbackRef? = null

    override fun moduleName(): String = NAME

    override fun prepare(remoteUrl: String?) {
        val next = mutableState.value.nextPreparationState(remoteUrl) ?: return
        clearCallback()
        mutableState.value = next
        val request = generation
        val url = next.remoteUrl.takeIf { next.phase == SoundPhase.PREPARING }.orEmpty()
        val params = JSONObject().apply { put("url", url); put("rawfile", fallbackRawFile) }
        if (url.isEmpty()) {
            asyncToNativeMethod("prepare", params, null)
            return
        }
        callbackRef = toNative(true, "prepare", params.toString(), { result ->
            if (generation == request) {
                mutableState.value = next.copy(phase = when (result?.optString("status")) {
                    "ready" -> SoundPhase.REMOTE_READY
                    else -> SoundPhase.REMOTE_FAILED
                })
            }
        }, false).callbackRef
    }

    override fun play() {
        if (mutableState.value.phase == SoundPhase.RELEASED) return
        asyncToNativeMethod("play", JSONObject().apply { put("rawfile", fallbackRawFile) }, null)
    }

    override fun release() {
        if (mutableState.value.phase == SoundPhase.RELEASED) return
        clearCallback()
        mutableState.value = SoundState(SoundPhase.RELEASED)
        asyncToNativeMethod("release", null, null)
    }

    private fun clearCallback() {
        generation++
        callbackRef?.let(::removeCallback)
        callbackRef = null
    }

    companion object { const val NAME = "GycSound" }
}
