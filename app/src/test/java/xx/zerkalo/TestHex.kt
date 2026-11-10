package xx.zerkalo

/** Bytes from hex, with spaces allowed between them for readability. */
fun hex(text: String): ByteArray =
    text.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
