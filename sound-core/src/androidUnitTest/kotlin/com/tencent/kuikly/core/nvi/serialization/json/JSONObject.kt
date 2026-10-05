package com.tencent.kuikly.core.nvi.serialization.json

class JSONObject {
    private val values = mutableMapOf<String, Any>()
    fun put(key: String, value: Any) { values[key] = value }
    fun optString(key: String) = values[key] as? String ?: ""
    override fun toString() = values.toString()
}
