package com.ashkanrafiee.librecontactsbackup.snapshot;

import java.util.ArrayList;

/**
 * Detailed result of a restore operation, tracking exactly what
 * was restored, what was skipped, and any errors encountered.
 *
 * Every failed operation is recorded. The UI must never report
 * "success" when data was silently dropped.
 */
public final class RestoreResult {

    // Source-side counts (what the backup contained).
    public int contactsRead;
    public int rawContactsRead;
    public int dataRowsRead;

    // Target-side counts (what restore produced).
    public int contactsCreated;
    public int rawContactsCreated;
    public int dataRowsRestored;
    public int binaryItemsRestored;

    // Split of dataRowsRestored by kind, for the restore-selection report:
    // core contact info / photos / group memberships vs. provider-specific
    // or unknown data.
    public int restoredUserDataRows;
    public int restoredProviderDataRows;

    // Intentional transformations (allowed, not data loss).
    public int mergedRawContacts;      // RawContacts folded into their source Contact's single target RawContact
    public int deduplicatedDataRows;   // genuinely identical rows collapsed into one

    // Rows present in the source but intentionally not materialized this run
    // because their RestoreCategory wasn't selected by the user. These are
    // NOT data loss: they remain in the snapshot/.lcb and can be restored
    // later with a different selection.
    public int skippedByUserChoice;

    // Data that could not be carried over, despite being present in the source.
    public int dataRowsSkipped;
    public int dataRowsFailed;
    public int groupMembershipsUnrestored;

    public final ArrayList<String> warnings = new ArrayList<>();
    public final ArrayList<String> errors = new ArrayList<>();

    public final ArrayList<FailedRow> failedRows = new ArrayList<>();

    public RestoreResult() {}

    public void addWarning(String message) {
        warnings.add(message);
    }

    public void addError(String message) {
        errors.add(message);
    }

    public void addFailedRow(String mimeType, String data1, String reason) {
        failedRows.add(new FailedRow(mimeType, data1, reason));
        dataRowsFailed++;
    }

    public boolean hasErrors() { return !errors.isEmpty() || !failedRows.isEmpty(); }
    public boolean hasWarnings() { return !warnings.isEmpty(); }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Source:\n");
        sb.append("  Contacts: ").append(contactsRead).append("\n");
        sb.append("  Raw contacts: ").append(rawContactsRead).append("\n");
        sb.append("  Data rows: ").append(dataRowsRead).append("\n");
        sb.append("Restored:\n");
        sb.append("  Contacts: ").append(contactsCreated).append("\n");
        sb.append("  Data rows: ").append(dataRowsRestored).append("\n");
        if (binaryItemsRestored > 0) {
            sb.append("  Binary items: ").append(binaryItemsRestored).append("\n");
        }
        if (mergedRawContacts > 0) {
            sb.append("Merged: ").append(mergedRawContacts).append(" raw contacts into their source contacts\n");
        }
        if (deduplicatedDataRows > 0) {
            sb.append("Deduplicated: ").append(deduplicatedDataRows).append(" identical rows\n");
        }
        if (skippedByUserChoice > 0) {
            sb.append("Not restored (category not selected, still in backup): ").append(skippedByUserChoice).append("\n");
        }
        if (dataRowsSkipped > 0) {
            sb.append("Skipped: ").append(dataRowsSkipped).append("\n");
        }
        if (dataRowsFailed > 0) {
            sb.append("Failed: ").append(dataRowsFailed).append("\n");
        }
        if (groupMembershipsUnrestored > 0) {
            sb.append("Group memberships not restored: ").append(groupMembershipsUnrestored).append("\n");
        }
        sb.append("Warnings: ").append(warnings.size()).append("\n");
        sb.append("Errors: ").append(errors.size()).append("\n");
        return sb.toString();
    }

    /**
     * Short, callable-facing description of the outcome, without restating
     * "restore complete" — callers already show that as the notification title.
     */
    public String briefSummary() {
        String skippedSuffix = skippedByUserChoice > 0
                ? " (" + skippedByUserChoice + " not selected)" : "";
        if (hasErrors()) {
            return dataRowsRestored + " restored, " + dataRowsFailed + " failed" + skippedSuffix;
        }
        if (hasWarnings()) {
            return dataRowsRestored + " data rows restored, with warnings" + skippedSuffix;
        }
        return contactsCreated + " contacts, " + dataRowsRestored + " data rows restored" + skippedSuffix;
    }

    public static final class FailedRow {
        public final String mimeType;
        public final String data1;
        public final String reason;

        public FailedRow(String mimeType, String data1, String reason) {
            this.mimeType = mimeType;
            this.data1 = data1;
            this.reason = reason;
        }
    }
}
