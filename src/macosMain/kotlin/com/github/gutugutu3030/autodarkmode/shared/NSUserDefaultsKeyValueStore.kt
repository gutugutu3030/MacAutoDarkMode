package com.github.gutugutu3030.autodarkmode.shared

import platform.Foundation.NSNumber
import platform.Foundation.NSUserDefaults

class NSUserDefaultsKeyValueStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : KeyValueStore {
    override fun getString(key: String): String? = defaults.stringForKey(key)

    override fun setString(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }

    override fun getDouble(key: String): Double? = (defaults.objectForKey(key) as? NSNumber)?.doubleValue

    override fun setDouble(key: String, value: Double) {
        defaults.setDouble(value, forKey = key)
    }

    override fun getInt(key: String): Int? = (defaults.objectForKey(key) as? NSNumber)?.intValue

    override fun setInt(key: String, value: Int) {
        defaults.setInteger(value.toLong(), forKey = key)
    }

    override fun getBoolean(key: String): Boolean? = (defaults.objectForKey(key) as? NSNumber)?.boolValue

    override fun setBoolean(key: String, value: Boolean) {
        defaults.setBool(value, forKey = key)
    }

    override fun remove(key: String) {
        defaults.removeObjectForKey(key)
    }
}