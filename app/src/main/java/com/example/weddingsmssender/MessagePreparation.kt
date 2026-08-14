package com.example.weddingsmssender

import java.util.Locale

enum class MessageMode {
    SAME,
    TEMPLATE,
    PER_ROW
}

data class CsvTable(
    val headers: List<String>,
    val rows: List<Map<String, String>>
)

data class Outgoing(
    val rowNumber: Int,
    val phone: String,
    val text: String
)

data class Compilation(
    val messages: List<Outgoing>,
    val errors: List<String>,
    val warnings: List<String>
)

object MessageCompiler {
    fun compile(table: CsvTable, mode: MessageMode, editorText: String): Compilation {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val messages = mutableListOf<Outgoing>()

        if (!table.headers.contains("phone")) {
            return Compilation(emptyList(), listOf("CSV must have a 'phone' column."), emptyList())
        }
        if (table.rows.isEmpty()) {
            return Compilation(emptyList(), listOf("CSV contains no data rows."), emptyList())
        }
        if ((mode == MessageMode.SAME || mode == MessageMode.TEMPLATE) && editorText.isBlank()) {
            errors += "Message text is blank."
        }
        if (mode == MessageMode.PER_ROW && !table.headers.contains("message")) {
            errors += "Per-row mode requires a CSV column named 'message'."
        }

        table.rows.forEachIndexed { index, row ->
            val csvRowNumber = index + 2
            val rawPhone = row["phone"].orEmpty()
            val phone = normalizePhone(rawPhone)
            if (!isValidPhone(phone)) {
                errors += "CSV row $csvRowNumber has an invalid phone value: '$rawPhone'."
                return@forEachIndexed
            }

            val message = when (mode) {
                MessageMode.SAME -> editorText
                MessageMode.TEMPLATE -> applyTemplate(editorText, row)
                MessageMode.PER_ROW -> applyTemplate(row["message"].orEmpty(), row)
            }

            if (message.isBlank()) {
                errors += "CSV row $csvRowNumber produces a blank message."
                return@forEachIndexed
            }

            if (mode != MessageMode.SAME) {
                val unresolved = findPlaceholders(message)
                if (unresolved.isNotEmpty()) {
                    errors += "CSV row $csvRowNumber contains unresolved placeholder(s): ${unresolved.joinToString()}."
                    return@forEachIndexed
                }
            }

            messages += Outgoing(csvRowNumber, phone, message)
        }

        val duplicatePhones = messages.groupBy { it.phone }.filterValues { it.size > 1 }
        if (duplicatePhones.isNotEmpty()) {
            warnings += "${duplicatePhones.size} phone number(s) occur more than once and will receive more than one SMS."
        }

        val rowsWithRequiredBlankLinks = when (mode) {
            MessageMode.SAME -> 0
            MessageMode.TEMPLATE -> if (templateUsesKey(editorText, "link")) {
                table.rows.count { it["link"].isNullOrBlank() }
            } else {
                0
            }
            MessageMode.PER_ROW -> table.rows.count { row ->
                templateUsesKey(row["message"].orEmpty(), "link") && row["link"].isNullOrBlank()
            }
        }
        if (rowsWithRequiredBlankLinks > 0) {
            warnings += "$rowsWithRequiredBlankLinks row(s) use {link} but have a blank link value."
        }

        return Compilation(messages, errors.distinct(), warnings.distinct())
    }

    fun applyTemplate(template: String, row: Map<String, String>): String {
        val placeholders = placeholderRanges(template)
        if (placeholders.isEmpty()) return template
        var cursor = 0
        return buildString(template.length) {
            placeholders.forEach { (range, rawKey) ->
                append(template.substring(cursor, range.first))
                append(row[CsvParser.normalizeHeader(rawKey)] ?: template.substring(range))
                cursor = range.last + 1
            }
            append(template.substring(cursor))
        }
    }

    fun normalizePhone(raw: String): String {
        return raw.trim().filterNot { character ->
            character == ' ' || character == '\t' || character == '(' ||
                character == ')' || character == '-' || character == '.'
        }
    }

    fun isValidPhone(phone: String): Boolean {
        if (phone.length == 10 && phone.startsWith("04") && phone.all(Char::isDigit)) return true
        if (phone.startsWith("+61")) {
            return phone.length == 12 && phone[3] in "23478" && phone.substring(3).all(Char::isDigit)
        }
        return phone.startsWith('+') && phone.length in 9..16 &&
            phone[1] in '1'..'9' && phone.substring(1).all(Char::isDigit)
    }

    fun duplicatePhoneCount(table: CsvTable): Int {
        return table.rows
            .map { normalizePhone(it["phone"].orEmpty()) }
            .filter(::isValidPhone)
            .groupingBy { it }
            .eachCount()
            .count { it.value > 1 }
    }

    private fun findPlaceholders(text: String): List<String> {
        return placeholderRanges(text).map { (range, _) -> text.substring(range) }.distinct()
    }

    private fun templateUsesKey(template: String, expectedKey: String): Boolean {
        return placeholderRanges(template).any { (_, rawKey) ->
            CsvParser.normalizeHeader(rawKey) == expectedKey
        }
    }

    private fun placeholderRanges(text: String): List<Pair<IntRange, String>> {
        val placeholders = mutableListOf<Pair<IntRange, String>>()
        var searchFrom = 0
        while (searchFrom < text.length) {
            val opening = text.indexOf('{', searchFrom)
            if (opening < 0) break
            val closing = text.indexOf('}', opening + 1)
            if (closing < 0) break
            val rawKey = text.substring(opening + 1, closing)
            if (rawKey.isNotEmpty() && rawKey.none { it == '{' || it == '\r' || it == '\n' }) {
                placeholders += (opening..closing) to rawKey
                searchFrom = closing + 1
            } else {
                searchFrom = opening + 1
            }
        }
        return placeholders
    }
}

object CsvParser {
    fun parse(input: String): CsvTable {
        val rawRows = parseRows(input.removePrefix("\uFEFF")).filterNot { row -> row.all { it.isBlank() } }
        require(rawRows.isNotEmpty()) { "CSV is empty." }

        val headers = rawRows.first().mapIndexed { index, header ->
            val normalized = normalizeHeader(header)
            require(normalized.isNotBlank()) { "Header column ${index + 1} is blank." }
            normalized
        }
        require(headers.distinct().size == headers.size) {
            "CSV contains duplicate header names after normalisation."
        }

        val rows = rawRows.drop(1).mapIndexed { index, values ->
            require(values.size <= headers.size) {
                "CSV data record ${index + 2} has ${values.size} fields but the header has ${headers.size}."
            }
            headers.indices.associate { columnIndex ->
                headers[columnIndex] to values.getOrElse(columnIndex) { "" }
            }
        }

        return CsvTable(headers, rows)
    }

    fun normalizeHeader(value: String): String {
        val normalized = StringBuilder()
        var pendingSeparator = false
        value.trim().lowercase(Locale.ROOT).forEach { character ->
            if (character in 'a'..'z' || character in '0'..'9') {
                if (pendingSeparator && normalized.isNotEmpty()) normalized.append('_')
                normalized.append(character)
                pendingSeparator = false
            } else {
                pendingSeparator = normalized.isNotEmpty()
            }
        }
        return normalized.toString()
    }

    private fun parseRows(input: String): List<List<String>> {
        val rows = mutableListOf<MutableList<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var justClosedQuote = false
        var fieldStarted = false
        var index = 0

        fun endField() {
            row.add(field.toString())
            field.setLength(0)
            fieldStarted = false
            justClosedQuote = false
        }

        fun endRow() {
            endField()
            rows.add(row)
            row = mutableListOf()
        }

        while (index < input.length) {
            val character = input[index]
            when {
                inQuotes -> when (character) {
                    '"' -> {
                        if (index + 1 < input.length && input[index + 1] == '"') {
                            field.append('"')
                            index++
                        } else {
                            inQuotes = false
                            justClosedQuote = true
                        }
                    }
                    else -> field.append(character)
                }

                justClosedQuote -> when (character) {
                    ',' -> endField()
                    '\n' -> endRow()
                    '\r' -> {
                        endRow()
                        if (index + 1 < input.length && input[index + 1] == '\n') index++
                    }
                    ' ', '\t' -> Unit
                    else -> throw IllegalArgumentException(
                        "Unexpected character '$character' after a closing quote near character ${index + 1}."
                    )
                }

                else -> when (character) {
                    '"' -> {
                        require(!fieldStarted) {
                            "Unexpected quote in an unquoted field near character ${index + 1}."
                        }
                        fieldStarted = true
                        inQuotes = true
                    }
                    ',' -> endField()
                    '\n' -> endRow()
                    '\r' -> {
                        endRow()
                        if (index + 1 < input.length && input[index + 1] == '\n') index++
                    }
                    else -> {
                        fieldStarted = true
                        field.append(character)
                    }
                }
            }
            index++
        }

        require(!inQuotes) { "CSV ends inside a quoted field." }
        if (fieldStarted || justClosedQuote || row.isNotEmpty()) endRow()
        return rows
    }
}
