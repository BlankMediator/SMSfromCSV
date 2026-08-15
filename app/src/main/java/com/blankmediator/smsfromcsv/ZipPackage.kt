package com.blankmediator.smsfromcsv

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/** Represents either a normal CSV import or a ZIP-backed CSV + image package. */
data class RecipientPackage(
    val table: CsvTable,
    val sourceLabel: String,
    val rootDir: File?,
    val filesByPath: Map<String, File>
) {
    val isZip: Boolean get() = rootDir != null

    fun resolveImage(csvValue: String): File? {
        val normalized = ZipPackageImporter.normalizeRelativePath(csvValue) ?: return null
        if (normalized.isBlank()) return null

        filesByPath[normalized.lowercase(Locale.ROOT)]?.let { return it }
        if (!normalized.contains('/')) {
            filesByPath["images/$normalized".lowercase(Locale.ROOT)]?.let { return it }
        }
        return null
    }
}

object ZipPackageImporter {
    private const val MAX_CSV_BYTES = 5 * 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 1000
    private const val MAX_ENTRY_BYTES = 25L * 1024L * 1024L
    private const val MAX_TOTAL_UNCOMPRESSED_BYTES = 120L * 1024L * 1024L

    fun import(
        displayName: String,
        input: InputStream,
        cacheRoot: File
    ): RecipientPackage {
        return if (displayName.lowercase(Locale.ROOT).endsWith(".zip")) {
            importZip(displayName, input, cacheRoot)
        } else {
            importCsv(displayName, input)
        }
    }

    fun importCsv(displayName: String, input: InputStream): RecipientPackage {
        val csv = readUtf8Limited(input, MAX_CSV_BYTES)
        return RecipientPackage(
            table = CsvParser.parse(csv),
            sourceLabel = displayName,
            rootDir = null,
            filesByPath = emptyMap()
        )
    }

    fun importZip(displayName: String, input: InputStream, cacheRoot: File): RecipientPackage {
        cacheRoot.mkdirs()
        val packageDir = File(cacheRoot, "pkg-${UUID.randomUUID()}")
        require(packageDir.mkdirs()) { "Could not create a temporary package directory." }
        val canonicalRoot = packageDir.canonicalFile

        val files = linkedMapOf<String, File>()
        var csvFile: File? = null
        var csvPath: String? = null
        var totalBytes = 0L
        var entryCount = 0

        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    require(entryCount <= MAX_ZIP_ENTRIES) {
                        "ZIP has more than $MAX_ZIP_ENTRIES entries."
                    }

                    val path = normalizeRelativePath(entry.name)
                        ?: throw IllegalArgumentException("Unsafe ZIP path: '${entry.name}'.")
                    if (path.isBlank()) {
                        zip.closeEntry()
                        continue
                    }

                    val output = File(canonicalRoot, path).canonicalFile
                    require(output.path.startsWith(canonicalRoot.path + File.separator)) {
                        "Unsafe ZIP path: '${entry.name}'."
                    }

                    if (entry.isDirectory) {
                        require(output.mkdirs() || output.isDirectory) {
                            "Could not create ZIP directory '$path'."
                        }
                        zip.closeEntry()
                        continue
                    }

                    val parent = requireNotNull(output.parentFile)
                    require(parent.mkdirs() || parent.isDirectory) {
                        "Could not create the directory for '$path'."
                    }

                    val lower = path.lowercase(Locale.ROOT)
                    require(!files.containsKey(lower)) {
                        "ZIP contains duplicate paths ignoring case: '$path'."
                    }

                    var entryBytes = 0L
                    FileOutputStream(output).use { destination ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            entryBytes += read
                            totalBytes += read
                            require(entryBytes <= MAX_ENTRY_BYTES) {
                                "ZIP entry '$path' is larger than ${MAX_ENTRY_BYTES / (1024 * 1024)} MB."
                            }
                            require(totalBytes <= MAX_TOTAL_UNCOMPRESSED_BYTES) {
                                "ZIP expands beyond ${MAX_TOTAL_UNCOMPRESSED_BYTES / (1024 * 1024)} MB."
                            }
                            destination.write(buffer, 0, read)
                        }
                    }
                    files[lower] = output

                    if (lower.endsWith(".csv")) {
                        val isPreferred = lower == "recipients.csv"
                        if (csvFile == null || isPreferred) {
                            csvFile = output
                            csvPath = path
                        } else if (csvPath?.lowercase(Locale.ROOT) != "recipients.csv") {
                            throw IllegalArgumentException(
                                "ZIP contains multiple CSV files. Name the intended one 'recipients.csv'."
                            )
                        }
                    }
                    zip.closeEntry()
                }
            }

            val selectedCsv = csvFile
                ?: throw IllegalArgumentException("ZIP must contain recipients.csv (or exactly one .csv file).")
            require(selectedCsv.length() <= MAX_CSV_BYTES) {
                "CSV inside ZIP is larger than ${MAX_CSV_BYTES / (1024 * 1024)} MB."
            }
            val csv = selectedCsv.readText(StandardCharsets.UTF_8)
            val table = CsvParser.parse(csv)

            return RecipientPackage(
                table = table,
                sourceLabel = "$displayName → ${csvPath ?: selectedCsv.name}",
                rootDir = packageDir,
                filesByPath = files
            )
        } catch (t: Throwable) {
            packageDir.deleteRecursively()
            throw t
        }
    }

    internal fun normalizeRelativePath(value: String): String? {
        val cleaned = value.trim().replace('\\', '/')
        if (cleaned.isBlank()) return ""
        if (cleaned.startsWith('/') || Regex("^[A-Za-z]:/").containsMatchIn(cleaned)) return null
        val parts = cleaned.split('/').filter(String::isNotEmpty)
        if (parts.any { it == "." || it == ".." }) return null
        if (parts.any { it.contains('\u0000') }) return null
        return parts.joinToString("/")
    }

    private fun readUtf8Limited(input: InputStream, maxBytes: Int): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= maxBytes) { "CSV is larger than ${maxBytes / (1024 * 1024)} MB." }
            out.write(buffer, 0, read)
        }
        return out.toString(StandardCharsets.UTF_8.name())
    }
}
