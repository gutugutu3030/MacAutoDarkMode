@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.github.gutugutu3030.autodarkmode.app

import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.invoke
import kotlinx.cinterop.reinterpret
import platform.CoreFoundation.CFRelease
import platform.IOKit.IOHIDServiceClientRef
import platform.posix.RTLD_LAZY
import platform.posix.dlclose
import platform.posix.dlopen
import platform.posix.dlsym

private const val ambientLightSensorEvent = 12
private const val bezelServicesFrameworkPath = "/System/Library/PrivateFrameworks/BezelServices.framework/BezelServices"

private fun ioHIDEventFieldBase(type: Int): Int = type shl 16

private typealias CopyALSServiceClientFn = CFunction<() -> IOHIDServiceClientRef?>
private typealias CopyEventFn = CFunction<(IOHIDServiceClientRef?, Long, Int, Long) -> CPointer<out CPointed>?>
private typealias GetFloatValueFn = CFunction<(CPointer<out CPointed>?, Int) -> Double>

/**
 * 周囲光センサーの取得可否を表します。
 *
 * @property displayName ログや表示に使う名前です。
 */
enum class NativeAmbientLightSource(val displayName: String) {
    Unavailable("Unavailable"),
    HID("IOHID + BezelServices"),
}

/**
 * 周囲光の読み取り結果です。
 *
 * @property lux 測定した周囲光です。
 * @property source 読み取り経路です。
 */
data class NativeAmbientLightReading(
    val lux: Double,
    val source: NativeAmbientLightSource,
)

/**
 * BezelServices を動的読み込みして周囲光を取得するリーダーです。
 */
class NativeAmbientLightReader {
    private var bezelServicesHandle: CPointer<out CPointed>? = null
    private var copyALSServiceClient: CPointer<CopyALSServiceClientFn>? = null
    private var copyEvent: CPointer<CopyEventFn>? = null
    private var getFloatValue: CPointer<GetFloatValueFn>? = null
    private var hidClient: IOHIDServiceClientRef? = null

    /**
     * センサーが使えるかどうかを返します。
     *
     * @return 利用可能なら `true`、そうでなければ `false` です。
     */
    fun isSensorAvailable(): Boolean {
        val available = ensureLoaded() && hidClient() != null
        println("[autoDarkMode] NativeAmbientLightReader availability: $available")
        return available
    }

    /**
     * 現在の周囲光を取得します。
     *
     * @return 読み取り結果。取得できない場合は `null` を返します。
     */
    fun currentReading(): NativeAmbientLightReading? {
        if (!ensureLoaded()) {
            return null
        }

        val value = getFloatValue
        if (value == null) {
            return null
        }

        val event = copyAmbientLightEvent() ?: return null
        val lux = value.invoke(event, ioHIDEventFieldBase(ambientLightSensorEvent))
        CFRelease(event)

        if (lux < 0) {
            return null
        }

        println("[autoDarkMode] NativeAmbientLightReader sample: ${formatLux(lux)}")
        return NativeAmbientLightReading(lux = lux, source = NativeAmbientLightSource.HID)
    }

    /**
     * 保持しているネイティブリソースを解放します。
     */
    fun close() {
        hidClient?.let {
            CFRelease(it)
            hidClient = null
        }

        bezelServicesHandle?.let {
            dlclose(it)
            bezelServicesHandle = null
        }

        copyALSServiceClient = null
        copyEvent = null
        getFloatValue = null
    }

    /**
     * BezelServices のシンボルを遅延ロードします。
     *
     * @return 必要な関数ポインタを揃えられた場合は `true` です。
     */
    private fun ensureLoaded(): Boolean {
        if (copyALSServiceClient != null && copyEvent != null && getFloatValue != null) {
            return true
        }

        // まずフレームワーク本体を開きます。
        if (bezelServicesHandle == null) {
            bezelServicesHandle = dlopen(bezelServicesFrameworkPath, RTLD_LAZY)
            if (bezelServicesHandle == null) {
                println("[autoDarkMode] NativeAmbientLightReader failed to open BezelServices framework.")
                return false
            }
        }

        // 必要なシンボルを個別に解決します。
        copyALSServiceClient = dlsym(bezelServicesHandle, "ALCALSCopyALSServiceClient")
            ?.reinterpret<CopyALSServiceClientFn>()
        copyEvent = dlsym(bezelServicesHandle, "IOHIDServiceClientCopyEvent")
            ?.reinterpret<CopyEventFn>()
        getFloatValue = dlsym(bezelServicesHandle, "IOHIDEventGetFloatValue")
            ?.reinterpret<GetFloatValueFn>()

        if (copyALSServiceClient != null && copyEvent != null && getFloatValue != null) {
            println("[autoDarkMode] NativeAmbientLightReader loaded BezelServices symbols.")
        }

        val loaded = copyALSServiceClient != null && copyEvent != null && getFloatValue != null
        if (!loaded) {
            println("[autoDarkMode] NativeAmbientLightReader missing one or more BezelServices symbols.")
        }

        return loaded
    }

    /**
     * センサークライアントを遅延生成します。
     *
     * @return クライアントです。
     */
    private fun hidClient(): IOHIDServiceClientRef? {
        if (hidClient == null) {
            hidClient = copyALSServiceClient?.invoke()
        }

        return hidClient
    }

    /**
     * 周囲光イベントを取得します。
     *
     * @return 生の HID イベントです。
     */
    private fun copyAmbientLightEvent(): CPointer<out CPointed>? {
        val client = hidClient() ?: return null
        return copyEvent?.invoke(client, ambientLightSensorEvent.toLong(), 0, 0L)
    }
}
