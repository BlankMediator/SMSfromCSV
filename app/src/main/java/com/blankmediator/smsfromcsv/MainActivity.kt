package com.blankmediator.smsfromcsv

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min

@SuppressLint("SetTextI18n")
class MainActivity : Activity() {
    private companion object {
        const val PICK_CSV_REQUEST = 1001
        const val SEND_SMS_PERMISSION_REQUEST = 1002
        const val SIM_READ_PERMISSION_REQUEST = 1003
        const val MAX_BATCH_SIZE = 500
        const val MAX_CSV_BYTES = 5 * 1024 * 1024
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
    private lateinit var csvStatus: TextView
    private lateinit var modeHelp: TextView
    private lateinit var previewText: TextView
    private lateinit var previewButton: Button
    private lateinit var testButton: Button
    private lateinit var sendButton: Button
    private lateinit var sendStatus: TextView

    private var table: CsvTable? = null
    private var previewReady = false
    private var pendingSend: PendingSend? = null
    private var availableSims: List<SimTarget> = emptyList()
    private var sending = false
    private var importRequestId = 0
    private val importExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val senderExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        updateModeUi(MessageMode.SAME)
        updateSimRoutingUi(SimRoutingMode.DEFAULT)

        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)) {
            AlertDialog.Builder(this)
                .setTitle("SMS not supported")
                .setMessage("This device does not report Android telephony messaging support. A phone with an SMS-capable SIM is required.")
                .setPositiveButton("Close") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        }
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
            text = "Import a CSV, review the rendered messages and SIM route, then submit individual SMS messages through this phone. Recipient data stays on the device."
            textSize = 16f
            setPadding(0, dp(8), 0, dp(12))
        })
        root.addView(TextView(this).apply {
            text = "Dual-SIM phone: use Android's default, choose one active SIM for the batch, or route each CSV row with a sim column."
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(18))
        })

        importButton = Button(this).apply {
            text = "1. Import CSV"
            setOnClickListener { openCsvPicker() }
        }
        root.addView(importButton)

        csvStatus = TextView(this).apply {
            text = "No CSV loaded. Required column: phone. Optional columns include name, link, reference, message, and sim."
            setPadding(0, dp(8), 0, dp(18))
            setTextIsSelectable(true)
        }
        root.addView(csvStatus)

        root.addView(sectionLabel("2. Message mode"))
        modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
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

        modeHelp = TextView(this).apply {
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(modeHelp)

        messageInput = EditText(this).apply {
            minLines = 4
            maxLines = 10
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(
            messageInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(sectionLabel("3. SIM routing"))
        simModeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    "Use Android default SMS SIM",
                    "Choose one SIM for this batch",
                    "Use CSV 'sim' column per recipient"
                )
            )
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val routingMode = selectedSimRoutingMode()
                    updateSimRoutingUi(routingMode)
                    invalidatePreview()
                    if (routingMode != SimRoutingMode.DEFAULT && !sending) {
                        ensureSimAccessAndLoad()
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        root.addView(simModeSpinner)

        simPicker = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("No active SIMs loaded")
            )
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    invalidatePreview()
                }

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
            text = "Delay between recipients in milliseconds (0–$MAX_DELAY_MS). This paces the handset only; carrier limits still apply."
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
            text = "Test with one recipient first. SMS charges, carrier fair-use rules, and device anti-spam prompts may apply. 'Submitted' means accepted by Android's SMS service, not delivered. Incoming replies remain available to your normal SMS/Messages app and are not read here."
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

    private fun selectedFixedSubscriptionId(): Int? {
        return availableSims.getOrNull(simPicker.selectedItemPosition)?.subscriptionId
    }

    private fun updateModeUi(mode: MessageMode) {
        if (!::messageInput.isInitialized || !::modeHelp.isInitialized) return
        when (mode) {
            MessageMode.SAME -> {
                messageInput.isEnabled = !sending
                messageInput.hint = "Type the exact SMS sent to every row. A shared RSVP link can be included here."
                modeHelp.text = "Free-form mode sends the text below exactly as entered; braces are not treated as placeholders."
            }
            MessageMode.TEMPLATE -> {
                messageInput.isEnabled = !sending
                messageInput.hint = "Hi {name}, your details are available here: {link}"
                modeHelp.text = "Use any normalised CSV header in braces, such as {name}, {link}, or {reference}."
            }
            MessageMode.PER_ROW -> {
                messageInput.isEnabled = false
                messageInput.hint = "Messages come from the CSV 'message' column."
                modeHelp.text = "Each row supplies its own message. Placeholders inside a row's message are also rendered."
            }
        }
    }

    private fun updateSimRoutingUi(mode: SimRoutingMode) {
        if (!::simPicker.isInitialized || !::refreshSimsButton.isInitialized || !::simHelp.isInitialized) return
        val usesLoadedSims = mode != SimRoutingMode.DEFAULT
        simPicker.visibility = if (mode == SimRoutingMode.FIXED) View.VISIBLE else View.GONE
        refreshSimsButton.visibility = if (usesLoadedSims) View.VISIBLE else View.GONE
        simPicker.isEnabled = !sending && mode == SimRoutingMode.FIXED && availableSims.isNotEmpty()
        refreshSimsButton.isEnabled = !sending && usesLoadedSims

        simHelp.text = when (mode) {
            SimRoutingMode.DEFAULT ->
                "Uses Android's configured default SMS subscription. Preview is blocked if Android has no default."
            SimRoutingMode.FIXED -> if (availableSims.isEmpty()) {
                "Load active SIMs, then choose the source SIM for the whole batch."
            } else {
                "Choose the source SIM above. Changing it invalidates the preview so the route must be checked again."
            }
            SimRoutingMode.PER_ROW -> if (availableSims.isEmpty()) {
                "Load active SIMs before validating the CSV 'sim' column."
            } else {
                "CSV 'sim' accepts 1, 2, SIM1, SIM2, slot1, slot2, sub:<id>, an exact SIM label/carrier, or an exposed SIM phone number."
            }
        }
    }

    private fun ensureSimAccessAndLoad() {
        if (!::simPicker.isInitialized) return
        val missingPermissions = buildList {
            if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.READ_PHONE_STATE)
            }
            if (checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.READ_PHONE_NUMBERS)
            }
        }
        if (missingPermissions.isEmpty()) {
            loadActiveSims()
        } else {
            requestPermissions(missingPermissions.toTypedArray(), SIM_READ_PERMISSION_REQUEST)
        }
    }

    @SuppressLint("MissingPermission")
    private fun loadActiveSims() {
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            availableSims = emptyList()
            replaceSimPickerItems(listOf("Phone/SIM permission required"), null)
            updateSimRoutingUi(selectedSimRoutingMode())
            simHelp.text = "Phone permission is required to list active SIMs. Default-SIM routing remains available."
            invalidatePreview()
            return
        }

        val manager = getSystemService(SubscriptionManager::class.java)
        if (manager == null) {
            availableSims = emptyList()
            replaceSimPickerItems(listOf("Subscription service unavailable"), null)
            updateSimRoutingUi(selectedSimRoutingMode())
            simHelp.text = "Android's subscription service is unavailable on this device."
            invalidatePreview()
            return
        }

        val previousSelection = selectedFixedSubscriptionId()
        val canReadNumbers =
            checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED
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
                    } else {
                        ""
                    }
                    SimTarget(
                        subscriptionId = info.subscriptionId,
                        slotNumber = info.simSlotIndex + 1,
                        displayName = info.displayName?.toString().orEmpty(),
                        carrierName = info.carrierName?.toString().orEmpty(),
                        phoneNumber = number.trim()
                    )
                }
                .sortedWith(compareBy(SimTarget::slotNumber, SimTarget::subscriptionId))
        }.getOrElse { throwable ->
            Log.e(LOG_TAG, "Could not load active SIMs", throwable)
            availableSims = emptyList()
            replaceSimPickerItems(listOf("Could not load active SIMs"), null)
            updateSimRoutingUi(selectedSimRoutingMode())
            simHelp.text = "Could not load active SIMs: ${throwable.message ?: throwable.javaClass.simpleName}"
            invalidatePreview()
            return
        }

        availableSims = loaded
        if (loaded.isEmpty()) {
            replaceSimPickerItems(listOf("No active SMS SIMs found"), null)
        } else {
            val defaultSubscriptionId = SubscriptionManager.getDefaultSmsSubscriptionId()
            val labels = loaded.map { sim ->
                sim.label() + if (sim.subscriptionId == defaultSubscriptionId) " — Android default" else ""
            }
            val selectedIndex = loaded.indexOfFirst { it.subscriptionId == previousSelection }
                .takeIf { it >= 0 } ?: 0
            replaceSimPickerItems(labels, selectedIndex)
        }

        updateSimRoutingUi(selectedSimRoutingMode())
        if (loaded.isNotEmpty() && !canReadNumbers) {
            simHelp.text = simHelp.text.toString() +
                " SIM phone numbers are hidden; grant Phone numbers permission or use SIM1/SIM2."
        } else if (loaded.isNotEmpty() && loaded.none { it.phoneNumber.isNotBlank() }) {
            simHelp.text = simHelp.text.toString() +
                " Android did not report SIM phone numbers; use SIM1/SIM2, a label, or sub:<id>."
        }
        invalidatePreview()
    }

    private fun replaceSimPickerItems(labels: List<String>, selectedIndex: Int?) {
        simPicker.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )
        if (selectedIndex != null) simPicker.setSelection(selectedIndex, false)
    }

    private fun invalidatePreview() {
        previewReady = false
        if (::testButton.isInitialized) testButton.isEnabled = false
        if (::sendButton.isInitialized) sendButton.isEnabled = false
    }

    private fun openCsvPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/csv")
            )
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, PICK_CSV_REQUEST)
    }

    @Deprecated("Uses the platform document picker without adding an AndroidX dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_CSV_REQUEST || resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        val requestId = ++importRequestId
        table = null
        invalidatePreview()
        importButton.isEnabled = false
        csvStatus.text = "Checking CSV safely…"
        previewText.text = "Please wait while the file is checked."

        importExecutor.submit {
            val result = runCatching {
                val parsed = CsvParser.parse(readUtf8CsvWithLimit(uri))
                require(parsed.headers.contains("phone")) {
                    "CSV must have a 'phone' column. Found: ${parsed.headers.joinToString()}"
                }
                require(parsed.rows.isNotEmpty()) { "CSV contains no data rows." }
                ImportedCsv(parsed, MessageCompiler.duplicatePhoneCount(parsed))
            }

            postToActiveUi {
                if (requestId != importRequestId) return@postToActiveUi
                importButton.isEnabled = true
                result.fold(
                    onSuccess = { imported -> showImportedCsv(imported) },
                    onFailure = { throwable -> showCsvImportError(throwable) }
                )
            }
        }
    }

    private fun readUtf8CsvWithLimit(uri: Uri): String {
        return contentResolver.openInputStream(uri)?.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var totalBytes = 0
            while (true) {
                if (Thread.currentThread().isInterrupted) error("CSV import was cancelled.")
                val bytesRead = stream.read(buffer)
                if (bytesRead == -1) break
                totalBytes += bytesRead
                require(totalBytes <= MAX_CSV_BYTES) {
                    "CSV is larger than 5 MB. Export a smaller file and try again."
                }
                output.write(buffer, 0, bytesRead)
            }
            output.toString(Charsets.UTF_8.name())
        } ?: error("Could not open the selected file.")
    }

    private fun showImportedCsv(imported: ImportedCsv) {
        val parsed = imported.table
        table = parsed
        val previousMode = selectedMode()
        val suggestedMode = MessageCompiler.suggestedModeAfterImport(
            table = parsed,
            currentMode = previousMode,
            editorText = messageInput.text.toString()
        )
        val automaticallySelectedPerRowMode = suggestedMode != previousMode
        if (automaticallySelectedPerRowMode) {
            modeSpinner.setSelection(2)
        }
        csvStatus.text = buildString {
            append("Loaded ${parsed.rows.size} rows. Columns: ${parsed.headers.joinToString()}.")
            if (automaticallySelectedPerRowMode) {
                append(" Message mode automatically set to the CSV 'message' column.")
            }
            if (parsed.rows.size > MAX_BATCH_SIZE) {
                append(" Send All is capped at $MAX_BATCH_SIZE recipients.")
            }
            if (imported.duplicateCount > 0) {
                append(" Warning: ${imported.duplicateCount} phone number(s) appear more than once.")
            }
        }
        invalidatePreview()
        previewText.text = "CSV loaded. Tap Preview and validate before sending."
        sendStatus.text = "Nothing submitted."
    }

    private fun showCsvImportError(throwable: Throwable) {
        Log.e(LOG_TAG, "CSV import failed", throwable)
        table = null
        invalidatePreview()
        val reason = when (throwable) {
            is OutOfMemoryError -> "The selected CSV is too large for this phone."
            else -> throwable.message ?: throwable.javaClass.simpleName
        }
        csvStatus.text = "CSV error: $reason"
        previewText.text = "The app stayed open. Correct the CSV and import it again."
    }

    private fun previewMessages() {
        val compilation = compileOutgoing()
        val segmentCounts = runCatching {
            compilation.messages.map { message ->
                getSmsManager(message.subscriptionId).divideMessage(message.text).size.coerceAtLeast(1)
            }
        }.getOrDefault(emptyList())

        previewText.text = buildString {
            append("Recipients: ${compilation.messages.size}\n")
            if (segmentCounts.isNotEmpty()) {
                append("Estimated SMS segments: ${segmentCounts.sum()} total; max ${segmentCounts.maxOrNull()} for one recipient.\n")
            } else if (compilation.messages.isNotEmpty()) {
                append("SMS segment estimate is unavailable on this device.\n")
            }
            if (compilation.warnings.isNotEmpty()) {
                append("Warnings: ${compilation.warnings.joinToString(" | ")}\n")
            }
            if (compilation.messages.size > MAX_BATCH_SIZE) {
                append("ERROR: Send All is blocked above the $MAX_BATCH_SIZE-recipient safety cap.\n")
            }
            if (compilation.errors.isNotEmpty()) {
                append("\nERRORS — sending is blocked until fixed:\n")
                compilation.errors.take(20).forEach { append("• $it\n") }
                if (compilation.errors.size > 20) {
                    append("• … ${compilation.errors.size - 20} more error(s)\n")
                }
            }
            if (compilation.messages.isNotEmpty()) {
                append("\n--- Preview (first ${min(20, compilation.messages.size)}) ---\n")
                compilation.messages.take(20).forEachIndexed { index, message ->
                    append("\n${index + 1}. ${message.phone}\nVia: ${message.simLabel}\n${message.text}\n")
                }
                if (compilation.messages.size > 20) {
                    append("\n… ${compilation.messages.size - 20} more recipient(s).")
                }
            }
        }

        previewReady = compilation.errors.isEmpty() && compilation.messages.isNotEmpty()
        testButton.isEnabled = previewReady
        sendButton.isEnabled = previewReady && compilation.messages.size <= MAX_BATCH_SIZE
    }

    private fun prepareSend(testOnly: Boolean) {
        if (!previewReady) {
            Toast.makeText(this, "Run a clean preview before sending.", Toast.LENGTH_LONG).show()
            return
        }

        val compilation = compileOutgoing()
        if (compilation.errors.isNotEmpty() || compilation.messages.isEmpty()) {
            invalidatePreview()
            previewText.text = "The data changed. Preview and resolve errors before sending."
            return
        }
        if (!routedSubscriptionsAreStillActive(compilation.messages)) {
            invalidatePreview()
            previewText.text = "The active SIMs changed after preview. Reload the active SIMs and preview again before sending."
            return
        }
        if (!testOnly && compilation.messages.size > MAX_BATCH_SIZE) {
            Toast.makeText(this, "Batch exceeds the $MAX_BATCH_SIZE-recipient safety cap.", Toast.LENGTH_LONG).show()
            return
        }
        val delayMs = readDelayMs() ?: return
        val batch = if (testOnly) listOf(compilation.messages.first()) else compilation.messages
        val estimatedSegments = runCatching {
            batch.sumOf { message ->
                getSmsManager(message.subscriptionId).divideMessage(message.text).size.coerceAtLeast(1)
            }
        }.getOrNull()
        val sample = batch.take(3).joinToString("\n\n") {
            "${it.phone} via ${it.simLabel}:\n${it.text}"
        }
        val warnings = if (compilation.warnings.isEmpty()) {
            ""
        } else {
            "\n\nWarnings:\n${compilation.warnings.joinToString("\n") { "• $it" }}"
        }

        AlertDialog.Builder(this)
            .setTitle(if (testOnly) "Send one test SMS?" else "Send to ${batch.size} recipients?")
            .setMessage(buildString {
                append("This submits ${batch.size} individual message(s) through the SIM route shown below and may incur carrier charges. Sending cannot be undone.")
                if (estimatedSegments != null) append(" Estimated SMS segments: $estimatedSegments.")
                append(" Delay: ${delayMs}ms between recipients.\n\nSample:\n$sample$warnings")
            })
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
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
                    if (routedSubscriptionsAreStillActive(request.messages)) {
                        sendBatch(request)
                    } else {
                        invalidatePreview()
                        previewText.text = "The SIM route changed while permission was being granted. Reload active SIMs and preview again."
                    }
                } else {
                    Toast.makeText(this, "SMS permission is required to send through the SIM.", Toast.LENGTH_LONG).show()
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
            val failures = mutableListOf<String>()
            val submittedBySim = linkedMapOf<String, Int>()
            val smsManagers = mutableMapOf<Int?, SmsManager>()

            for ((index, outgoing) in batch.withIndex()) {
                if (Thread.currentThread().isInterrupted) break

                try {
                    val smsManager = smsManagers.getOrPut(outgoing.subscriptionId) {
                        getSmsManager(outgoing.subscriptionId)
                    }
                    val parts = smsManager.divideMessage(outgoing.text)
                    if (parts.size <= 1) {
                        smsManager.sendTextMessage(outgoing.phone, null, outgoing.text, null, null)
                    } else {
                        smsManager.sendMultipartTextMessage(outgoing.phone, null, parts, null, null)
                    }
                    submitted++
                    submittedBySim[outgoing.simLabel] = submittedBySim.getOrDefault(outgoing.simLabel, 0) + 1
                } catch (exception: Exception) {
                    failures += "CSV row ${outgoing.rowNumber} (${outgoing.phone}, ${outgoing.simLabel}): ${exception.message ?: exception.javaClass.simpleName}"
                }

                postToActiveUi {
                    sendStatus.text = "Processed ${index + 1}/${batch.size}; submitted $submitted; immediate failures ${failures.size}."
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
                val result = buildString {
                    append("Finished. $submitted/${batch.size} submitted to Android's SMS service.")
                    if (submittedBySim.isNotEmpty()) {
                        append("\nSubmitted routes: ")
                        append(submittedBySim.entries.joinToString(" | ") { "${it.key}: ${it.value}" })
                    }
                    if (failures.isNotEmpty()) {
                        append(" ${failures.size} immediate failure(s):\n")
                        failures.take(30).forEach { append("• $it\n") }
                        if (failures.size > 30) append("• … ${failures.size - 30} more failure(s)\n")
                    }
                    append("\nSubmission is not a delivery receipt. This app does not request delivery reports or read replies.")
                }
                finishSendingUi(result)
            }
        }
    }

    private fun postToActiveUi(action: () -> Unit) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) action()
        }
    }

    private fun finishSendingUi(result: String) {
        sending = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setControlsEnabled(true)
        sendStatus.text = result
    }

    private fun setControlsEnabled(enabled: Boolean) {
        importButton.isEnabled = enabled
        modeSpinner.isEnabled = enabled
        simModeSpinner.isEnabled = enabled
        simPicker.isEnabled = enabled && selectedSimRoutingMode() == SimRoutingMode.FIXED && availableSims.isNotEmpty()
        refreshSimsButton.isEnabled = enabled && selectedSimRoutingMode() != SimRoutingMode.DEFAULT
        delayInput.isEnabled = enabled
        previewButton.isEnabled = enabled
        messageInput.isEnabled = enabled && selectedMode() != MessageMode.PER_ROW
        testButton.isEnabled = enabled && previewReady
        sendButton.isEnabled = enabled && previewReady && (table?.rows?.size ?: 0) <= MAX_BATCH_SIZE
    }

    private fun compileOutgoing(): Compilation {
        val data = table
            ?: return Compilation(emptyList(), listOf("Import a CSV first."), emptyList())
        val routingMode = selectedSimRoutingMode()
        val compiled = MessageCompiler.compile(data, selectedMode(), messageInput.text.toString())
        val initiallyRouted = SimRouter.route(
            table = data,
            compilation = compiled,
            mode = routingMode,
            fixedSubscriptionId = selectedFixedSubscriptionId(),
            activeSims = availableSims
        )
        val routingErrors = mutableListOf<String>()
        val routed = if (routingMode == SimRoutingMode.DEFAULT) {
            val defaultSubscriptionId = SubscriptionManager.getDefaultSmsSubscriptionId()
            if (defaultSubscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                routingErrors += "Android has no default SMS SIM. Choose one SIM in the app before sending."
                initiallyRouted
            } else {
                initiallyRouted.copy(
                    messages = initiallyRouted.messages.map { message ->
                        message.copy(
                            subscriptionId = defaultSubscriptionId,
                            simLabel = "Android default SMS SIM (subscription $defaultSubscriptionId)"
                        )
                    }
                )
            }
        } else {
            initiallyRouted
        }
        if (routingMode != SimRoutingMode.DEFAULT &&
            checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED
        ) {
            routingErrors += "Phone permission is required to validate active SIM routing."
        }
        return routed.copy(errors = (routed.errors + routingErrors).distinct())
    }

    @SuppressLint("MissingPermission")
    private fun routedSubscriptionsAreStillActive(messages: List<Outgoing>): Boolean {
        val routedIds = messages.mapNotNull(Outgoing::subscriptionId).toSet()
        if (routedIds.isEmpty()) return true
        if (selectedSimRoutingMode() == SimRoutingMode.DEFAULT) {
            val currentDefault = SubscriptionManager.getDefaultSmsSubscriptionId()
            return currentDefault != SubscriptionManager.INVALID_SUBSCRIPTION_ID &&
                routedIds.size == 1 && routedIds.single() == currentDefault
        }
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val manager = getSystemService(SubscriptionManager::class.java) ?: return false
        val activeIds = runCatching {
            manager.activeSubscriptionInfoList.orEmpty().map { it.subscriptionId }.toSet()
        }.getOrNull() ?: return false
        return routedIds.all(activeIds::contains)
    }

    private fun readDelayMs(): Int? {
        val raw = delayInput.text.toString()
        val delay = raw.toIntOrNull()
        if (delay == null || delay !in 0..MAX_DELAY_MS) {
            delayInput.error = "Enter a whole number from 0 to $MAX_DELAY_MS."
            Toast.makeText(this, "Enter a valid recipient delay.", Toast.LENGTH_LONG).show()
            return null
        }
        delayInput.error = null
        return delay
    }

    private fun getSmsManager(subscriptionId: Int?): SmsManager {
        val defaultManager = getSystemService(SmsManager::class.java) ?: error("SMS service unavailable")
        if (subscriptionId == null) return defaultManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            defaultManager.createForSubscriptionId(subscriptionId)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        }
    }

    override fun onDestroy() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        importRequestId += 1
        importExecutor.shutdownNow()
        senderExecutor.shutdownNow()
        super.onDestroy()
    }

    data class ImportedCsv(val table: CsvTable, val duplicateCount: Int)
    data class PendingSend(val messages: List<Outgoing>, val delayMs: Int)
}
