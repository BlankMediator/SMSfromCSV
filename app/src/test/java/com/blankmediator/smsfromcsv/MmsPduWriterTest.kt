package com.blankmediator.smsfromcsv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MmsPduWriterTest {
    @Test
    fun composeBuildsSendRequestWithTextSmilAndJpeg() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        val pdu = MmsPduWriter.compose(
            recipient = "+61400000001",
            text = "Hello Jane",
            image = MmsPduWriter.ImagePart(jpeg, "invite.jpg"),
            transactionId = "test-1"
        )

        assertTrue(pdu.size > jpeg.size)
        assertTrue(pdu.containsAscii("test-1"))
        assertTrue(pdu.containsAscii("+61400000001/TYPE=PLMN"))
        assertTrue(pdu.containsAscii("Hello Jane"))
        assertTrue(pdu.containsAscii("application/smil"))
        assertTrue(pdu.containsAscii("invite.jpg"))
        assertTrue(pdu.containsSubsequence(jpeg))

        // X-Mms-Message-Type followed by m-send-req.
        assertArrayEquals(byteArrayOf(0x8C.toByte(), 0x80.toByte()), pdu.copyOfRange(0, 2))
    }

    private fun ByteArray.containsAscii(value: String): Boolean =
        containsSubsequence(value.toByteArray(Charsets.UTF_8))

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        for (start in 0..size - needle.size) {
            var match = true
            for (index in needle.indices) {
                if (this[start + index] != needle[index]) {
                    match = false
                    break
                }
            }
            if (match) return true
        }
        return false
    }
}
