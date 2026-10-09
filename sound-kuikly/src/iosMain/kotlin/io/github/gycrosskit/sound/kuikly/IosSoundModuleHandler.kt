package io.github.gycrosskit.sound.kuikly

import io.github.gycrosskit.sound.IosSoundPlayer
import platform.Foundation.NSBundle

/** 由已有 Shared framework 导出；Pod receiver 在 Main 调用。rawfile 为 Bundle 文件名含扩展名。 */
class IosSoundModuleHandler(bundle: NSBundle) {
    constructor() : this(NSBundle.mainBundle)
    private val handler = SoundHandler { rawfile ->
        val separator = rawfile.lastIndexOf('.')
        if (separator <= 0 || separator == rawfile.lastIndex || rawfile.contains('/')) null
        else IosSoundPlayer(rawfile.substring(0, separator), rawfile.substring(separator + 1), bundle)
    }

    fun call(method: String, params: String, callback: (String) -> Unit) = handler.call(method, params, callback)
    fun dispose() = handler.dispose()
}
