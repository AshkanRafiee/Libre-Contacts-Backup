# Libre Contacts Backup privacy policy

**Effective date: 2026-10-21**

Libre Contacts Backup is local-first software. It does not operate an account,
analytics service, advertising service, cloud backend, or automatic network
upload path. The app does not request the Android `INTERNET` permission.

## Data handled on the device

When you start a manual or scheduled backup, or a restore, Android may grant
the app access to the contacts and SIM providers needed for the operation. The
app uses that data on the device to create or restore the archive you selected.
The app does not automatically send contact data, phone numbers, email
addresses, passwords, or archive contents to the project or any third party.

## Issue reports

From the About screen, you may create an issue report. Before sharing, the app
asks whether to add an optional diagnostic section at the end of the report.
That section contains only an allow-listed app version, Android version,
language, device model, ROM/build information, read/write permission states,
backup feature state, retention mode, and whether the last backup succeeded.
The SIM scope is identified as best-effort because device support varies.

Diagnostics contain no contact data, phone numbers, email addresses, passwords,
archives, storage paths, or backup error messages. They intentionally do not
include unique device identifiers, though device and build information may
identify a device family or software release. You can also choose to share
only the report template without diagnostics. Nothing is sent automatically;
the email or sharing app you choose controls what leaves the device, including
any text you add.

Manual exports and reports are shared only when you select an Android app to
handle them. A cloud-backed Storage Access Framework destination can also sync
files according to that provider's behavior.

## Backup destination and deletion

You choose the backup destination through Android's Storage Access Framework.
That destination may be local storage or a cloud-backed document provider. Any
syncing, retention, or access provided by that provider is controlled by the
provider and your Android account, not by Libre Contacts Backup.

Archives remain wherever you chose to save them until you delete them or the
configured retention policy removes older backup sets. Removing app data from
Android Settings removes local app preferences and the locally retained
Keystore-wrapped password, but does not automatically delete files in a
Storage Access Framework destination.

## Contact

For privacy questions, contact
`librecontactsbackup.abstract692@passmail.net`.
