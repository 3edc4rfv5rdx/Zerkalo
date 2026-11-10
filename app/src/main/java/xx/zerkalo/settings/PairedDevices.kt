package xx.zerkalo.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xx.zerkalo.net.TokenStore
import xx.zerkalo.parseHex
import xx.zerkalo.toHex

/** A device this one has paired with. [id] is its id in hex. */
class PairedDevice(val id: String, val name: String, val host: String, val token: ByteArray)

/**
 * The paired devices, one preference each: the id as the key, the token, host and
 * name as the value, the name last because it alone can hold any character.
 * Call [load] once before use. Network threads write here too, hence the locking.
 */
object PairedDevices : TokenStore {
    private const val PREFS = "zerkalo_paired"

    private lateinit var context: Context
    private val _devices = MutableStateFlow<List<PairedDevice>>(emptyList())
    val devices: StateFlow<List<PairedDevice>> = _devices.asStateFlow()

    @Synchronized
    fun load(appContext: Context) {
        context = appContext.applicationContext
        _devices.value = prefs().all.mapNotNull { (id, value) -> parse(id, value as? String) }.sorted()
    }

    private fun parse(id: String, value: String?): PairedDevice? {
        val parts = value?.split('\n', limit = 3)?.takeIf { it.size == 3 } ?: return null
        val token = parseHex(parts[0]) ?: return null
        return PairedDevice(id, parts[2], parts[1], token)
    }

    private fun List<PairedDevice>.sorted() = sortedWith(compareBy({ it.name }, { it.id }))

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The device with this [id] or, for an address typed in by hand ([id] null), the one
     * last seen at [host]. A known id never falls back to the address: DHCP can hand one
     * device's old address to another.
     */
    fun find(id: String?, host: String): PairedDevice? =
        _devices.value.firstOrNull { if (id != null) it.id == id else it.host == host }

    override fun token(peerId: ByteArray): ByteArray? {
        val id = peerId.toHex()
        return _devices.value.firstOrNull { it.id == id }?.token
    }

    @Synchronized
    override fun save(peerId: ByteArray, token: ByteArray, name: String, host: String) {
        val id = peerId.toHex()
        prefs().edit { putString(id, "${token.toHex()}\n$host\n$name") }
        _devices.value = (_devices.value.filterNot { it.id == id } + PairedDevice(id, name, host, token)).sorted()
    }

    @Synchronized
    fun forget(id: String) {
        prefs().edit { remove(id) }
        _devices.value = _devices.value.filterNot { it.id == id }
    }
}
