package com.github.gutugutu3030.autodarkmode.shared

class InMemoryKeyValueStore : KeyValueStore {
    private val values = mutableMapOf<String, Any>()

    override fun getString(key: String): String? = values[key] as? String

    override fun setString(key: String, value: String) {
        values[key] = value
    }

    override fun getDouble(key: String): Double? = values[key] as? Double

    override fun setDouble(key: String, value: Double) {
        values[key] = value
    }

    override fun getInt(key: String): Int? = values[key] as? Int

    override fun setInt(key: String, value: Int) {
        values[key] = value
    }

    override fun getBoolean(key: String): Boolean? = values[key] as? Boolean

    override fun setBoolean(key: String, value: Boolean) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}