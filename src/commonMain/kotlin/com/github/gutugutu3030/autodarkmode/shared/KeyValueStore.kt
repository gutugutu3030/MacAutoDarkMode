package com.github.gutugutu3030.autodarkmode.shared

interface KeyValueStore {
    fun getString(key: String): String?
    fun setString(key: String, value: String)

    fun getDouble(key: String): Double?
    fun setDouble(key: String, value: Double)

    fun getInt(key: String): Int?
    fun setInt(key: String, value: Int)

    fun getBoolean(key: String): Boolean?
    fun setBoolean(key: String, value: Boolean)

    fun remove(key: String)
}