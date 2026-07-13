package com.github.blebrowserbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncProtocolTest {

    private val tag = SyncProtocol.deriveGroupTag("bandA")

    @Test
    fun roundTripCarriesNamePageAndCounter() {
        val data = SyncProtocol.encode(tag, 7, 2, "march.pdf")
        val update = SyncProtocol.decode(tag, data)
        assertEquals(SyncProtocol.Update("march.pdf", 2, 7), update)
    }

    @Test
    fun longNamesAreTruncatedToThePayloadLimit() {
        val data = SyncProtocol.encode(tag, 1, 4, "symphony_no_9_finale.pdf")
        assertEquals(SyncProtocol.MAX_ADVERTISEMENT_BYTES, data.size)
        assertEquals("symphony_no_9", SyncProtocol.decode(tag, data)?.name)
    }

    @Test
    fun wrongGroupTagIsRejected() {
        val data = SyncProtocol.encode(tag, 1, 0, "secret.pdf")
        assertNull(SyncProtocol.decode(SyncProtocol.deriveGroupTag("bandB"), data))
    }

    @Test
    fun tooShortPayloadIsRejected() {
        assertNull(SyncProtocol.decode(tag, ByteArray(SyncProtocol.HEADER_SIZE - 1)))
    }

    @Test
    fun pageIndexSurvivesBothBytes() {
        for (page in intArrayOf(0, 1, 255, 256, 300, 65535)) {
            val data = SyncProtocol.encode(tag, 1, page, "a.pdf")
            assertEquals(page, SyncProtocol.decode(tag, data)?.pageIndex)
        }
    }

    @Test
    fun groupTagIsDeterministicAndDistinguishesCodes() {
        assertTrue(SyncProtocol.deriveGroupTag("x").contentEquals(SyncProtocol.deriveGroupTag("x")))
        assertEquals(SyncProtocol.GROUP_TAG_SIZE, SyncProtocol.deriveGroupTag("").size)
        assertFalse(SyncProtocol.deriveGroupTag("").contentEquals(SyncProtocol.deriveGroupTag("bandA")))
    }

    @Test
    fun emptyGroupCodeStillTagsThePayload() {
        // Even without a user-set code the tag must be present, so arbitrary
        // BLE advertisements from other apps are not mistaken for updates
        val noCode = SyncProtocol.deriveGroupTag("")
        assertNotEquals(0, noCode.count { it != 0.toByte() })
    }

    @Test
    fun truncatedAdvertisedNameMatchesTheFullFileName() {
        assertTrue(SyncProtocol.matchesAdvertisedName("symphony_no_9_finale.pdf", "symphony_no_9"))
        assertTrue(SyncProtocol.matchesAdvertisedName("March.PDF", "march.pdf"))
        assertFalse(SyncProtocol.matchesAdvertisedName("waltz.pdf", "march.pdf"))
    }

    @Test
    fun midiBankAndProgramMapToOneBasedFileName() {
        assertEquals("14_20", SyncProtocol.midiTargetName(13, 19))
        assertEquals("1_1", SyncProtocol.midiTargetName(0, 0))
    }
}
