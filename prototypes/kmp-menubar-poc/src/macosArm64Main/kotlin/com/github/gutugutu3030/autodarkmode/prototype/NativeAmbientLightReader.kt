@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.github.gutugutu3030.autodarkmode.prototype

import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.invoke
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

enum class NativeAmbientLightSource(val displayName: String) {
    Unavailable("Unavailable"),
    HID("IOHID + BezelServices"),
}

data class NativeAmbientLightReading(
    val lux: Double,
    val source: NativeAmbientLightSource,
)

class NativeAmbientLightReader {
    private var bezelServicesHandle: CPointer<out CPointed>? = null
    private var copyALSServiceClient: CPointer<CopyALSServiceClientFn>? = null
    private var copyEvent: CPointer<CopyEventFn>? = null
    private var getFloatValue: CPointer<GetFloatValueFn>? = null
    private var hidClient: IOHIDServiceClientRef? = null

    fun isSensorAvailable(): Boolean {
        val available = ensureLoaded() && hidClient() != null
        println("[kmp-menubar-poc] NativeAmbientLightReader availability: $available")
        return available
    }

    fun currentReading(): NativeAmbientLightReading? {
        if (!ensureLoaded()) {
            return null
        }

        val event = copyAmbientLightEvent() ?: return null
        val lux = getFloatValue?.invoke(event, ioHIDEventFieldBase(ambientLightSensorEvent)) ?: return null
        CFRelease(event)

        if (lux < 0) {
            return null
        }

        println("[kmp-menubar-poc] NativeAmbientLightReader sample: ${formatLux(lux)}")
        return NativeAmbientLightReading(lux = lux, source = NativeAmbientLightSource.HID)
    }

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

    private fun ensureLoaded(): Boolean {
        if (copyALSServiceClient != null && copyEvent != null && getFloatValue != null) {
            return true
        }

        if (bezelServicesHandle == null) {
            bezelServicesHandle = dlopen(bezelServicesFrameworkPath, RTLD_LAZY)
            if (bezelServicesHandle == null) {
                println("[kmp-menubar-poc] NativeAmbientLightReader failed to open BezelServices framework.")
                return false
            }
        }

        copyALSServiceClient = dlsym(bezelServicesHandle, "ALCALSCopyALSServiceClient")
            ?.reinterpret<CopyALSServiceClientFn>()
        copyEvent = dlsym(bezelServicesHandle, "IOHIDServiceClientCopyEvent")
            ?.reinterpret<CopyEventFn>()
        getFloatValue = dlsym(bezelServicesHandle, "IOHIDEventGetFloatValue")
            ?.reinterpret<GetFloatValueFn>()

        if (copyALSServiceClient != null && copyEvent != null && getFloatValue != null) {
            println("[kmp-menubar-poc] NativeAmbientLightReader loaded BezelServices symbols.")
        }

        val loaded = copyALSServiceClient != null && copyEvent != null && getFloatValue != null
        if (!loaded) {
            println("[kmp-menubar-poc] NativeAmbientLightReader missing one or more BezelServices symbols.")
        }

        return loaded
    }

    private fun hidClient(): IOHIDServiceClientRef? {
        if (hidClient == null) {
            hidClient = copyALSServiceClient?.invoke()
        }

        return hidClient
    }

    private fun copyAmbientLightEvent(): CPointer<out CPointed>? {
        val client = hidClient() ?: return null
        return copyEvent?.invoke(client, ambientLightSensorEvent.toLong(), 0, 0L)
    }
}