package xx.zerkalo.sender

import xx.zerkalo.CONTROL_PORT
import xx.zerkalo.net.FoundReceiver
import xx.zerkalo.settings.PairedDevice

/**
 * What the sender's list shows: the receivers heard on the network, then the remembered
 * ones not heard right now, which may still answer at their last address. A receiver
 * that is heard takes its current address and name from the network.
 */
fun receiverRows(found: List<FoundReceiver>, remembered: List<PairedDevice>): List<ConnectTarget> {
    val heard = found.map { ConnectTarget(it.id, it.name, it.host, it.port) }
    val heardIds = found.map { it.id }.toSet()
    val silent = remembered.filterNot { it.id in heardIds }.map { ConnectTarget(it.id, it.name, it.host, CONTROL_PORT) }
    return heard + silent
}
