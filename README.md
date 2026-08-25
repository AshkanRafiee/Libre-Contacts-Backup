# Libre Contacts Backup

Libre Contacts Backup is a FLOSS native Android contact backup app that works entirely offline, encrypts your backups, and has no third-party runtime dependencies.

Suggestions: `librecontactsbackup.abstract692@passmail.net`.

Source: https://github.com/AshkanRafiee/Libre-Contacts-Backup
License: GNU General Public License v3.0

## Included

- Android 8.0+ support
- Reads contacts through `READ_CONTACTS` only when a backup starts
- User-selected folder through the Storage Access Framework
- Daily, weekly, monthly, or custom-time schedules using `AlarmManager`
- Timestamped single-file `.lcb` archives containing:
  - `android-contacts.json` — lossless canonical snapshot (Contact → RawContact → Data)
  - `contacts.vcf` — universal vCard for interoperability
  - `contacts.json` — normalized JSON export
  - `contacts.csv` — spreadsheet-compatible export
  - `manifest.json` — archive metadata and checksums
- Optional password-protected AES-256-GCM archive encryption
- Encryption passwords are wrapped with Android Keystore for scheduled backups
- Retention choices for the last 1, 3, 5, 10, or all backups
- Scheduled alarms are restored after device reboot
- Scheduled runs post a success or failure notification when notifications are allowed

## Backup Formats

| Format | Purpose | Preserves hierarchy? |
|--------|---------|---------------------|
| `android-contacts.json` | Lossless restore | Yes — Contact → RawContact → Data |
| `contacts.vcf` | Interoperability (GrapheneOS, other apps) | No — flattened per raw contact |
| `contacts.json` | Machine-readable normalized export | No — flattened per contact |
| `contacts.csv` | Spreadsheet / Excel | No — one row per contact |

## Security

- AES-256-GCM encryption with PBKDF2 key derivation
- Encryption keys stored in Android Keystore (hardware-backed when available)
- No PII logged to logcat in production builds
- `android:allowBackup="false"` prevents cloud extraction of encrypted data
- BackupAlarmReceiver restricted to system broadcasts only
- All source builds reproducible; no committed binaries or proprietary dependencies

## Build

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

For a local signed release, create the ignored `signing.properties` file with your own keystore values as described in [`docs/RELEASING.md`](docs/RELEASING.md). The keystore and signing file are intentionally ignored and never belong in the repository. Without them, `assembleRelease` still produces an unsigned release artifact.

## Testing

```bash
# Instrumented tests (requires emulator or device)
./gradlew connectedDebugAndroidTest

# Device test scripts (requires adb connection)
bash tests/test_all.sh
bash tests/test_100.sh
bash tests/test_edge_cases.sh
bash tests/test_roundtrip.sh
```

## Screenshot

![Libre Contacts Backup main screen](screenshots/main-screen.png)
