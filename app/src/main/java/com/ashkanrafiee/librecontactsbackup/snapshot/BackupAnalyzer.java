package com.ashkanrafiee.librecontactsbackup.snapshot;

import com.ashkanrafiee.librecontactsbackup.archive.ContactsSnapshotRestorer;

/**
 * Computes a {@link BackupAnalysis} from an already-decoded snapshot, purely
 * in memory — no device capability probing. Classification of "core" vs
 * "provider-specific/unknown" MIME types uses
 * {@link ContactsSnapshotRestorer#CORE_CONTACT_MIME_TYPES}, the exact same
 * list the restorer itself uses, so the analysis screen can never disagree
 * with what a restore actually does.
 */
public final class BackupAnalyzer {

    private static final String MIME_PHOTO = "vnd.android.cursor.item/photo";
    private static final String MIME_GROUP_MEMBERSHIP = "vnd.android.cursor.item/group_membership";

    private BackupAnalyzer() {}

    public static BackupAnalysis analyze(AndroidContactsSnapshot snapshot) {
        BackupAnalysis analysis = new BackupAnalysis();
        analysis.contactCount = snapshot.getContactCount();
        analysis.rawContactCount = snapshot.getRawContactCount();
        analysis.dataRowCount = snapshot.getDataRowCount();

        int accountLinkedRawContacts = 0;
        for (AndroidContactSnapshot contact : snapshot.contacts) {
            for (AndroidContactSnapshot.RawContactSnapshot rc : contact.rawContacts) {
                if (rc.accountName != null && !rc.accountName.isEmpty()) {
                    accountLinkedRawContacts++;
                }
                for (AndroidContactSnapshot.DataRowSnapshot row : rc.dataRows) {
                    String mime = row.mimeType;
                    if (mime == null) continue;
                    if (MIME_PHOTO.equals(mime)) {
                        increment(analysis, RestoreCategory.PHOTOS);
                    } else if (MIME_GROUP_MEMBERSHIP.equals(mime)) {
                        // Counted via distinct Groups below, not per-membership-row.
                    } else if (ContactsSnapshotRestorer.CORE_CONTACT_MIME_TYPES.contains(mime)) {
                        increment(analysis, RestoreCategory.CONTACT_INFO);
                    } else {
                        increment(analysis, RestoreCategory.ADDITIONAL_DATA);
                    }
                }
            }
        }
        analysis.categoryCounts.put(RestoreCategory.GROUPS, snapshot.getGroups().size());
        analysis.categoryCounts.put(RestoreCategory.ACCOUNT_INFO, accountLinkedRawContacts);

        return analysis;
    }

    private static void increment(BackupAnalysis analysis, RestoreCategory category) {
        analysis.categoryCounts.merge(category, 1, Integer::sum);
    }
}
