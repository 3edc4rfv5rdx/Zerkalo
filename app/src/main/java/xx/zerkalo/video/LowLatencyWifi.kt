package xx.zerkalo.video

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

/**
 * A held lock that keeps Wi-Fi out of power saving, which would otherwise add latency
 * spikes. Null on a device without Wi-Fi, such as an Ethernet-only TV box.
 */
fun acquireLowLatencyWifi(context: Context): WifiManager.WifiLock? {
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        WifiManager.WIFI_MODE_FULL_LOW_LATENCY
    } else {
        @Suppress("DEPRECATION")
        WifiManager.WIFI_MODE_FULL_HIGH_PERF
    }
    return wifi.createWifiLock(mode, "zerkalo-stream").apply {
        setReferenceCounted(false)
        acquire()
    }
}
