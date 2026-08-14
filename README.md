# SMSfromCSV

SMSfromCSV is a small, offline Android app for previewing and sending personalised SMS messages from a CSV file through a physical phone's SIMs. It can use Android's default SMS SIM, one SIM selected for a batch, or a `sim` CSV value for each recipient. It does not upload recipient data, require an internet connection, use a cloud SMS gateway, schedule campaigns, or read replies.

The app is intended for responsible, consent-based private use and sideloaded distribution. Carrier charges, fair-use rules, rate limits, and local messaging laws still apply.

## Features and safeguards

- Imports UTF-8 CSV files with Android's system document picker, without broad storage permission.
- Checks files on a background worker, rejects files above 5 MB, and reports invalid input without closing the app.
- Handles quoted commas, escaped double quotes, CRLF/LF files, quoted multiline fields, and UTF-8 BOM-prefixed files.
- Requires a `phone` column and supports arbitrary additional columns.
- Lists active SIMs on demand and can dynamically select one SIM for the whole batch.
- Supports a `sim` CSV column for per-recipient routing, with every resolved route shown in preview.
- Supports one shared message, a shared template, or a unique `message` value per row.
- Normalises headers for placeholders: `Account Reference` becomes `{account_reference}`.
- Blocks blank messages, invalid phone numbers, and unresolved placeholders.
- Accepts Australian mobile numbers such as `0412345678` and explicit international numbers such as `+61412345678`. It removes formatting characters but never invents a country code.
- Shows up to the first 20 rendered recipient/message pairs and estimates SMS segment counts.
- Warns about duplicate normalised phone numbers.
- Requires a clean preview and final confirmation before test or batch sends.
- Includes **Send test to first CSV recipient**.
- Defaults to a 1000 ms recipient delay and caps batches at 500 recipients.
- Uses Android's multipart SMS API for long messages.
- Keeps the screen awake and displays progress while a batch is active.
- Reports immediate submission failures per row. `Submitted` does not mean `delivered`.

## CSV format

The only required column is `phone`. Other columns are available to templates.

```csv
name,phone,sim,link,reference,message
"Jane Smith","+61400000001","SIM1","https://example.com/details/jane-a1b2","REF-001","Hi {name}, your reference is {reference}. Details: {link}"
```

An editable three-recipient example is available at [`examples/recipients_template.csv`](examples/recipients_template.csv).

Keep phone values as text in Excel, Numbers, or another spreadsheet editor so a leading `+` or `0` is not removed.

### Message modes

1. **Same message for everyone** sends the editor contents exactly as entered. Braces are ordinary characters in this mode.
2. **Template using CSV placeholders** renders fields such as `{name}`, `{link}`, and `{reference}` separately for each row.
3. **Unique message from CSV `message` column** uses each row's message and renders any placeholders in that cell from the same row.

When a newly imported CSV has a nonblank `message` value in every row and the shared editor is still blank, the app automatically selects the per-row mode. An explicit user-selected mode or typed shared message is never overridden.

Unknown placeholders remain unresolved and block sending. A known placeholder with a blank CSV value renders as blank. The preview makes this visible, and a blank `{link}` also produces a warning when used.

### SIM routing modes

1. **Use Android default SMS SIM** resolves the subscription configured in Android settings during preview. Sending is blocked when Android has no default or when that default changes before confirmation.
2. **Choose one SIM for this batch** loads the active subscriptions and applies the selected SIM to every recipient.
3. **Use CSV `sim` column per recipient** resolves and previews a separate active SIM for each row.

The `sim` column accepts:

- `1`, `2`, `SIM1`, `SIM2`, `slot1`, or `slot2` for a physical/logical SIM slot.
- `sub:22` for an exact Android subscription ID shown by the app.
- An exact SIM display label or carrier name when it identifies only one active SIM.
- The exact SIM phone number when Android reports it and Phone numbers permission is granted.

A blank, unknown, inactive, or ambiguous `sim` value blocks sending. SIM phone numbers are not guaranteed to be stored on the SIM or reported by the carrier, so `SIM1`/`SIM2` is the most portable CSV format.

## Build

Prerequisites:

- JDK 17
- Android SDK Platform 37
- Android SDK Build Tools 36.0.0 or newer

The project uses Android Gradle Plugin 9.3.0, its built-in Kotlin support, and Gradle 9.5.1. From the project directory on Windows:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

The generated debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

You can also open the project in a current Android Studio release and run the `app` configuration on a physical SMS-capable phone.

## Install and use

1. Install the APK on a physical Android phone with an active SMS-capable SIM.
2. Open SMSfromCSV and import the CSV.
3. Select a message mode and enter the shared text or template if needed.
4. Choose the SIM routing mode. For fixed or CSV routing, grant Phone/SIM access and load the active SIMs.
5. Set the delay, then tap **Preview and validate**. Inspect each recipient, rendered message, SIM route, warnings, and segment estimate.
6. Tap **Send test to first CSV recipient**, review the confirmation, and grant `SEND_SMS` when Android asks.
7. Verify the test with the recipient before using **SEND ALL**.
8. Keep the app in the foreground until the batch finishes.

Granting SMS permission does not itself send anything. The app requests it only after a confirmed send action.

## Privacy and permissions

The manifest requests:

```xml
<uses-permission android:name="android.permission.SEND_SMS" />
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
<uses-permission android:name="android.permission.READ_PHONE_NUMBERS" />
```

`READ_PHONE_STATE` is used only to list active subscriptions for explicit routing. `READ_PHONE_NUMBERS` allows matching a CSV `sim` value to a line number when Android exposes one; slot, label, and subscription-ID routing continue to work if the number is unavailable. These permissions are requested only when a non-default routing mode is selected.

The app does not request `INTERNET`, `READ_SMS`, `RECEIVE_SMS`, contacts, location, or broad file access. Incoming replies remain in the phone's normal SMS application. Whether outgoing messages appear there can vary by Android and device manufacturer because SMSfromCSV is not the default SMS handler.

The Android application ID and namespace are `com.blankmediator.smsfromcsv`. Because this differs from earlier private Wedding SMS Sender builds, Android installs SMSfromCSV as a separate app instead of upgrading the old installation.

## Operational and distribution notes

- Every SMS segment can incur a carrier charge, and long messages can use multiple segments per recipient.
- The delay is for handset/modem stability and must not be used to evade carrier controls.
- Submission means Android accepted the API call. The app does not request delivery receipts and cannot claim carrier delivery.
- A partially completed batch cannot be recalled. Closing the activity stops remaining work, but already submitted messages stay sent.
- Google Play applies separate restrictions to SMS permissions. Play Store distribution requires its own policy review and may require a different product design.

## Project layout

- `app/src/main/java/com/blankmediator/smsfromcsv/MainActivity.kt` - one-screen UI, permission request, confirmation, progress, and SMS submission.
- `app/src/main/java/com/blankmediator/smsfromcsv/MessagePreparation.kt` - CSV parsing, templating, phone validation, and duplicate detection.
- `app/src/test/java/com/blankmediator/smsfromcsv/` - JVM unit tests for parsing, message preparation, and SIM routing.
- `app/src/main/AndroidManifest.xml` - SMS sending and on-demand SIM discovery permissions plus the launcher activity.
- `examples/recipients_template.csv` - editable three-recipient example.
