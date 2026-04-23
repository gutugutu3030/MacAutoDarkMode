package com.github.gutugutu3030.autodarkmode.shared

/**
 * 文字列、数値、真偽値をキー単位で保存するための抽象ストアです。
 */
interface KeyValueStore {
    /**
     * 指定したキーに対応する文字列を返します。
     *
     * @param key 取得対象のキーです。
     * @return 保存済みの文字列。存在しない場合は `null` を返します。
     */
    fun getString(key: String): String?

    /**
     * 指定したキーに文字列を保存します。
     *
     * @param key 保存先のキーです。
     * @param value 保存する文字列です。
     */
    fun setString(key: String, value: String)

    /**
     * 指定したキーに対応する小数値を返します。
     *
     * @param key 取得対象のキーです。
     * @return 保存済みの数値。存在しない場合は `null` を返します。
     */
    fun getDouble(key: String): Double?

    /**
     * 指定したキーに小数値を保存します。
     *
     * @param key 保存先のキーです。
     * @param value 保存する数値です。
     */
    fun setDouble(key: String, value: Double)

    /**
     * 指定したキーに対応する整数値を返します。
     *
     * @param key 取得対象のキーです。
     * @return 保存済みの整数値。存在しない場合は `null` を返します。
     */
    fun getInt(key: String): Int?

    /**
     * 指定したキーに整数値を保存します。
     *
     * @param key 保存先のキーです。
     * @param value 保存する整数値です。
     */
    fun setInt(key: String, value: Int)

    /**
     * 指定したキーに対応する真偽値を返します。
     *
     * @param key 取得対象のキーです。
     * @return 保存済みの真偽値。存在しない場合は `null` を返します。
     */
    fun getBoolean(key: String): Boolean?

    /**
     * 指定したキーに真偽値を保存します。
     *
     * @param key 保存先のキーです。
     * @param value 保存する真偽値です。
     */
    fun setBoolean(key: String, value: Boolean)

    /**
     * 指定したキーの値を削除します。
     *
     * @param key 削除対象のキーです。
     */
    fun remove(key: String)
}
