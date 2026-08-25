# Libre Contacts Backup

A free, open-source, native Android app that backs up your contacts — fully offline, fully local, fully yours.

- **Libre & open source** — GPLv3, no proprietary bits, buildable from source, [available on F-Droid](https://github.com/AshkanRafiee/Libre-Contacts-Backup).
- **Private by design** — no account, no cloud, no analytics, no network permission at all. Contacts are only read when a backup runs.
- **Local by design** — backups are written to a folder you choose on your own device (or SD card); nothing is ever uploaded anywhere.
- **Scheduled backups** — set a daily time and it runs automatically in the background, entirely on-device.
- **Lossless** — preserves the full Contact → RawContact → Data hierarchy (every field, every account, every custom type), not a lossy flattened copy.
- **Optional encryption** — AES-256-GCM with a password, key wrapped by Android Keystore.

## Backup format

Each backup is a single timestamped `.lcb` archive containing:

| File | Purpose |
|------|---------|
| `android-contacts.json` | Canonical lossless snapshot — what restore reads from |
| `contacts.vcf` | Standard vCard, for use with other apps |
| `contacts.json` / `contacts.csv` | Human-readable exports |
| `manifest.json` | Checksums and format version |

On restore, contacts that originally belonged together (e.g. synced from Google *and* stored locally) are merged back into one contact — without ever merging two different people who just happen to share a name.

## Build

```bash
./gradlew assembleDebug     # unsigned debug build
./gradlew assembleRelease   # unsigned release build
```

To produce a signed release, add your own keystore details in a `signing.properties` file (see [`docs/RELEASING.md`](docs/RELEASING.md)). It's gitignored and never belongs in the repo.

## Test

```bash
./gradlew connectedDebugAndroidTest   # instrumented tests, needs a device/emulator
```

## License

GPL-3.0. Source: https://github.com/AshkanRafiee/Libre-Contacts-Backup

## Screenshot

![Libre Contacts Backup main screen](screenshots/main-screen.png)
