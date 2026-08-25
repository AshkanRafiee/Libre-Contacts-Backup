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

    public int contactsRead;
    public int rawContactsRead;
    public int dataRowsRead;

    public int contactsCreated;
    public int rawContactsCreated;
    public int dataRowsRestored;
    public int binaryItemsRestored;

    public int dataRowsSkipped;
    public int dataRowsFailed;

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
        sb.append("Contacts: ").append(contactsRead).append("\n");
        sb.append("Raw contacts: ").append(rawContactsRead).append("\n");
        sb.append("Data rows: ").append(dataRowsRead).append("\n");
        sb.append("Restored: ").append(dataRowsRestored).append("\n");
        if (binaryItemsRestored > 0) {
            sb.append("Binary items restored: ").append(binaryItemsRestored).append("\n");
        }
        if (dataRowsSkipped > 0) {
            sb.append("Skipped: ").append(dataRowsSkipped).append("\n");
        }
        if (dataRowsFailed > 0) {
            sb.append("Failed: ").append(dataRowsFailed).append("\n");
        }
        if (!warnings.isEmpty()) {
            sb.append("Warnings: ").append(warnings.size()).append("\n");
        }
        if (!errors.isEmpty()) {
            sb.append("Errors: ").append(errors.size()).append("\n");
        }
        return sb.toString();
    }

    public String briefSummary() {
        if (hasErrors()) {
            return "Restore completed with issues: " + dataRowsRestored + " restored, " + dataRowsFailed + " failed";
        }
        if (hasWarnings()) {
            return "Restore completed with warnings: " + dataRowsRestored + " restored";
        }
        return "Restore complete: " + contactsCreated + " contacts, " + dataRowsRestored + " data rows";
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
