package xx.zerkalo.sender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xx.zerkalo.CONTROL_PORT
import xx.zerkalo.net.FoundReceiver
import xx.zerkalo.settings.PairedDevice

class ReceiverRowsTest {
    private fun found(id: String, name: String = "TV", host: String = "10.0.0.5", port: Int = 5000) =
        FoundReceiver(id, name, host, port)

    private fun paired(id: String, name: String = "Old", host: String = "10.0.0.9") =
        PairedDevice(id, name, host, ByteArray(32))

    @Test
    fun nothingKnownMeansNoRows() {
        assertTrue(receiverRows(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun rememberedReceiverIsListedWithoutBeingHeard() {
        assertEquals(listOf(ConnectTarget("a", "Old", "10.0.0.9", CONTROL_PORT)), receiverRows(emptyList(), listOf(paired("a"))))
    }

    @Test
    fun heardReceiverIsListedOnce() {
        assertEquals(1, receiverRows(listOf(found("a")), listOf(paired("a"))).size)
    }

    @Test
    fun heardAddressAndNameBeatRememberedOnes() {
        assertEquals(listOf(ConnectTarget("a", "TV", "10.0.0.5", 5000, online = true)), receiverRows(listOf(found("a")), listOf(paired("a"))))
    }

    @Test
    fun heardReceiversComeFirst() {
        val rows = receiverRows(listOf(found("b")), listOf(paired("a")))
        assertEquals(listOf("b", "a"), rows.map { it.id })
    }
}
