package com.github.blebrowserbridge

import java.security.MessageDigest

/**
 * The BLE advertisement payload format shared by server and clients:
 * [group tag (4)] [counter (1)] [page hi] [page lo] [name (up to 13 bytes)]
 *
 * Pure logic with no Android dependencies - unit-tested in SyncProtocolTest.
 */
object SyncProtocol {
    const val MAX_ADVERTISEMENT_BYTES = 20
    const val GROUP_TAG_SIZE = 4
    const val HEADER_SIZE = GROUP_TAG_SIZE + 3
    const val MAX_NAME_BYTES = MAX_ADVERTISEMENT_BYTES - HEADER_SIZE

    // First bytes of SHA-256 of the shared group code. Advertisements carry
    // it as a prefix and clients filter on it, so unrelated senders (or a
    // second group in the same venue) cannot confuse the clients.
    fun deriveGroupTag(code: String): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest("pdf-sync-viewer:$code".toByteArray(Charsets.UTF_8))
            .copyOf(GROUP_TAG_SIZE)

    fun encode(groupTag: ByteArray, counter: Byte, pageIndex: Int, name: String): ByteArray {
        var nameBytes = name.toByteArray(Charsets.UTF_8)
        if (nameBytes.size > MAX_NAME_BYTES) {
            nameBytes = nameBytes.sliceArray(0 until MAX_NAME_BYTES)
        }
        val data = ByteArray(nameBytes.size + HEADER_SIZE)
        System.arraycopy(groupTag, 0, data, 0, GROUP_TAG_SIZE)
        data[GROUP_TAG_SIZE] = counter
        data[GROUP_TAG_SIZE + 1] = (pageIndex shr 8 and 0xFF).toByte()
        data[GROUP_TAG_SIZE + 2] = (pageIndex and 0xFF).toByte()
        System.arraycopy(nameBytes, 0, data, HEADER_SIZE, nameBytes.size)
        return data
    }

    data class Update(val name: String, val pageIndex: Int, val counter: Byte)

    /** Returns null if the payload is too short or carries another group's tag. */
    fun decode(groupTag: ByteArray, data: ByteArray): Update? {
        if (data.size < HEADER_SIZE) return null
        for (i in 0 until GROUP_TAG_SIZE) {
            if (data[i] != groupTag[i]) return null
        }
        val counter = data[GROUP_TAG_SIZE]
        val pageIndex = ((data[GROUP_TAG_SIZE + 1].toInt() and 0xFF) shl 8) or
                (data[GROUP_TAG_SIZE + 2].toInt() and 0xFF)
        val name = String(data, HEADER_SIZE, data.size - HEADER_SIZE, Charsets.UTF_8)
            .trim { it <= ' ' || it == '\u0000' }
        return Update(name, pageIndex, counter)
    }

    /** Advertised names may be truncated: match local files by prefix. */
    fun matchesAdvertisedName(fileName: String, advertisedName: String): Boolean =
        fileName.startsWith(advertisedName, ignoreCase = true)

    /**
     * MIDI bank/program (0-based wire values) to the file base name, using
     * the 1-based numbers as displayed in SongBook: bank 13 + program 19
     * selects "14_20".
     */
    fun midiTargetName(bank: Int, program: Int): String = "${bank + 1}_${program + 1}"
}
