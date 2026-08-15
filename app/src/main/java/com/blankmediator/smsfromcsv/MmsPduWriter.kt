package com.blankmediator.smsfromcsv

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Minimal MMS M-Send.req composer for one recipient with text + one JPEG image.
 *
 * The encoding mirrors Android/AOSP's PduComposer for the fields used here.
 * It intentionally supports only the subset SMSfromCSV needs so the app does
 * not depend on hidden framework APIs or a legacy MMS library.
 */
object MmsPduWriter {
    private const val HEADER_CONTENT_TYPE = 0x84
    private const val HEADER_FROM = 0x89
    private const val HEADER_MESSAGE_TYPE = 0x8C
    private const val HEADER_MMS_VERSION = 0x8D
    private const val HEADER_TO = 0x97
    private const val HEADER_TRANSACTION_ID = 0x98

    private const val MESSAGE_TYPE_SEND_REQ = 0x80
    private const val MMS_VERSION_1_2 = 0x12
    private const val FROM_INSERT_ADDRESS_TOKEN = 0x81

    private const val PARAM_CHARSET = 0x81
    private const val PARAM_NAME = 0x85
    private const val PARAM_RELATED_TYPE = 0x89
    private const val PARAM_RELATED_START = 0x8A
    private const val PART_CONTENT_LOCATION = 0x8E
    private const val PART_CONTENT_ID = 0xC0

    private const val CT_TEXT_PLAIN = 0x03
    private const val CT_IMAGE_JPEG = 0x1E
    private const val CT_MULTIPART_RELATED = 0x33
    private const val UTF8_MIB_ENUM = 106

    data class ImagePart(
        val bytes: ByteArray,
        val fileName: String = "image.jpg"
    )

    fun compose(
        recipient: String,
        text: String,
        image: ImagePart,
        transactionId: String = "T${System.currentTimeMillis().toString(16)}"
    ): ByteArray {
        require(recipient.isNotBlank()) { "MMS recipient is blank." }
        require(text.isNotBlank()) { "MMS text is blank." }
        require(image.bytes.isNotEmpty()) { "MMS image is empty." }

        val safeImageName = sanitizeFileName(image.fileName).ifBlank { "image.jpg" }
        val out = WspWriter()

        out.octet(HEADER_MESSAGE_TYPE)
        out.octet(MESSAGE_TYPE_SEND_REQ)

        out.octet(HEADER_TRANSACTION_ID)
        out.textString(transactionId)

        out.octet(HEADER_MMS_VERSION)
        out.shortInteger(MMS_VERSION_1_2)

        // From: insert-address-token means the MMSC/network inserts this handset's address.
        out.octet(HEADER_FROM)
        out.octet(1)
        out.octet(FROM_INSERT_ADDRESS_TOKEN)

        out.octet(HEADER_TO)
        out.encodedString("${recipient}/TYPE=PLMN", UTF8_MIB_ENUM)

        out.octet(HEADER_CONTENT_TYPE)

        val smilName = "smil.xml"
        val textName = "text.txt"
        val smil = buildSmil(textName, safeImageName).toByteArray(StandardCharsets.UTF_8)
        val textBytes = text.toByteArray(StandardCharsets.UTF_8)

        val parts = listOf(
            Part(
                contentType = ContentType.Text("application/smil"),
                name = smilName,
                contentId = "smil",
                contentLocation = smilName,
                charset = UTF8_MIB_ENUM,
                data = smil
            ),
            Part(
                contentType = ContentType.WellKnown(CT_TEXT_PLAIN),
                name = textName,
                contentId = "text",
                contentLocation = textName,
                charset = UTF8_MIB_ENUM,
                data = textBytes
            ),
            Part(
                contentType = ContentType.WellKnown(CT_IMAGE_JPEG),
                name = safeImageName,
                contentId = "image",
                contentLocation = safeImageName,
                charset = null,
                data = image.bytes
            )
        )

        val topContentType = WspWriter().apply {
            shortInteger(CT_MULTIPART_RELATED)
            octet(PARAM_RELATED_START)
            textString("<smil>")
            octet(PARAM_RELATED_TYPE)
            textString("application/smil")
        }.bytes()
        out.valueLength(topContentType.size)
        out.raw(topContentType)

        out.uintvar(parts.size.toLong())
        for (part in parts) {
            val header = buildPartHeader(part)
            out.uintvar(header.size.toLong())
            out.uintvar(part.data.size.toLong())
            out.raw(header)
            out.raw(part.data)
        }

        return out.bytes()
    }

    private fun buildPartHeader(part: Part): ByteArray {
        val out = WspWriter()
        val contentTypeValue = WspWriter().apply {
            when (val type = part.contentType) {
                is ContentType.WellKnown -> shortInteger(type.code)
                is ContentType.Text -> textString(type.value)
            }
            octet(PARAM_NAME)
            textString(part.name)
            part.charset?.let {
                octet(PARAM_CHARSET)
                shortInteger(it)
            }
        }.bytes()

        out.valueLength(contentTypeValue.size)
        out.raw(contentTypeValue)

        out.octet(PART_CONTENT_ID)
        out.quotedString("<${part.contentId}>")

        out.octet(PART_CONTENT_LOCATION)
        out.textString(part.contentLocation)
        return out.bytes()
    }

    private fun buildSmil(textName: String, imageName: String): String {
        val escapedTextName = xmlEscape(textName)
        val escapedImageName = xmlEscape(imageName)
        return """<smil><head><layout><root-layout/><region id="Image"/><region id="Text"/></layout></head><body><par dur="5000ms"><img src="$escapedImageName" region="Image"/><text src="$escapedTextName" region="Text"/></par></body></smil>"""
    }

    private fun xmlEscape(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            append(
                when (ch) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&apos;"
                    else -> ch.toString()
                }
            )
        }
    }

    private fun sanitizeFileName(value: String): String {
        return value.substringAfterLast('/').substringAfterLast('\\')
            .map { ch -> if (ch.isLetterOrDigit() || ch in "._-") ch else '_' }
            .joinToString("")
            .take(80)
    }

    private sealed interface ContentType {
        data class WellKnown(val code: Int) : ContentType
        data class Text(val value: String) : ContentType
    }

    private data class Part(
        val contentType: ContentType,
        val name: String,
        val contentId: String,
        val contentLocation: String,
        val charset: Int?,
        val data: ByteArray
    )

    private class WspWriter {
        private val out = ByteArrayOutputStream()

        fun octet(value: Int) {
            out.write(value and 0xFF)
        }

        fun raw(bytes: ByteArray) {
            out.write(bytes)
        }

        fun shortInteger(value: Int) {
            octet((value and 0x7F) or 0x80)
        }

        fun textString(value: String) {
            textString(value.toByteArray(StandardCharsets.UTF_8))
        }

        fun textString(value: ByteArray) {
            require(value.isNotEmpty()) { "WSP text-string cannot be empty here." }
            if ((value[0].toInt() and 0xFF) > 0x7F) octet(0x7F)
            raw(value)
            octet(0)
        }

        fun quotedString(value: String) {
            octet(0x22)
            raw(value.toByteArray(StandardCharsets.UTF_8))
            octet(0)
        }

        fun encodedString(value: String, charset: Int) {
            val inner = WspWriter().apply {
                shortInteger(charset)
                textString(value)
            }.bytes()
            valueLength(inner.size)
            raw(inner)
        }

        fun valueLength(value: Int) {
            require(value >= 0)
            if (value < 31) {
                octet(value)
            } else {
                octet(31)
                uintvar(value.toLong())
            }
        }

        fun uintvar(value: Long) {
            require(value >= 0)
            var shift = 0
            var remaining = value
            while (remaining > 0x7F) {
                remaining = remaining ushr 7
                shift += 7
            }
            while (shift > 0) {
                val fragment = (value ushr shift) and 0x7F
                octet(fragment.toInt() or 0x80)
                shift -= 7
            }
            octet((value and 0x7F).toInt())
        }

        fun bytes(): ByteArray = out.toByteArray()
    }
}
