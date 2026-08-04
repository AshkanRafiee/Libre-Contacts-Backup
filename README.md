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
- Timestamped single-file `.lcb` archives containing VCF, JSON, and CSV
- Optional password-protected AES-GCM archive encryption
- Encryption passwords are wrapped with Android Keystore for scheduled backups
- Retention choices for the last 1, 3, 5, 10, or all backups
- Scheduled alarms are restored after device reboot
- Scheduled runs post a success or failure notification when notifications are allowed

## Build

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

For a local signed release, copy `signing.properties.example` to `signing.properties` and provide your own keystore values. The keystore and signing file are intentionally ignored and never belong in the repository. Without them, `assembleRelease` still produces an unsigned release artifact.

## Screenshot

![Libre Contacts Backup main screen](screenshots/main-screen.png)
