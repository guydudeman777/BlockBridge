package dev.blockbridge.core

import java.nio.ByteBuffer

/** Just enough RakNet to answer LAN discovery pings. */
object RakNet {
    const val ID_UNCONNECTED_PING: Byte = 0x01
    const val ID_UNCONNECTED_PING_OPEN: Byte = 0x02
    const val ID_UNCONNECTED_PONG: Byte = 0x1c

    val MAGIC = byteArrayOf(
        0x00, 0xff.toByte(), 0xff.toByte(), 0x00,
        0xfe.toByte(), 0xfe.toByte(), 0xfe.toByte(), 0xfe.toByte(),
        0xfd.toByte(), 0xfd.toByte(), 0xfd.toByte(), 0xfd.toByte(),
        0x12, 0x34, 0x56, 0x78,
    )

    private fun hasMagic(data: ByteArray, offset: Int): Boolean {
        if (data.size < offset + MAGIC.size) return false
        for (i in MAGIC.indices) if (data[offset + i] != MAGIC[i]) return false
        return true
    }

    /** Ping: id(1) time(8) magic(16) clientGuid(8) = 33 bytes. */
    fun isPing(data: ByteArray, len: Int): Boolean =
        len >= 25 &&
            (data[0] == ID_UNCONNECTED_PING || data[0] == ID_UNCONNECTED_PING_OPEN) &&
            hasMagic(data, 9)

    fun pingTime(data: ByteArray): Long = ByteBuffer.wrap(data, 1, 8).long

    fun buildPing(time: Long, clientGuid: Long): ByteArray =
        ByteBuffer.allocate(33)
            .put(ID_UNCONNECTED_PING).putLong(time).put(MAGIC).putLong(clientGuid)
            .array()

    data class Pong(val time: Long, val serverGuid: Long, val motd: String)

    /** Pong: id(1) time(8) guid(8) magic(16) strLen(2) str. */
    fun parsePong(data: ByteArray, len: Int): Pong? {
        if (len < 35 || data[0] != ID_UNCONNECTED_PONG || !hasMagic(data, 17)) return null
        val buf = ByteBuffer.wrap(data, 0, len)
        buf.get()
        val time = buf.long
        val guid = buf.long
        buf.position(33)
        val strLen = buf.short.toInt() and 0xffff
        if (35 + strLen > len) return null
        val motd = String(data, 35, strLen, Charsets.UTF_8)
        return Pong(time, guid, motd)
    }

    fun buildPong(time: Long, serverGuid: Long, motd: String): ByteArray {
        val str = motd.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(35 + str.size)
            .put(ID_UNCONNECTED_PONG).putLong(time).putLong(serverGuid).put(MAGIC)
            .putShort(str.size.toShort()).put(str)
            .array()
    }

    /**
     * Bedrock MOTD layout:
     * MCPE;line1;protocol;version;online;max;guid;line2;gamemode;gamemodeNum;port4;port6;
     * Rewrite so the console sees our name and our local port.
     */
    fun rewriteMotd(remote: String, name: String?, localPort: Int, guid: Long): String {
        val parts = remote.split(';').toMutableList()
        while (parts.size < 12) parts.add("")
        if (parts[0].isBlank()) parts[0] = "MCPE"
        if (!name.isNullOrBlank()) parts[1] = clean(name)
        if (parts[6].isBlank()) parts[6] = guid.toString()
        if (parts[7].isBlank()) parts[7] = "BlockBridge"
        if (parts[8].isBlank()) parts[8] = "Survival"
        if (parts[9].isBlank()) parts[9] = "1"
        parts[10] = localPort.toString()
        parts[11] = localPort.toString()
        // Keep trailing ';' like real servers.
        return parts.take(12).joinToString(";") + ";" +
            parts.drop(12).filter { it.isNotEmpty() }.joinToString("") { "$it;" }
    }

    /** Used when the real server has not answered yet. */
    fun fallbackMotd(name: String, localPort: Int, guid: Long): String =
        "MCPE;${clean(name)};766;1.21.50;0;10;$guid;BlockBridge;Survival;1;$localPort;$localPort;"

    private fun clean(s: String) = s.replace(';', ' ')
}
