package com.blankmediator.smsfromcsv

import java.util.Locale

enum class MessageMode {
    SAME,
    TEMPLATE,
    PER_ROW
}

enum class SimRoutingMode {
    DEFAULT,
    FIXED,
    PER_ROW
}

data class CsvTable(
    val headers: List<String>,
    val rows: List<Map<String, String>>
)

data class Outgoing(
    val rowNumber: Int,
    val phone: String,
    val text: String,
    val subscriptionId: Int? = null,
    val simLabel: String = "Android default SMS SIM"
)

data class Compilation(
    val messages: List<Outgoing>,
    val errors: List<String>,
    val warnings: List<String>
)

data class SimTarget(
    val subscriptionId: Int,
    val slotNumber: Int,
    val displayName: String,
    val carrierName: String,
    val phoneNumber: String
) {
    fun label(): String = buildString {
        append("SIM $slotNumber")
        val names = listOf(displayName, carrierName)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        if (names.isNotEmpty()) append(" — ${names.joinToString(" / ")}")
        if (phoneNumber.isNotBlank()) append(" — $phoneNumber")
        append(" (subscription $subscriptionId)")
    }
}

object SimRouter {
    fun route(
        table: CsvTable,
        compilation: Compilation,
        mode: SimRoutingMode,
        fixedSubscriptionId: Int?,
        activeSims: List<SimTarget>
    ): Compilation {
        val routingErrors = mutableListOf<String>()
        val routedMessages = when (mode) {
            SimRoutingMode.DEFAULT -> compilation.messages.map { message ->
                message.copy(subscriptionId = null, simLabel = "Android default SMS SIM")
            }

            SimRoutingMode.FIXED -> {
                val selected = activeSims.singleOrNull { it.subscriptionId == fixedSubscriptionId }
                if (selected == null) {
                    routingErrors += "Select an active SIM for this batch."
                    emptyList()
                } else {
                    compilation.messages.map { message ->
                        message.copy(
                            subscriptionId = selected.subscriptionId,
                            simLabel = selected.label()
                        )
                    }
                }
            }

            SimRoutingMode.PER_ROW -> routePerRow(table, compilation.messages, activeSims, routingErrors)
        }

        return Compilation(
            messages = routedMessages,
            errors = (compilation.errors + routingErrors).distinct(),
            warnings = compilation.warnings
        )
    }

    private fun routePerRow(
        table: CsvTable,
        messages: List<Outgoing>,
        activeSims: List<SimTarget>,
        errors: MutableList<String>
    ): List<Outgoing> {
        if (!table.headers.contains("sim")) {
            errors += "Per-row SIM routing requires a CSV column named 'sim'."
            return emptyList()
        }
        if (activeSims.isEmpty()) {
            errors += "Load at least one active SIM before using the CSV 'sim' column."
            return emptyList()
        }

        return messages.mapNotNull { message ->
            val selector = table.rows.getOrNull(message.rowNumber - 2)?.get("sim").orEmpty().trim()
            if (selector.isEmpty()) {
                errors += "CSV row ${message.rowNumber} has a blank 'sim' value."
                return@mapNotNull null
            }

            when (val match = resolve(selector, activeSims)) {
                is SimMatch.Found -> message.copy(
                    subscriptionId = match.sim.subscriptionId,
                    simLabel = match.sim.label()
                )
                SimMatch.Ambiguous -> {
                    errors += "CSV row ${message.rowNumber} has an ambiguous 'sim' value: '$selector'. Use SIM1/SIM2 or sub:<id>."
                    null
                }
                SimMatch.NotFound -> {
                    errors += "CSV row ${message.rowNumber} cannot match 'sim' value '$selector' to an active SIM."
                    null
                }
            }
        }
    }

    private fun resolve(selector: String, activeSims: List<SimTarget>): SimMatch {
        val trimmed = selector.trim()
        val folded = trimmed.lowercase(Locale.ROOT)
        val compact = folded.filterNot { it == ' ' || it == '_' || it == '-' }

        parsePrefixedSubscriptionId(compact)?.let { subscriptionId ->
            return activeSims.singleOrNull { it.subscriptionId == subscriptionId }
                ?.let(SimMatch::Found) ?: SimMatch.NotFound
        }

        parseSlotNumber(compact)?.let { slotNumber ->
            return singleMatch(activeSims.filter { it.slotNumber == slotNumber })
        }

        val selectorPhone = canonicalPhoneNumber(trimmed)
        if (selectorPhone.isNotEmpty()) {
            val phoneMatches = activeSims.filter { sim ->
                val candidate = canonicalPhoneNumber(sim.phoneNumber)
                candidate.isNotEmpty() && candidate == selectorPhone
            }
            if (phoneMatches.isNotEmpty()) return singleMatch(phoneMatches)
        }

        val labelMatches = activeSims.filter { sim ->
            folded == sim.displayName.trim().lowercase(Locale.ROOT) ||
                folded == sim.carrierName.trim().lowercase(Locale.ROOT) ||
                folded == sim.label().lowercase(Locale.ROOT)
        }
        return singleMatch(labelMatches)
    }

    private fun parsePrefixedSubscriptionId(compact: String): Int? {
        val prefixes = listOf("sub:", "subscription:", "subid:")
        val prefix = prefixes.firstOrNull(compact::startsWith) ?: return null
        return compact.removePrefix(prefix).toIntOrNull()
    }

    private fun parseSlotNumber(compact: String): Int? {
        val digits = when {
            compact.startsWith("sim") -> compact.removePrefix("sim")
            compact.startsWith("slot") -> compact.removePrefix("slot")
            compact.all(Char::isDigit) -> compact
            else -> return null
        }
        return digits.toIntOrNull()?.takeIf { it > 0 }
    }

    private fun canonicalPhoneNumber(value: String): String {
        return value.trim().filter(Char::isDigit)
    }

    private fun singleMatch(matches: List<SimTarget>): SimMatch = when (matches.size) {
        0 -> SimMatch.NotFound
        1 -> SimMatch.Found(matches.single())
        else -> SimMatch.Ambiguous
    }

    private sealed interface SimMatch {
        data class Found(val sim: SimTarget) : SimMatch
        data object Ambiguous : SimMatch
        data object NotFound : SimMatch
    }
}

object MessageCompiler {
    fun suggestedModeAfterImport(
        table: CsvTable,
        currentMode: MessageMode,
        editorText: String
    ): MessageMode {
        val hasCompletePerRowMessages = table.headers.contains("message") &&
            table.rows.isNotEmpty() && table.rows.all { row -> row["message"].orEmpty().isNotBlank() }
        return if (currentMode == MessageMode.SAME && editorText.isBlank() && hasCompletePerRowMessages) {
            MessageMode.PER_ROW
        } else {
            currentMode
        }
    }

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
        if (errors.isNotEmpty()) {
            return Compilation(emptyList(), errors.distinct(), warnings)
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
