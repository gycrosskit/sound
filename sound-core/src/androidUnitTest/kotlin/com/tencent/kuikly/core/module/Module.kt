package com.tencent.kuikly.core.module

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

typealias CallbackRef = Int

/** 只截获原生调用；状态、代次和关闭逻辑来自生产 SoundModule。 */
abstract class Module {
    data class ReturnValue(val callbackRef: CallbackRef?)
    data class Call(val method: String, val deliver: (JSONObject?) -> Unit)
    val calls = mutableListOf<Call>()
    val liveCallbacks = mutableSetOf<CallbackRef>()
    private var sequence = 0
    abstract fun moduleName(): String
    fun toNative(keepCallbackAlive: Boolean, method: String, params: Any?, callback: ((JSONObject?) -> Unit)?, syncCall: Boolean): ReturnValue {
        val ref = callback?.let { ++sequence }
        ref?.let(liveCallbacks::add)
        calls += Call(method) { callback?.invoke(it) }
        return ReturnValue(ref)
    }
    fun asyncToNativeMethod(method: String, params: JSONObject?, callback: Any?) { calls += Call(method) {} }
    fun removeCallback(ref: CallbackRef) { liveCallbacks.remove(ref) }
}
