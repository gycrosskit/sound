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

/**
 * 播放器状态快照；不代表本地音效已播放或远端文件完整下载。
 * @property phase 默认 LOCAL_ONLY；RELEASED 是不可重开的终态。
 * @property remoteUrl 已准入或被拒绝的远端地址，默认空串；避免日志记录地址中的业务查询参数。
 */
data class SoundState(
    val phase: SoundPhase = SoundPhase.LOCAL_ONLY,
    val remoteUrl: String = "",
)

/** 单个短音效播放器。Android 自动切回 Main；iOS 在主线程调用，Kuikly Module 在页面线程调用。 */
interface SoundPlayer {
    /** 只读当前状态；异步 prepare 的结果通过此流观察。 */
    val state: StateFlow<SoundState>

    /** 空地址恢复内置音效；非空地址由平台播放器异步预缓冲，不阻塞业务页面。 */
    fun prepare(remoteUrl: String?)

    /** 执行时远端已就绪则从头播放，否则播放安装包内音效。 */
    fun play()

    /** 永久关闭实例并取消迟到回调；Android 后台调用以 RELEASED 状态确认关闭，重用须新建实例。 */
    fun release()
}

/** 去除首尾空白并只接受无凭据的 HTTPS 地址；空值、内嵌空白或非法 Host 返回 null。 */
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

/** 计算准备状态：已释放或同址准备/就绪返回 null；失败同址可重试，空址恢复本地音效。 */
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
