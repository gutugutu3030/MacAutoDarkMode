package com.github.gutugutu3030.autodarkmode.shared

import platform.Foundation.NSNumber
import platform.Foundation.NSUserDefaults

/**
 * `NSUserDefaults` を `KeyValueStore` として扱う実装です。
 *
 * @param defaults 実際の保存先です。
 */
class NSUserDefaultsKeyValueStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : KeyValueStore {
    /**
     * 文字列を読み出します。
     *
     * @param key 取得対象のキーです。
     * @return 保存済みの文字列です。
     */
    override fun getString(key: String): String? = defaults.stringForKey(key)

    /**
     * 文字列を保存します。
     *
     * @param key 保存先のキーです。
     * @param value 保存する文字列です。
     */
    override fun setString(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }

    /**
     * 小数値を読み出します。
     *
     * @param key 取得対象のキーです。
     * @return 保存済みの数値です。
     */
    override fun getDouble(key: String): Double? = (defaults.objectForKey(key) as? NSNumber)?.doubleValue

    /**
     * 小数値を保存します。
     *
     * @param key 保存先のキーです。
     * @param value 保存する数値です。
     */
    override fun setDouble(key: String, value: Double) {
        defaults.setDouble(value, forKey = key)
    }

    /**
     * 整数値を読み出します。
     *
     * @param key 取得対象のキーです。
     * @return 保存済みの整数値です。
     */
    override fun getInt(key: String): Int? = (defaults.objectForKey(key) as? NSNumber)?.intValue

    /**
     * 整数値を保存します。
     *
     * @param key 保存先のキーです。
     * @param value 保存する整数値です。
     */
    override fun setInt(key: String, value: Int) {
        defaults.setInteger(value.toLong(), forKey = key)
    }

    /**
     * 真偽値を読み出します。
     *
     * @param key 取得対象のキーです。
     * @return 保存済みの真偽値です。
     */
    override fun getBoolean(key: String): Boolean? = (defaults.objectForKey(key) as? NSNumber)?.boolValue

    /**
     * 真偽値を保存します。
     *
     * @param key 保存先のキーです。
     * @param value 保存する真偽値です。
     */
    override fun setBoolean(key: String, value: Boolean) {
        defaults.setBool(value, forKey = key)
    }

    /**
     * 保存済みの値を削除します。
     *
     * @param key 削除対象のキーです。
     */
    override fun remove(key: String) {
        defaults.removeObjectForKey(key)
    }
}
