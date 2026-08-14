package com.example.weddingsmssender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CsvParserTest {
    @Test
    fun parsesQuotedCommasEscapedQuotesAndCrLf() {
        val table = CsvParser.parse(
            "name,phone,notes\r\n" +
                "\"Smith, Jane\",\"+61400000001\",\"She said \"\"yes\"\"\"\r\n"
        )

        assertEquals(listOf("name", "phone", "notes"), table.headers)
        assertEquals("Smith, Jane", table.rows.single()["name"])
        assertEquals("She said \"yes\"", table.rows.single()["notes"])
    }

    @Test
    fun parsesQuotedMultilineFieldsAndLf() {
        val table = CsvParser.parse(
            "name,phone,message\n" +
                "Jane,+61400000001,\"First line\nSecond line, with comma\"\n"
        )

        assertEquals("First line\nSecond line, with comma", table.rows.single()["message"])
    }

    @Test
    fun normalisesHeadersAndRemovesUtf8Bom() {
        val table = CsvParser.parse("\uFEFFParty Size,Phone Number\n2,+61400000001")

        assertEquals(listOf("party_size", "phone_number"), table.headers)
    }

    @Test
    fun removesUtf8BomBeforeAQuotedHeader() {
        val table = CsvParser.parse(
            "\uFEFF\"name\",\"phone\"\r\n\"Jane\",\"+61400000001\""
        )

        assertEquals(listOf("name", "phone"), table.headers)
        assertEquals("Jane", table.rows.single()["name"])
    }

    @Test
    fun rejectsExtraDataFields() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            CsvParser.parse("phone,name\n+61400000001,Jane,unexpected")
        }

        assertEquals(
            "CSV data record 2 has 3 fields but the header has 2.",
            exception.message
        )
    }
}
