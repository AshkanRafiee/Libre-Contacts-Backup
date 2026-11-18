# Libre Contacts Backup user guide

## Make your first backup

Libre Contacts Backup requires Android 8.0 or later.

1. Open **Backup folder** and choose a location in Android’s file picker.
2. If you want password protection, turn on **Encryption** before making the backup.
3. Tap **Back up now**, grant Contacts access if prompted, and check that the backup completed.

No internet connection is needed for a local folder. If you choose a cloud-backed folder, its provider may sync the files; Libre Contacts Backup does not upload them. Before resetting, replacing, or trading in a phone, confirm the archive is there and keep a copy off the phone.

## Schedule backups

Open **Schedule** to set a daily time, a weekly day and time, or a monthly date and time. If you pick a date a shorter month doesn’t have, the backup is scheduled for that month’s last day. Set the schedule to **Off** to stop automatic backups; **Back up now** still works.

On Android 12 and later, allow **Alarms & reminders** if prompted. If backups are late or missing, allow background activity and turn off battery optimization for Libre Contacts Backup in Android Settings. Make sure Contacts permission is still granted and the backup folder is still accessible. Allow notifications if you want to see scheduled backup results without opening the app.

Android can still delay or skip a run. Don’t assume a scheduled time means a backup was created: check the last result in the app and the archive in your folder. If a run was missed, use **Back up now** rather than relying on a catch-up run.

## Decide how long to keep backups

In **Backup retention**, choose one of two policies:

- **Keep last N backup sets** keeps the newest sets, regardless of age. Choose **Keep all** here if you don’t want automatic deletion.
- **Backups of increasing age** keeps the newest available backup in each calendar day, week, and month within the windows you set. For example, **7 daily / 4 weekly / 3 monthly** covers today and the previous six days, this week and the previous three weeks, and this month and the previous two months. Weeks start on Monday. An empty period keeps nothing, and one archive can count in more than one tier. Set a tier to `0` to turn it off.

After a successful manual or scheduled backup, the app trims older timestamped backup sets in the selected folder. This includes encrypted and unencrypted archives, but not standalone CSV or vCard exports. Copy any archive you want to keep independently of retention to another folder before reducing the history.

## Understand backups and exports

**Back up now** and scheduled backups create the same kind of timestamped archive: `.lcb` without encryption or `.lcb.enc` with encryption. The archive captures the structure Android exposes: one displayed contact can contain separate records from several sources, each with its own fields. This preserves more than a flat export.

| Inside a current backup | What it’s for |
| --- | --- |
| `android-contacts.json` | The full snapshot used by Libre Contacts Backup to restore contacts. |
| `contacts.vcf` | A vCard 3.0 copy for other apps. Groups are carried as vCard categories where supported by the importing app. |
| `contacts.json` and `contacts.csv` | Simplified copies for reading or use in other tools. |
| `manifest.json` | The backup format version and checksums used to check the archive’s integrity. |

The app saves the full-size photo Android provides without recompressing it, up to 8 MiB per photo. If a full-size photo is larger or can’t be read, the archive keeps the thumbnail if one is available. The target phone may only accept a thumbnail on restore, even if the full-size photo is in the archive.

Use **CSV** or **VCF** under **Export a copy** for a standalone file to use elsewhere. These simplified copies are always unencrypted, even when backup encryption is on. Use an archive, not a standalone export, for a full restore.

### Password protection

Encryption is off by default. When you enable it, choose a strong password of at least eight characters. New encrypted archives use AES‑256‑GCM with a password-derived key (PBKDF2‑HMAC‑SHA‑256, 600,000 iterations). The app keeps a local copy of the password protected by Android Keystore so scheduled backups can run unattended.

Keep the original password somewhere safe outside the app. You’ll need it to open that archive on this phone or another one. Turning encryption off forgets the saved password and leaves existing files unchanged; clearing app data also removes the saved password without deleting files in your chosen folder. Enabling encryption doesn’t protect archives you already created.

## Restore contacts

1. Tap **Restore from backup** and select a `.lcb` or `.lcb.enc` archive. Grant Contacts access if prompted. For an encrypted archive, enter the password used to create it.
2. Review the category counts and choose what to include. Contact information, photos, and groups are selected by default.
3. Tap **Restore**, keep the app open, and check the result for errors or warnings. Skipped data stays in the original archive, which is never modified by a restore.

**Account/source information** and **Additional contact data** are off by default:

- **Account/source information** carries over the original account details for ordinary contact entries. Leave it off to restore those entries locally, especially when moving to a different device or account. Restoring account details does not set up or sign in to the account.
- **Additional contact data** includes less common fields and entries owned by other apps, such as messaging-app links. For app-specific entries, their data and original account are carried over together, even if **Account/source information** is off. Photos and groups still follow your selections.

The app tries to put linked records back together as one visible contact and keep unrelated contacts separate, even when they share a name. Android assigns new internal record IDs and may reject some fields or group memberships. Keeping account details doesn’t guarantee another app will recognize the restored entry or avoid creating its own copy. Restoring writes contacts into your current address book; don’t assume it will replace or deduplicate contacts already there.

### SIM card contacts

Backups capture names and numbers stored on the SIM if the phone exposes its SIM phonebook. If it can’t be read, the backup reports that it contains device contacts only.

When the archive has SIM entries, **SIM card contacts** is selected by default. Choose **Device contacts** (the default), **SIM card**, or **Both (device and SIM card)**. If Android exposes more than one SIM phonebook, you can pick the destination card. If a card is full or doesn’t accept writes, the restore reports a warning. With **SIM card** selected, the app tries to save rejected entries to the device address book instead.

## Display and support

Open **Display** in the footer to set the app language or choose the system, light, or dark theme.

To share a problem report, open **About → Report a problem**. Choose whether to include technical diagnostics, then select an email or sharing app. Nothing is sent automatically. Optional diagnostics include app and Android versions, device and build details, and backup and permission status. They don’t include contact data, passwords, archives, storage paths, or backup error messages. Review any text you add before sending, and don’t attach an archive to a public report.

You can also use the [GitHub issue tracker](https://github.com/AshkanRafiee/Libre-Contacts-Backup/issues). See the [privacy policy](../PRIVACY.md) for the full data-handling details.
