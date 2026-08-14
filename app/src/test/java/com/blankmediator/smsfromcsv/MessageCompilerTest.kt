package com.blankmediator.smsfromcsv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageCompilerTest {
    @Test
    fun blocksCsvWithoutPhoneColumn() {
        val table = CsvParser.parse("name\nJane")

        val result = MessageCompiler.compile(table, MessageMode.SAME, "Hello")

        assertTrue(result.messages.isEmpty())
        assertEquals(listOf("CSV must have a 'phone' column."), result.errors)
    }

    @Test
    fun rejectsInvalidPhoneWithoutInventingCountryCode() {
        val table = CsvParser.parse("phone\n61400000001")

        val result = MessageCompiler.compile(table, MessageMode.SAME, "Hello")

        assertTrue(result.messages.isEmpty())
        assertTrue(result.errors.single().contains("invalid phone"))
    }

    @Test
    fun acceptsAustralianLocalMobileAndInternationalNumbers() {
        val table = CsvParser.parse("phone\n\"0412 345 678\"\n\"+61 400 000 001\"")

        val result = MessageCompiler.compile(table, MessageMode.SAME, "Hello")

        assertTrue(result.errors.isEmpty())
        assertEquals(listOf("0412345678", "+61400000001"), result.messages.map { it.phone })
    }

    @Test
    fun rejectsAustralianInternationalNumberWithLocalTrunkPrefix() {
        val table = CsvParser.parse("phone\n+610412345678")

        val result = MessageCompiler.compile(table, MessageMode.SAME, "Hello")

        assertTrue(result.messages.isEmpty())
        assertTrue(result.errors.single().contains("invalid phone"))
    }

    @Test
    fun blocksBlankMessage() {
        val table = CsvParser.parse("phone\n+61400000001")

        val result = MessageCompiler.compile(table, MessageMode.SAME, "   ")

        assertEquals(listOf("Message text is blank."), result.errors)
    }

    @Test
    fun suggestsPerRowModeForCompleteImportedMessageColumn() {
        val table = CsvParser.parse(
            "phone,message\n" +
                "+61400000001,First message\n" +
                "+61400000002,Second message"
        )

        val suggested = MessageCompiler.suggestedModeAfterImport(
            table,
            currentMode = MessageMode.SAME,
            editorText = ""
        )

        assertEquals(MessageMode.PER_ROW, suggested)
    }

    @Test
    fun doesNotOverrideAnExplicitModeOrIncompleteMessageColumn() {
        val complete = CsvParser.parse("phone,message\n+61400000001,Per-row message")
        val incomplete = CsvParser.parse("phone,message\n+61400000001,")

        assertEquals(
            MessageMode.TEMPLATE,
            MessageCompiler.suggestedModeAfterImport(complete, MessageMode.TEMPLATE, "Hi {name}")
        )
        assertEquals(
            MessageMode.SAME,
            MessageCompiler.suggestedModeAfterImport(incomplete, MessageMode.SAME, "")
        )
    }

    @Test
    fun blocksUnresolvedPlaceholder() {
        val table = CsvParser.parse("name,phone\nJane,+61400000001")

        val result = MessageCompiler.compile(table, MessageMode.TEMPLATE, "Hi {name}, use {link}")

        assertTrue(result.messages.isEmpty())
        assertTrue(result.errors.single().contains("{link}"))
    }

    @Test
    fun warnsAboutDuplicateNormalisedPhoneNumbers() {
        val table = CsvParser.parse("phone\n\"+61 400 000 001\"\n+61400000001")

        val result = MessageCompiler.compile(table, MessageMode.SAME, "Hello")

        assertEquals(2, result.messages.size)
        assertTrue(result.warnings.single().contains("occur more than once"))
    }

    @Test
    fun rendersNormalisedPlaceholdersInPerRowMessages() {
        val table = CsvParser.parse(
            "Name,Phone,Party Size,message\n" +
                "Jane,+61400000001,2,\"Hi {name}, seats: {Party Size}\""
        )

        val result = MessageCompiler.compile(table, MessageMode.PER_ROW, "ignored")

        assertTrue(result.errors.isEmpty())
        assertEquals("Hi Jane, seats: 2", result.messages.single().text)
    }

    @Test
    fun sameModeLeavesBracesUntouched() {
        val table = CsvParser.parse("phone\n+61400000001")

        val result = MessageCompiler.compile(table, MessageMode.SAME, "Meet at {Gate 2}")

        assertTrue(result.errors.isEmpty())
        assertEquals("Meet at {Gate 2}", result.messages.single().text)
        assertFalse(result.warnings.isNotEmpty())
    }
}
