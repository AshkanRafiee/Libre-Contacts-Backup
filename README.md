# Libre Contacts Backup

Libre Contacts Backup is an offline and encrypted native Android contact backup app with no third-party runtime dependencies.

Created by Ashkan Rafiee. Suggestions: `librecontactsbackup.abstract692@passmail.net`.

Source: https://github.com/AshkanRafiee/Libre-Contacts-Backup
License: GNU General Public License v3.0

## Included

- Android 8.0+ support, targeting Android API 35
- Reads contacts through `READ_CONTACTS` only when a backup starts
- User-selected folder through the Storage Access Framework
- Daily, weekly, monthly, or custom-time schedules using `AlarmManager`
- Timestamped single-file `.lcb` archives containing VCF, JSON, and CSV
- Optional password-protected AES-GCM archive encryption
- Encryption passwords are wrapped with Android Keystore for scheduled backups
- Retention choices for the last 1, 3, 5, 10, or all backups
- VCF restore through `WRITE_CONTACTS`
- Scheduled alarms are restored after device reboot
- Scheduled runs post a success or failure notification when notifications are allowed
- Store name: `Libre Contacts Backup – Offline & Encrypted`
- Light system theme and a dark, custom Libre Contacts Backup interface

## Build

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

For a local signed release, copy `signing.properties.example` to `signing.properties` and provide your own keystore values. The keystore and signing file are intentionally ignored and never belong in the repository. Without them, `assembleRelease` still produces an unsigned release artifact.

See [`docs/RELEASING.md`](docs/RELEASING.md) for production signing, automated GitHub Releases, and F-Droid submission steps.
