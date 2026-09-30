package io.github.gycrosskit.sound

import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.coroutines.flow.StateFlow

/** 远端准备与实例生命周期状态；本地资源播放结果由系统决定。 */
enum class SoundPhase {
    LOCAL_ONLY,
    RELEASED,
    PREPARING,
    REMOTE_READY,
    REMOTE_FAILED,
}

data class SoundState(
    val phase: SoundPhase = SoundPhase.LOCAL_ONLY,
    val remoteUrl: String = "",
)

/** 单个短音效播放器。入口由宿主主线程（Kuikly 为页面线程）串行调用。 */
interface SoundPlayer {
    val state: StateFlow<SoundState>

    /** 空地址恢复内置音效；非空地址由平台播放器异步预缓冲，不阻塞业务页面。 */
    fun prepare(remoteUrl: String?)

    /** 远端已就绪时从头播放，否则立即播放安装包内音效。 */
    fun play()

    /** 永久关闭实例并取消迟到回调；再次使用须创建新实例。 */
    fun release()
}

/** Kotlin 调用方共用同一地址准入规则，避免两端对空白、协议和 Host 的判断漂移。 */
fun normalizeSoundUrl(remoteUrl: String?): String? {
    val value = remoteUrl?.trim().orEmpty()
    if (value.isEmpty() || value.any(Char::isWhitespace)) return null
    val schemePrefix = "https://"
    if (!value.startsWith(schemePrefix, ignoreCase = true)) return null
    val authority = value.drop(schemePrefix.length)
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
    if (authority.isBlank()) return null
    val parsed = runCatching { Url(value) }.getOrNull() ?: return null
    return value.takeIf {
        parsed.protocol == URLProtocol.HTTPS && parsed.host.isNotBlank() && parsed.user == null && parsed.password == null
    }
}

/** 统一 Kotlin 调用方的地址拒绝、重复预加载和重试状态，只把实际播放交给平台实现。 */
fun SoundState.nextPreparationState(remoteUrl: String?): SoundState? {
    if (phase == SoundPhase.RELEASED) return null
    val normalizedUrl = normalizeSoundUrl(remoteUrl)
        ?: return if (remoteUrl.isNullOrBlank()) {
            SoundState()
        } else {
            SoundState(SoundPhase.REMOTE_FAILED, remoteUrl.trim())
        }
    if (
        this.remoteUrl == normalizedUrl &&
        (phase == SoundPhase.PREPARING || phase == SoundPhase.REMOTE_READY)
    ) {
        return null
    }
    return SoundState(SoundPhase.PREPARING, normalizedUrl)
}
