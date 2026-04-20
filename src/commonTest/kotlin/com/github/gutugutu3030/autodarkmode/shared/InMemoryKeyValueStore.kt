package com.github.gutugutu3030.autodarkmode.shared

/**
 * メモリ内で動作する `KeyValueStore` 実装です。
 */
class InMemoryKeyValueStore : KeyValueStore {
    private val values = mutableMapOf<String, Any>()

    /**
     * 文字列を読み出します。
     *
     * @param key 取得対象のキーです。
     * @return 保存済みの文字列です。
     */
    override fun getString(key: String): String? = values[key] as? String

    /**
     * 文字列を保存します。
     *
     * @param key 保存先のキーです。
     * @param value 保存する文字列です。
     */
    override fun setString(key: String, value: String) {
        values[key] = value
    }

    /**
     * 小数値を読み出します。
     *
     * @param key 取得対象のキーです。
     * @return 保存済みの数値です。
     */
    override fun getDouble(key: String): Double? = values[key] as? Double

    /**
     * 小数値を保存します。
     *
     * @param key 保存先のキーです。
     * @param value 保存する数値です。
     */
    override fun setDouble(key: String, value: Double) {
        values[key] = value
    }

    /**
     * 整数値を読み出します。
     *
     * @param key 取得対象のキーです。
     * @return 保存済みの整数値です。
     */
    override fun getInt(key: String): Int? = values[key] as? Int

    /**
     * 整数値を保存します。
     *
     * @param key 保存先のキーです。
     * @param value 保存する整数値です。
     */
    override fun setInt(key: String, value: Int) {
        values[key] = value
    }

    /**
     * 真偽値を読み出します。
     *
     * @param key 取得対象のキーです。
     * @return 保存済みの真偽値です。
     */
    override fun getBoolean(key: String): Boolean? = values[key] as? Boolean

    /**
     * 真偽値を保存します。
     *
     * @param key 保存先のキーです。
     * @param value 保存する真偽値です。
     */
    override fun setBoolean(key: String, value: Boolean) {
        values[key] = value
    }

    /**
     * 指定キーの値を削除します。
     *
     * @param key 削除対象のキーです。
     */
    override fun remove(key: String) {
        values.remove(key)
    }
}