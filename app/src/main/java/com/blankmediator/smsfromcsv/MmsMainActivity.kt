package com.blankmediator.smsfromcsv

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min

@SuppressLint("SetTextI18n")
class MmsMainActivity : Activity() {
    private companion object {
        const val PICK_PACKAGE_REQUEST = 1101
        const val SEND_SMS_PERMISSION_REQUEST = 1102
        const val SIM_READ_PERMISSION_REQUEST = 1103
        const val MAX_BATCH_SIZE = 500
        const val DEFAULT_DELAY_MS = 1000
        const val MAX_DELAY_MS = 60_000
        const val LOG_TAG = "SMSfromCSV"
    }

    private lateinit var importButton: Button
    private lateinit var modeSpinner: Spinner
    private lateinit var messageInput: EditText
    private lateinit var simModeSpinner: Spinner
    private lateinit var simPicker: Spinner
    private lateinit var simHelp: TextView
    private lateinit var refreshSimsButton: Button
    private lateinit var delayInput: EditText
    private lateinit var packageStatus: TextView
    private lateinit var modeHelp: TextView
    private lateinit var previewText: TextView
    private lateinit var previewButton: Button
    private lateinit var testButton: Button
    private lateinit var sendButton: Button
    private lateinit var sendStatus: TextView

    private var recipientPackage: RecipientPackage? = null
    private var previewReady = false
    private var pendingSend: PendingSend? = null
    private var availableSims: List<SimTarget> = emptyList()
    private var sending = false
    private var importRequestId = 0

    private val importExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val previewExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val senderExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pruneMmsCache()
        setContentView(buildUi())
        updateModeUi(MessageMode.SAME)
        updateSimRoutingUi(SimRoutingMode.DEFAULT)

        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)) {
            AlertDialog.Builder(this)
                .setTitle("SMS/MMS not supported")
                .setMessage("This device does not report Android telephony messaging support. A phone with an SMS/MMS-capable SIM is required.")
                .setPositiveButton("Close") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        }
    }

    override fun onDestroy() {
        importExecutor.shutdownNow()
        previewExecutor.shutdownNow()
        senderExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "SMSfromCSV"
            textSize = 26f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Import a CSV for SMS, or a ZIP containing recipients.csv plus images for per-row MMS. Everything stays on this phone."
            textSize = 16f
            setPadding(0, dp(8), 0, dp(12))
        })
        root.addView(TextView(this).apply {
            text = "ZIP format: recipients.csv + images/. Add an 'image' column such as images/invite.jpg. Rows with a blank image remain SMS."
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(18))
        })

        importButton = Button(this).apply {
            text = "1. Import CSV or ZIP"
            setOnClickListener { openPackagePicker() }
        }
        root.addView(importButton)

        packageStatus = TextView(this).apply {
            text = "No file loaded. Required CSV column: phone. Optional columns include message, sim, image, and template fields."
            setPadding(0, dp(8), 0, dp(18))
            setTextIsSelectable(true)
        }
        root.addView(packageStatus)

        root.addView(sectionLabel("2. Message mode"))
        modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MmsMainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    "Same message for everyone",
                    "Template using CSV placeholders",
                    "Unique message from CSV 'message' column"
                )
            )
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    updateModeUi(selectedMode())
                    invalidatePreview()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        root.addView(modeSpinner)

        modeHelp = TextView(this).apply { setPadding(0, dp(8), 0, dp(8)) }
        root.addView(modeHelp)

        messageInput = EditText(this).apply {
            minLines = 4
            maxLines = 10
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(messageInput)

        root.addView(sectionLabel("3. SIM routing"))
        simModeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MmsMainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    "Use Android default SMS/MMS SIM",
                    "Choose one SIM for this batch",
                    "Use CSV 'sim' column per recipient"
                )
            )
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val mode = selectedSimRoutingMode()
                    updateSimRoutingUi(mode)
                    invalidatePreview()
                    if (mode != SimRoutingMode.DEFAULT && !sending) ensureSimAccessAndLoad()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        root.addView(simModeSpinner)

        simPicker = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MmsMainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("No active SIMs loaded")
            )
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = invalidatePreview()
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        root.addView(simPicker)

        refreshSimsButton = Button(this).apply {
            text = "Load / refresh active SIMs"
            setOnClickListener { ensureSimAccessAndLoad() }
        }
        root.addView(refreshSimsButton)

        simHelp = TextView(this).apply {
            setPadding(0, dp(8), 0, dp(10))
            setTextIsSelectable(true)
        }
        root.addView(simHelp)

        root.addView(sectionLabel("4. Sending pace"))
        root.addView(TextView(this).apply {
            text = "Delay between recipients in milliseconds (0–$MAX_DELAY_MS). MMS carrier processing can be slower than SMS."
        })
        delayInput = EditText(this).apply {
            setText(DEFAULT_DELAY_MS.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            maxLines = 1
        }
        root.addView(delayInput)

        previewButton = Button(this).apply {
            text = "5. Preview and validate"
            setOnClickListener { previewMessages() }
        }
        root.addView(previewButton)

        previewText = TextView(this).apply {
            text = "Preview will appear here. A clean preview is required before sending."
            setTextIsSelectable(true)
            setPadding(0, dp(10), 0, dp(14))
        }
        root.addView(previewText)

        testButton = Button(this).apply {
            text = "Send test to first CSV recipient"
            isEnabled = false
            setOnClickListener { prepareSend(testOnly = true) }
        }
        root.addView(testButton)

        sendButton = Button(this).apply {
            text = "SEND ALL"
            isEnabled = false
            setOnClickListener { prepareSend(testOnly = false) }
        }
        root.addView(sendButton)

        sendStatus = TextView(this).apply {
            text = "Nothing submitted."
            setPadding(0, dp(10), 0, 0)
            setTextIsSelectable(true)
        }
        root.addView(sendStatus)

        root.addView(TextView(this).apply {
            text = "Test one recipient first. SMS/MMS charges, carrier fair-use rules, mobile-data/MMS APN requirements, and device anti-spam controls may apply. 'Submitted' means handed to Android, not delivered."
            textSize = 13f
            setPadding(0, dp(20), 0, 0)
        })

        messageInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(text: Editable?) = invalidatePreview()
        })
        return scroll
    }

    private fun sectionLabel(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 18f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(10), 0, dp(6))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun selectedMode(): MessageMode = when (modeSpinner.selectedItemPosition) {
        1 -> MessageMode.TEMPLATE
        2 -> MessageMode.PER_ROW
        else -> MessageMode.SAME
    }

    private fun selectedSimRoutingMode(): SimRoutingMode = when (simModeSpinner.selectedItemPosition) {
        1 -> SimRoutingMode.FIXED
        2 -> SimRoutingMode.PER_ROW
        else -> SimRoutingMode.DEFAULT
    }

    private fun selectedFixedSubscriptionId(): Int? = availableSims.getOrNull(simPicker.selectedItemPosition)?.subscriptionId

    private fun updateModeUi(mode: MessageMode) {
        if (!::messageInput.isInitialized || !::modeHelp.isInitialized) return
        when (mode) {
            MessageMode.SAME -> {
                messageInput.isEnabled = !sending
                messageInput.hint = "Type the exact text sent to every row."
                modeHelp.text = "Rows with an image become MMS; rows without one stay SMS."
            }
            MessageMode.TEMPLATE -> {
                messageInput.isEnabled = !sending
                messageInput.hint = "Hi {name}, here's your invitation."
                modeHelp.text = "Use normalised CSV headers in braces, such as {name}, {link}, or {reference}."
            }
            MessageMode.PER_ROW -> {
                messageInput.isEnabled = false
                messageInput.hint = "Messages come from the CSV 'message' column."
                modeHelp.text = "Each row supplies its own message. Placeholders inside each row are rendered too."
            }
        }
    }

    private fun updateSimRoutingUi(mode: SimRoutingMode) {
        if (!::simPicker.isInitialized) return
        val usesLoadedSims = mode != SimRoutingMode.DEFAULT
        simPicker.visibility = if (mode == SimRoutingMode.FIXED) View.VISIBLE else View.GONE
        refreshSimsButton.visibility = if (usesLoadedSims) View.VISIBLE else View.GONE
        simPicker.isEnabled = !sending && mode == SimRoutingMode.FIXED && availableSims.isNotEmpty()
        refreshSimsButton.isEnabled = !sending && usesLoadedSims
        simHelp.text = when (mode) {
            SimRoutingMode.DEFAULT -> "Uses Android's configured default SMS subscription for both SMS and MMS."
            SimRoutingMode.FIXED -> if (availableSims.isEmpty()) "Load active SIMs, then choose one for the batch." else "Choose the source SIM above."
            SimRoutingMode.PER_ROW -> if (availableSims.isEmpty()) "Load active SIMs before validating the CSV 'sim' column." else "CSV 'sim' accepts SIM1/SIM2, slot aliases, sub:<id>, a unique SIM label/carrier, or an exposed line number."
        }
    }

    private fun openPackagePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("text/csv", "text/comma-separated-values", "application/zip", "application/x-zip-compressed", "application/octet-stream")
            )
        }
        startActivityForResult(intent, PICK_PACKAGE_REQUEST)
    }

    @Deprecated("Deprecated in Android API; retained because minSdk includes pre-ActivityResult APIs.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_PACKAGE_REQUEST || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val displayName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "import"
        val requestId = ++importRequestId
        packageStatus.text = "Importing $displayName…"
        invalidatePreview()

        importExecutor.submit {
            try {
                val imported = contentResolver.openInputStream(uri)?.use { stream ->
                    ZipPackageImporter.import(displayName, stream, File(cacheDir, "imports"))
                } ?: error("Could not open the selected file.")
                postToActiveUi {
                    if (requestId != importRequestId) {
                        imported.rootDir?.deleteRecursively()
                        return@postToActiveUi
                    }
                    showImportedPackage(imported)
                }
            } catch (t: Throwable) {
                Log.e(LOG_TAG, "Import failed", t)
                postToActiveUi {
                    if (requestId == importRequestId) showImportError(t)
                }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    private fun showImportedPackage(imported: RecipientPackage) {
        recipientPackage?.rootDir?.deleteRecursively()
        recipientPackage = imported
        val table = imported.table
        val previousMode = selectedMode()
        val suggestedMode = MessageCompiler.suggestedModeAfterImport(table, previousMode, messageInput.text.toString())
        val autoPerRow = suggestedMode != previousMode
        if (autoPerRow) modeSpinner.setSelection(2)

        val imageRows = table.rows.count { it["image"].orEmpty().isNotBlank() }
        packageStatus.text = buildString {
            append("Loaded ${table.rows.size} rows from ${imported.sourceLabel}. Columns: ${table.headers.joinToString()}.")
            append(if (imported.isZip) " ZIP package loaded." else " Plain CSV loaded.")
            if (imageRows > 0) append(" $imageRows row(s) request MMS images.")
            if (autoPerRow) append(" Message mode automatically set to the CSV 'message' column.")
            if (table.rows.size > MAX_BATCH_SIZE) append(" Send All is capped at $MAX_BATCH_SIZE recipients.")
            val duplicateCount = MessageCompiler.duplicatePhoneCount(table)
            if (duplicateCount > 0) append(" Warning: $duplicateCount phone number(s) appear more than once.")
        }
        invalidatePreview()
        previewText.text = "File loaded. Tap Preview and validate before sending."
        sendStatus.text = "Nothing submitted."
    }

    private fun showImportError(t: Throwable) {
        recipientPackage?.rootDir?.deleteRecursively()
        recipientPackage = null
        invalidatePreview()
        val reason = if (t is OutOfMemoryError) "The selected package is too large for this phone." else t.message ?: t.javaClass.simpleName
        packageStatus.text = "Import error: $reason"
        previewText.text = "Correct the CSV/ZIP package and import it again."
    }

    private fun previewMessages() {
        previewButton.isEnabled = false
        previewText.text = "Validating recipients, SIM routes, and images…"
        // Read widget state on the UI thread; only image/carrier inspection runs in the worker.
        val compilation = compileMediaOutgoing()
        previewExecutor.submit {
            val result = buildPreviewText(compilation)
            postToActiveUi {
                previewButton.isEnabled = !sending
                previewText.text = result.text
                previewReady = !result.hasErrors && compilation.messages.isNotEmpty()
                testButton.isEnabled = previewReady && !sending
                sendButton.isEnabled = previewReady && compilation.messages.size <= MAX_BATCH_SIZE && !sending
            }
        }
    }

    private fun buildPreviewText(compilation: MediaCompilation): PreviewResult {
        var smsSegments = 0
        var smsCount = 0
        var mmsCount = 0
        val mediaDetails = mutableMapOf<Int, String>()
        val dynamicErrors = mutableListOf<String>()

        compilation.messages.forEach { outgoing ->
            try {
                if (outgoing.imageFile == null) {
                    smsCount++
                    smsSegments += getSmsManager(outgoing.base.subscriptionId).divideMessage(outgoing.base.text).size.coerceAtLeast(1)
                } else {
                    mmsCount++
                    val manager = getSmsManager(outgoing.base.subscriptionId)
                    val limits = ImagePreparer.limits(manager)
                    if (!limits.enabled) {
                        dynamicErrors += "CSV row ${outgoing.base.rowNumber}: MMS is disabled by ${outgoing.base.simLabel}."
                    }
                    val info = ImagePreparer.inspect(outgoing.imageFile)
                    mediaDetails[outgoing.base.rowNumber] = "${outgoing.imageFile.name} — ${info.width}×${info.height}, ${formatBytes(info.sourceBytes)}; carrier limit ~${limits.maxMessageBytes / 1024} KB"
                }
            } catch (t: Throwable) {
                dynamicErrors += "CSV row ${outgoing.base.rowNumber}: ${t.message ?: t.javaClass.simpleName}"
            }
        }

        val errors = (compilation.errors + dynamicErrors).distinct()
        val text = buildString {
            append("Recipients: ${compilation.messages.size}\n")
            append("SMS: $smsCount")
            if (smsCount > 0) append(" ($smsSegments estimated segment(s))")
            append("\nMMS with image: $mmsCount\n")
            if (compilation.warnings.isNotEmpty()) append("Warnings: ${compilation.warnings.joinToString(" | ")}\n")
            if (compilation.messages.size > MAX_BATCH_SIZE) append("ERROR: Send All is blocked above $MAX_BATCH_SIZE recipients.\n")
            if (errors.isNotEmpty()) {
                append("\nERRORS — sending is blocked until fixed:\n")
                errors.take(30).forEach { append("• $it\n") }
                if (errors.size > 30) append("• … ${errors.size - 30} more error(s)\n")
            }
            if (compilation.messages.isNotEmpty()) {
                append("\n--- Preview (first ${min(20, compilation.messages.size)}) ---\n")
                compilation.messages.take(20).forEachIndexed { index, item ->
                    append("\n${index + 1}. ${item.base.phone}\nVia: ${item.base.simLabel}\n")
                    if (item.imageFile != null) {
                        append("MMS image: ${mediaDetails[item.base.rowNumber] ?: item.imageFile.name}\n")
                    } else {
                        append("SMS\n")
                    }
                    append(item.base.text).append('\n')
                }
                if (compilation.messages.size > 20) append("\n… ${compilation.messages.size - 20} more recipient(s).")
            }
        }
        return PreviewResult(text = text, hasErrors = errors.isNotEmpty())
    }

    private fun compileMediaOutgoing(): MediaCompilation {
        val imported = recipientPackage
            ?: return MediaCompilation(emptyList(), listOf("Import a CSV or ZIP first."), emptyList())
        val base = MessageCompiler.compile(imported.table, selectedMode(), messageInput.text.toString())
        val routed = SimRouter.route(
            table = imported.table,
            compilation = base,
            mode = selectedSimRoutingMode(),
            fixedSubscriptionId = selectedFixedSubscriptionId(),
            activeSims = availableSims
        )

        val errors = routed.errors.toMutableList()
        if (selectedSimRoutingMode() == SimRoutingMode.DEFAULT && !hasValidDefaultSubscription()) {
            errors += "Android has no valid default SMS/MMS subscription. Set a default SIM or choose one explicitly."
        }

        val media = routed.messages.mapNotNull { message ->
            val row = imported.table.rows.getOrNull(message.rowNumber - 2).orEmpty()
            val imageValue = row["image"].orEmpty().trim()
            if (imageValue.isBlank()) {
                MediaOutgoing(message, null)
            } else if (!imported.isZip) {
                errors += "CSV row ${message.rowNumber} has image '$imageValue', but images require a ZIP package."
                null
            } else {
                val file = imported.resolveImage(imageValue)
                if (file == null || !file.isFile) {
                    errors += "CSV row ${message.rowNumber} cannot find image '$imageValue' in the ZIP."
                    null
                } else {
                    MediaOutgoing(message, file)
                }
            }
        }

        return MediaCompilation(media, errors.distinct(), routed.warnings)
    }

    private fun prepareSend(testOnly: Boolean) {
        if (!previewReady) {
            Toast.makeText(this, "Run a clean preview before sending.", Toast.LENGTH_LONG).show()
            return
        }
        val compilation = compileMediaOutgoing()
        if (compilation.errors.isNotEmpty() || compilation.messages.isEmpty()) {
            invalidatePreview()
            previewText.text = "The data changed. Preview and resolve errors before sending."
            return
        }
        if (!routesStillValid(compilation.messages)) {
            invalidatePreview()
            previewText.text = "The SIM route changed after preview. Reload SIMs and preview again."
            return
        }
        if (!testOnly && compilation.messages.size > MAX_BATCH_SIZE) {
            Toast.makeText(this, "Batch exceeds the $MAX_BATCH_SIZE-recipient safety cap.", Toast.LENGTH_LONG).show()
            return
        }
        val delayMs = readDelayMs() ?: return
        val batch = if (testOnly) listOf(compilation.messages.first()) else compilation.messages
        val sms = batch.count { it.imageFile == null }
        val mms = batch.size - sms
        val sample = batch.take(3).joinToString("\n\n") {
            val type = if (it.imageFile == null) "SMS" else "MMS + ${it.imageFile.name}"
            "${it.base.phone} via ${it.base.simLabel} [$type]:\n${it.base.text}"
        }
        val warnings = if (compilation.warnings.isEmpty()) "" else "\n\nWarnings:\n${compilation.warnings.joinToString("\n") { "• $it" }}"

        AlertDialog.Builder(this)
            .setTitle(if (testOnly) "Send one test message?" else "Send to ${batch.size} recipients?")
            .setMessage(
                "This will submit $sms SMS and $mms image MMS through the shown SIM routes. MMS images are resized/compressed to the carrier limit before sending. Sending cannot be undone. Delay: ${delayMs}ms.\n\nSample:\n$sample$warnings"
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Send") { _, _ -> ensurePermissionThenSend(PendingSend(batch, delayMs)) }
            .show()
    }

    private fun ensurePermissionThenSend(request: PendingSend) {
        if (checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            sendBatch(request)
        } else {
            pendingSend = request
            requestPermissions(arrayOf(Manifest.permission.SEND_SMS), SEND_SMS_PERMISSION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            SIM_READ_PERMISSION_REQUEST -> {
                if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                    loadActiveSims()
                } else {
                    availableSims = emptyList()
                    replaceSimPickerItems(listOf("Phone/SIM permission required"), null)
                    updateSimRoutingUi(selectedSimRoutingMode())
                    simHelp.text = "Phone permission was denied. Default-SIM routing remains available."
                    invalidatePreview()
                }
            }
            SEND_SMS_PERMISSION_REQUEST -> {
                val request = pendingSend
                pendingSend = null
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && request != null) {
                    if (routesStillValid(request.messages)) sendBatch(request)
                    else {
                        invalidatePreview()
                        previewText.text = "The SIM route changed while permission was being granted. Preview again."
                    }
                } else {
                    Toast.makeText(this, "SMS permission is required to submit SMS or MMS.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun sendBatch(request: PendingSend) {
        val batch = request.messages
        sending = true
        setControlsEnabled(false)
        sendStatus.text = "Starting… 0/${batch.size} submitted."
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        senderExecutor.submit {
            var submitted = 0
            var smsSubmitted = 0
            var mmsSubmitted = 0
            val failures = mutableListOf<String>()

            for ((index, outgoing) in batch.withIndex()) {
                if (Thread.currentThread().isInterrupted) break
                try {
                    if (outgoing.imageFile == null) {
                        sendSms(outgoing)
                        smsSubmitted++
                    } else {
                        sendMms(outgoing)
                        mmsSubmitted++
                    }
                    submitted++
                } catch (t: Throwable) {
                    failures += "CSV row ${outgoing.base.rowNumber} (${outgoing.base.phone}, ${outgoing.base.simLabel}): ${t.message ?: t.javaClass.simpleName}"
                }

                postToActiveUi {
                    sendStatus.text = "Processed ${index + 1}/${batch.size}; submitted $submitted (SMS $smsSubmitted, MMS $mmsSubmitted); immediate failures ${failures.size}."
                }

                if (index < batch.lastIndex && request.delayMs > 0) {
                    try {
                        Thread.sleep(request.delayMs.toLong())
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }

            postToActiveUi {
                sending = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                setControlsEnabled(true)
                invalidatePreview()
                sendStatus.text = buildString {
                    append("Finished submission: $submitted/${batch.size} accepted by Android ($smsSubmitted SMS, $mmsSubmitted MMS).")
                    if (mmsSubmitted > 0) append(" MMS carrier outcomes are logged asynchronously by the app receiver.")
                    if (failures.isNotEmpty()) {
                        append("\nImmediate failures (${failures.size}):\n")
                        failures.take(20).forEach { append("• $it\n") }
                    }
                }
            }
        }
    }

    private fun sendSms(outgoing: MediaOutgoing) {
        val smsManager = getSmsManager(outgoing.base.subscriptionId)
        val parts = smsManager.divideMessage(outgoing.base.text)
        if (parts.size <= 1) {
            smsManager.sendTextMessage(outgoing.base.phone, null, outgoing.base.text, null, null)
        } else {
            smsManager.sendMultipartTextMessage(outgoing.base.phone, null, parts, null, null)
        }
    }

    private fun sendMms(outgoing: MediaOutgoing) {
        val imageFile = requireNotNull(outgoing.imageFile)
        val smsManager = getSmsManager(outgoing.base.subscriptionId)
        val limits = ImagePreparer.limits(smsManager)
        val prepared = ImagePreparer.prepare(imageFile, outgoing.base.text, limits)
        val pdu = MmsPduWriter.compose(
            recipient = outgoing.base.phone,
            text = outgoing.base.text,
            image = MmsPduWriter.ImagePart(prepared.jpegBytes, imageFile.nameWithoutExtension + ".jpg")
        )
        require(pdu.size <= limits.maxMessageBytes) {
            "Encoded MMS is ${pdu.size / 1024} KB, above the carrier limit ${limits.maxMessageBytes / 1024} KB."
        }

        val outDir = File(cacheDir, "mms_out").apply { mkdirs() }
        val pduFile = File(outDir, "mms-${System.currentTimeMillis()}-${outgoing.base.rowNumber}-${System.nanoTime()}.pdu")
        pduFile.writeBytes(pdu)
        val uri = Uri.Builder()
            .scheme("content")
            .authority("$packageName.mms")
            .appendPath(pduFile.name)
            .build()

        val statusIntent = Intent(this, MmsStatusReceiver::class.java).apply {
            putExtra(MmsStatusReceiver.EXTRA_ROW, outgoing.base.rowNumber)
            putExtra(MmsStatusReceiver.EXTRA_PHONE, outgoing.base.phone)
        }
        val requestCode = (System.nanoTime() xor outgoing.base.rowNumber.toLong()).toInt()
        val pending = PendingIntent.getBroadcast(
            this,
            requestCode,
            statusIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        smsManager.sendMultimediaMessage(applicationContext, uri, null, null, pending)
    }

    private fun getSmsManager(subscriptionId: Int?): SmsManager {
        val resolvedId = subscriptionId ?: SubscriptionManager.getDefaultSmsSubscriptionId().also {
            require(isValidSubscriptionIdCompat(it)) {
                "Android has no valid default SMS/MMS subscription."
            }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(SmsManager::class.java).createForSubscriptionId(resolvedId)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getSmsManagerForSubscriptionId(resolvedId)
        }
    }

    private fun hasValidDefaultSubscription(): Boolean {
        return isValidSubscriptionIdCompat(SubscriptionManager.getDefaultSmsSubscriptionId())
    }

    /** SubscriptionManager.isValidSubscriptionId() is unavailable on Android 8 and 9. */
    private fun isValidSubscriptionIdCompat(subscriptionId: Int): Boolean =
        subscriptionId > SubscriptionManager.INVALID_SUBSCRIPTION_ID

    private fun routesStillValid(messages: List<MediaOutgoing>): Boolean {
        if (messages.any { it.base.subscriptionId == null } && !hasValidDefaultSubscription()) return false
        val explicitIds = messages.mapNotNull { it.base.subscriptionId }.toSet()
        if (explicitIds.isEmpty()) return true
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return false
        return runCatching {
            val active = getSystemService(SubscriptionManager::class.java)
                .activeSubscriptionInfoList.orEmpty().map { it.subscriptionId }.toSet()
            active.containsAll(explicitIds)
        }.getOrDefault(false)
    }

    private fun ensureSimAccessAndLoad() {
        val missing = buildList {
            if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.READ_PHONE_STATE)
            if (checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.READ_PHONE_NUMBERS)
        }
        if (missing.isEmpty()) loadActiveSims() else requestPermissions(missing.toTypedArray(), SIM_READ_PERMISSION_REQUEST)
    }

    @SuppressLint("MissingPermission")
    private fun loadActiveSims() {
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(SubscriptionManager::class.java)
        val canReadNumbers = checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED
        val previous = selectedFixedSubscriptionId()
        val loaded = runCatching {
            manager.activeSubscriptionInfoList.orEmpty()
                .filter { it.simSlotIndex >= 0 }
                .map { info ->
                    val number = if (canReadNumbers) {
                        runCatching {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                manager.getPhoneNumber(info.subscriptionId)
                            } else {
                                @Suppress("DEPRECATION")
                                info.number.orEmpty()
                            }
                        }.getOrDefault("")
                    } else ""
                    SimTarget(
                        subscriptionId = info.subscriptionId,
                        slotNumber = info.simSlotIndex + 1,
                        displayName = info.displayName?.toString().orEmpty(),
                        carrierName = info.carrierName?.toString().orEmpty(),
                        phoneNumber = number.trim()
                    )
                }
                .sortedWith(compareBy(SimTarget::slotNumber, SimTarget::subscriptionId))
        }.getOrElse { t ->
            Log.e(LOG_TAG, "Could not load active SIMs", t)
            emptyList()
        }
        availableSims = loaded
        if (loaded.isEmpty()) {
            replaceSimPickerItems(listOf("No active SIMs found"), null)
        } else {
            val defaultId = SubscriptionManager.getDefaultSmsSubscriptionId()
            replaceSimPickerItems(
                loaded.map { it.label() + if (it.subscriptionId == defaultId) " — Android default" else "" },
                loaded.indexOfFirst { it.subscriptionId == previous }.takeIf { it >= 0 }
            )
        }
        updateSimRoutingUi(selectedSimRoutingMode())
        invalidatePreview()
    }

    private fun replaceSimPickerItems(labels: List<String>, selection: Int?) {
        simPicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        selection?.let { simPicker.setSelection(it) }
    }

    private fun readDelayMs(): Int? {
        val value = delayInput.text.toString().trim().toIntOrNull()
        if (value == null || value !in 0..MAX_DELAY_MS) {
            Toast.makeText(this, "Delay must be between 0 and $MAX_DELAY_MS milliseconds.", Toast.LENGTH_LONG).show()
            return null
        }
        return value
    }

    private fun setControlsEnabled(enabled: Boolean) {
        importButton.isEnabled = enabled
        modeSpinner.isEnabled = enabled
        messageInput.isEnabled = enabled && selectedMode() != MessageMode.PER_ROW
        simModeSpinner.isEnabled = enabled
        delayInput.isEnabled = enabled
        previewButton.isEnabled = enabled
        updateSimRoutingUi(selectedSimRoutingMode())
        testButton.isEnabled = false
        sendButton.isEnabled = false
    }

    private fun invalidatePreview() {
        previewReady = false
        if (::testButton.isInitialized) testButton.isEnabled = false
        if (::sendButton.isInitialized) sendButton.isEnabled = false
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun pruneMmsCache() {
        val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        File(cacheDir, "mms_out").listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) file.delete()
        }
    }

    private fun postToActiveUi(block: () -> Unit) {
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            if (!isFinishing && !isDestroyed) block()
        }
    }

    private data class PreviewResult(val text: String, val hasErrors: Boolean)
    private data class MediaOutgoing(val base: Outgoing, val imageFile: File?)
    private data class MediaCompilation(
        val messages: List<MediaOutgoing>,
        val errors: List<String>,
        val warnings: List<String>
    )
    private data class PendingSend(val messages: List<MediaOutgoing>, val delayMs: Int)
}
