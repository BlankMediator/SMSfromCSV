# SMSfromCSV project specification

## Goal

SMSfromCSV is an intentionally small Android utility for private, consent-based messaging. It imports a UTF-8 CSV and submits each row as an individual SMS through the physical device's default SIM. Recipient data remains on the device; the app does not use a cloud SMS provider or require internet access.

## Functional requirements

- Import with Android's system document picker and do not request broad storage access.
- Require a `phone` column and support arbitrary additional columns.
- Support a shared exact message, a shared placeholder template, and per-row messages from a `message` column.
- Parse standard CSV, including quoted commas, escaped quotes, CRLF/LF, multiline fields, and an optional UTF-8 BOM.
- Validate phone values conservatively and never infer a missing country code.
- Block blank messages and unresolved placeholders.
- Warn about duplicate normalised phone numbers.
- Preview rendered messages and estimated SMS segments before sending.
- Require explicit confirmation before both test and batch sends.
- Provide a configurable delay, progress display, 500-recipient safety cap, and multipart SMS support.
- Request `SEND_SMS` only after confirmation. Never request `INTERNET`, `READ_SMS`, or `RECEIVE_SMS`.
- Clearly distinguish submission to Android's SMS service from carrier delivery.
- Keep malformed CSV input and unexpected parser failures from terminating the activity.

## Verification requirements

- Run the CSV parser and message compiler unit tests.
- Cover quoted commas, multiline fields, BOM-prefixed input, invalid phone values, blank messages, unresolved placeholders, and duplicates.
- Run Android lint and assemble the debug APK.
- Verify that importing or previewing cannot send a message automatically.
- Verify that long messages use Android's multipart SMS API.

## Distribution assumption

This project is designed primarily for private sideloading. Google Play has separate SMS-permission rules and would require an independent policy and product review.
