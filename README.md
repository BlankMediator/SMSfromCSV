# SMSfromCSV

SMSfromCSV is a small, offline Android app for previewing and sending personalised SMS messages from a CSV file through a physical phone's default SMS SIM. It does not upload recipient data, require an internet connection, use a cloud SMS gateway, schedule campaigns, or read replies.

The app is intended for responsible, consent-based private use and sideloaded distribution. Carrier charges, fair-use rules, rate limits, and local messaging laws still apply.

## Features and safeguards

- Imports UTF-8 CSV files with Android's system document picker, without broad storage permission.
- Checks files on a background worker, rejects files above 5 MB, and reports invalid input without closing the app.
- Handles quoted commas, escaped double quotes, CRLF/LF files, quoted multiline fields, and UTF-8 BOM-prefixed files.
- Requires a `phone` column and supports arbitrary additional columns.
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
name,phone,link,reference,message
"Jane Smith","+61400000001","https://example.com/details/jane-a1b2","REF-001","Hi {name}, your reference is {reference}. Details: {link}"
```

An editable three-recipient example is available at [`examples/recipients_template.csv`](examples/recipients_template.csv).

Keep phone values as text in Excel, Numbers, or another spreadsheet editor so a leading `+` or `0` is not removed.

### Message modes

1. **Same message for everyone** sends the editor contents exactly as entered. Braces are ordinary characters in this mode.
2. **Template using CSV placeholders** renders fields such as `{name}`, `{link}`, and `{reference}` separately for each row.
3. **Unique message from CSV `message` column** uses each row's message and renders any placeholders in that cell from the same row.

Unknown placeholders remain unresolved and block sending. A known placeholder with a blank CSV value renders as blank. The preview makes this visible, and a blank `{link}` also produces a warning when used.

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
2. On a dual-SIM phone, select the intended default SMS SIM in Android settings.
3. Open SMSfromCSV and import the CSV.
4. Select a message mode and enter the shared text or template if needed.
5. Set the delay, then tap **Preview and validate**. Inspect recipients, messages, warnings, and segment estimates.
6. Tap **Send test to first CSV recipient**, review the confirmation, and grant `SEND_SMS` when Android asks.
7. Verify the test with the recipient before using **SEND ALL**.
8. Keep the app in the foreground until the batch finishes.

Granting SMS permission does not itself send anything. The app requests it only after a confirmed send action.

## Privacy and permissions

The manifest requests only:

```xml
<uses-permission android:name="android.permission.SEND_SMS" />
```

It does not request `INTERNET`, `READ_SMS`, `RECEIVE_SMS`, contacts, location, or broad file access. Incoming replies remain in the phone's normal SMS application. Whether outgoing messages appear there can vary by Android and device manufacturer because SMSfromCSV is not the default SMS handler.

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
- `app/src/test/java/com/blankmediator/smsfromcsv/` - JVM unit tests for parsing and message preparation.
- `app/src/main/AndroidManifest.xml` - the single SMS permission and launcher activity.
- `examples/recipients_template.csv` - editable three-recipient example.
