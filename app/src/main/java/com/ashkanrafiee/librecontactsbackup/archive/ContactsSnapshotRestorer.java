package com.ashkanrafiee.librecontactsbackup.archive;

import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.Context;
import android.provider.ContactsContract;
import android.util.Log;

import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactsSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactSnapshot.DataRowSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactSnapshot.RawContactSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.RestoreResult;

import java.util.ArrayList;

/**
 * Restores a lossless snapshot back to the Android Contacts Provider.
 *
 * During restore, all raw contacts belonging to the same parent contact
 * are merged into a single raw contact. This prevents the contact
 * multiplication problem (e.g. 758 → 3297) that occurs when multiple
 * raw contacts from different sync accounts are restored as separate
 * entries with null account type.
 *
 * Data rows from all raw contacts are combined, deduplicated by
 * canonical key (mimeType + all data fields), and placed into the
 * single restored raw contact.
 */
public final class ContactsSnapshotRestorer {

    private static final String TAG = "SnapshotRestorer";

    private ContactsSnapshotRestorer() {}

    /**
     * Performs a restore: creates new contacts matching the snapshot.
     * Raw contacts from the same parent are merged into one.
     */
    public static RestoreResult restoreExact(Context context,
                                              AndroidContactsSnapshot snapshot,
                                              RestoreProgress progress) {

        RestoreResult result = new RestoreResult();
        result.contactsRead = snapshot.getContactCount();
        result.rawContactsRead = snapshot.getRawContactCount();
        result.dataRowsRead = snapshot.getDataRowCount();

        Log.i(TAG, "=== Starting restore ===");
        Log.i(TAG, "Snapshot: " + snapshot.getContactCount() + " contacts, "
                + snapshot.getRawContactCount() + " raw contacts, "
                + snapshot.getDataRowCount() + " data rows");

        ContentResolver resolver = context.getContentResolver();
        int totalContacts = snapshot.getContactCount();
        int current = 0;

        for (AndroidContactSnapshot contact : snapshot.contacts) {
            current++;
            if (progress != null) {
                progress.update("Restoring " + current + " of " + totalContacts, current, totalContacts);
            }

            try {
                restoreContact(resolver, contact, result);
                result.contactsCreated++;
            } catch (Exception e) {
                Log.e(TAG, "Failed to restore contact #" + current + " error=" + e.getMessage());
                result.addError("Failed to restore contact: " + e.getMessage());
            }
        }

        Log.i(TAG, "=== Restore complete: " + result.contactsCreated + " contacts, "
                + result.rawContactsCreated + " raw contacts, "
                + result.dataRowsRestored + " data rows restored ===");

        return result;
    }

    /**
     * Restores a single contact by merging all its raw contacts into one.
     */
    private static void restoreContact(ContentResolver resolver,
                                        AndroidContactSnapshot contact,
                                        RestoreResult result) throws Exception {

        // Merge all data rows from all raw contacts, deduplicating by canonical key
        ArrayList<DataRowSnapshot> mergedRows = new ArrayList<>();
        String bestAccountName = null;
        String bestAccountType = null;

        for (RawContactSnapshot rawContact : contact.rawContacts) {
            // Track the first non-null account type
            if (bestAccountName == null && rawContact.accountName != null && !rawContact.accountName.isEmpty()) {
                bestAccountName = rawContact.accountName;
                bestAccountType = rawContact.accountType;
            }
            for (DataRowSnapshot row : rawContact.dataRows) {
                String key = row.canonicalKey();
                boolean duplicate = false;
                for (DataRowSnapshot existing : mergedRows) {
                    if (key.equals(existing.canonicalKey())) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) {
                    mergedRows.add(row);
                }
            }
        }

        Log.d(TAG, "Restoring contact: rawContacts=" + contact.rawContacts.size()
                + " mergedDataRows=" + mergedRows.size());

        // Ensure a name data row exists
        boolean hasNameRow = false;
        for (DataRowSnapshot row : mergedRows) {
            if ("vnd.android.cursor.item/name".equals(row.mimeType)) {
                hasNameRow = true;
                break;
            }
        }
        if (!hasNameRow) {
            String nameToUse = null;
            // Try each raw contact's displayName
            for (RawContactSnapshot rc : contact.rawContacts) {
                if (rc.displayName != null && !rc.displayName.isEmpty()) {
                    nameToUse = rc.displayName;
                    break;
                }
            }
            // Fall back to the parent contact's displayName
            if (nameToUse == null && contact.displayName != null && !contact.displayName.isEmpty()) {
                nameToUse = contact.displayName;
            }
            if (nameToUse != null) {
                Log.d(TAG, "  Synthesizing name row");
                DataRowSnapshot syntheticName = new DataRowSnapshot();
                syntheticName.mimeType = "vnd.android.cursor.item/name";
                syntheticName.data1 = nameToUse;
                mergedRows.add(0, syntheticName);
            } else {
                Log.w(TAG, "  No name available for contact");
            }
        }

        if (mergedRows.isEmpty()) {
            Log.w(TAG, "  WARNING: contact has ZERO data rows!");
        }

        // Build batch: raw contact insert (index 0) + all data rows
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();

        ContentProviderOperation.Builder rawBuilder = ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI);
        if (bestAccountName != null) {
            rawBuilder.withValue(ContactsContract.RawContacts.ACCOUNT_NAME, bestAccountName);
            rawBuilder.withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, bestAccountType);
        } else {
            rawBuilder.withValue(ContactsContract.RawContacts.ACCOUNT_NAME, (String) null);
            rawBuilder.withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, (String) null);
        }
        ops.add(rawBuilder.build());

        for (DataRowSnapshot row : mergedRows) {
            try {
                ContentProviderOperation.Builder dataBuilder = buildDataInsertBuilder(row);
                if (dataBuilder != null) {
                    dataBuilder.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0);
                    ops.add(dataBuilder.build());
                } else {
                    Log.w(TAG, "  Skipping data row: mime=" + row.mimeType);
                }
            } catch (Exception e) {
                Log.e(TAG, "  Build data row FAILED: mime=" + row.mimeType + " err=" + e.getMessage());
                result.addFailedRow(row.mimeType, null, e.getMessage());
            }
        }

        // Apply batch
        try {
            resolver.applyBatch(ContactsContract.AUTHORITY, ops);
            result.rawContactsCreated++;
            result.dataRowsRestored += mergedRows.size();
            Log.d(TAG, "  Batch OK ops=" + ops.size());
            for (DataRowSnapshot row : mergedRows) {
                if (row.data15 != null && row.data15.length > 0) {
                    result.binaryItemsRestored++;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "  Batch insert FAILED: error=" + e.getMessage());
            result.addWarning("Batch insert failed, attempting individual inserts: " + e.getMessage());

            // Fallback: create raw contact alone, then insert data rows one by one
            ArrayList<ContentProviderOperation> singleOp = new ArrayList<>();
            singleOp.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, (String) null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, (String) null)
                    .build());

            try {
                android.content.ContentProviderResult[] results = resolver.applyBatch(ContactsContract.AUTHORITY, singleOp);
                String rawId = results[0].uri != null ? results[0].uri.getLastPathSegment() : null;

                if (rawId != null) {
                    int restored = 0;
                    int failed = 0;
                    for (DataRowSnapshot row : mergedRows) {
                        try {
                            ContentProviderOperation.Builder dataBuilder = buildDataInsertBuilder(row);
                            if (dataBuilder != null) {
                                dataBuilder.withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId);
                                resolver.applyBatch(ContactsContract.AUTHORITY,
                                        new ArrayList<>(java.util.Collections.singletonList(dataBuilder.build())));
                                restored++;
                            }
                        } catch (Exception rowEx) {
                            Log.e(TAG, "  Fallback data row FAILED: mime=" + row.mimeType + " err=" + rowEx.getMessage());
                            result.addFailedRow(row.mimeType, null, rowEx.getMessage());
                            failed++;
                        }
                    }
                    result.dataRowsRestored += restored;
                    result.dataRowsFailed += failed;
                    Log.d(TAG, "  Fallback data rows: " + restored + " restored, " + failed + " failed");
                } else {
                    result.dataRowsFailed += mergedRows.size();
                }
            } catch (Exception ex) {
                Log.e(TAG, "  Fallback raw contact insert FAILED: " + ex.getMessage());
                result.addFailedRow("raw_contact", null, ex.getMessage());
                result.dataRowsFailed += mergedRows.size();
            }
        }
    }

    /**
     * Builds a ContentProviderOperation.Builder to insert a data row.
     * Maps snapshot fields to the appropriate ContactsContract columns.
     * Returns null if the row cannot be mapped.
     */
    private static ContentProviderOperation.Builder buildDataInsertBuilder(DataRowSnapshot row) throws Exception {
        ContentProviderOperation.Builder builder = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI);
        builder.withValue(ContactsContract.Data.MIMETYPE, row.mimeType);

        String mime = row.mimeType;
        if (mime == null) return null;

        switch (mime) {
            case "vnd.android.cursor.item/name":
                if (row.data1 != null) builder.withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, row.data1);
                if (row.data2 != null) builder.withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, row.data2);
                if (row.data3 != null) builder.withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, row.data3);
                if (row.data4 != null) builder.withValue(ContactsContract.CommonDataKinds.StructuredName.PREFIX, row.data4);
                if (row.data5 != null) builder.withValue(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME, row.data5);
                if (row.data6 != null) builder.withValue(ContactsContract.CommonDataKinds.StructuredName.SUFFIX, row.data6);
                if (row.data7 != null) builder.withValue(ContactsContract.CommonDataKinds.StructuredName.PHONETIC_GIVEN_NAME, row.data7);
                if (row.data8 != null) builder.withValue(ContactsContract.CommonDataKinds.StructuredName.PHONETIC_MIDDLE_NAME, row.data8);
                if (row.data9 != null) builder.withValue(ContactsContract.CommonDataKinds.StructuredName.PHONETIC_FAMILY_NAME, row.data9);
                break;

            case "vnd.android.cursor.item/phone_v2":
                if (row.data1 != null) builder.withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, row.data1);
                if (row.data2 != null) builder.withValue(ContactsContract.CommonDataKinds.Phone.TYPE, parseTypeInt(row.data2));
                if (row.data3 != null && parseTypeInt(row.data2) == 0) builder.withValue(ContactsContract.CommonDataKinds.Phone.LABEL, row.data3);
                break;

            case "vnd.android.cursor.item/email_v2":
                if (row.data1 != null) builder.withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, row.data1);
                if (row.data2 != null) builder.withValue(ContactsContract.CommonDataKinds.Email.TYPE, parseTypeInt(row.data2));
                if (row.data3 != null && parseTypeInt(row.data2) == 0) builder.withValue(ContactsContract.CommonDataKinds.Email.LABEL, row.data3);
                break;

            case "vnd.android.cursor.item/postal-address_v2":
            case "vnd.android.cursor.item/postal-address":
                if (row.data4 != null) builder.withValue(ContactsContract.CommonDataKinds.StructuredPostal.POBOX, row.data4);
                if (row.data5 != null) builder.withValue(ContactsContract.CommonDataKinds.StructuredPostal.NEIGHBORHOOD, row.data5);
                if (row.data6 != null) builder.withValue(ContactsContract.CommonDataKinds.StructuredPostal.STREET, row.data6);
                if (row.data7 != null) builder.withValue(ContactsContract.CommonDataKinds.StructuredPostal.CITY, row.data7);
                if (row.data8 != null) builder.withValue(ContactsContract.CommonDataKinds.StructuredPostal.REGION, row.data8);
                if (row.data9 != null) builder.withValue(ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE, row.data9);
                if (row.data10 != null) builder.withValue(ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY, row.data10);
                if (row.data2 != null) builder.withValue(ContactsContract.CommonDataKinds.StructuredPostal.TYPE, parseTypeInt(row.data2));
                if (row.data3 != null && parseTypeInt(row.data2) == 0) builder.withValue(ContactsContract.CommonDataKinds.StructuredPostal.LABEL, row.data3);
                break;

            case "vnd.android.cursor.item/organization":
                if (row.data1 != null) builder.withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, row.data1);
                if (row.data4 != null) builder.withValue(ContactsContract.CommonDataKinds.Organization.TITLE, row.data4);
                if (row.data5 != null) builder.withValue(ContactsContract.CommonDataKinds.Organization.DEPARTMENT, row.data5);
                break;

            case "vnd.android.cursor.item/nickname":
                if (row.data1 != null) builder.withValue(ContactsContract.CommonDataKinds.Nickname.NAME, row.data1);
                break;

            case "vnd.android.cursor.item/note":
                if (row.data1 != null) builder.withValue(ContactsContract.CommonDataKinds.Note.NOTE, row.data1);
                break;

            case "vnd.android.cursor.item/contact_event":
                if (row.data1 != null) builder.withValue(ContactsContract.CommonDataKinds.Event.START_DATE, row.data1);
                if (row.data2 != null) builder.withValue(ContactsContract.CommonDataKinds.Event.TYPE, parseTypeInt(row.data2));
                if (row.data3 != null && parseTypeInt(row.data2) == 0) builder.withValue(ContactsContract.CommonDataKinds.Event.LABEL, row.data3);
                break;

            case "vnd.android.cursor.item/website":
                if (row.data1 != null) builder.withValue(ContactsContract.CommonDataKinds.Website.URL, row.data1);
                if (row.data2 != null) builder.withValue(ContactsContract.CommonDataKinds.Website.TYPE, parseTypeInt(row.data2));
                if (row.data3 != null && parseTypeInt(row.data2) == 0) builder.withValue(ContactsContract.CommonDataKinds.Website.LABEL, row.data3);
                break;

            case "vnd.android.cursor.item/im":
                if (row.data1 != null) builder.withValue(ContactsContract.CommonDataKinds.Im.DATA, row.data1);
                if (row.data5 != null) {
                    int proto = parseTypeInt(row.data5);
                    builder.withValue(ContactsContract.CommonDataKinds.Im.PROTOCOL, proto);
                    if (proto == 0 && row.data6 != null && !row.data6.isEmpty()) {
                        builder.withValue(ContactsContract.CommonDataKinds.Im.CUSTOM_PROTOCOL, row.data6);
                    }
                }
                break;

            case "vnd.android.cursor.item/relation":
                if (row.data1 != null) builder.withValue(ContactsContract.CommonDataKinds.Relation.NAME, row.data1);
                if (row.data2 != null) builder.withValue(ContactsContract.CommonDataKinds.Relation.TYPE, parseTypeInt(row.data2));
                if (row.data3 != null && parseTypeInt(row.data2) == 0) builder.withValue(ContactsContract.CommonDataKinds.Relation.LABEL, row.data3);
                break;

            case "vnd.android.cursor.item/photo":
                if (row.data15 != null && row.data15.length > 0) {
                    builder.withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, row.data15);
                }
                break;

            case "vnd.android.cursor.item/sip-address":
                if (row.data1 != null) builder.withValue(ContactsContract.CommonDataKinds.SipAddress.SIP_ADDRESS, row.data1);
                if (row.data2 != null) builder.withValue(ContactsContract.CommonDataKinds.SipAddress.TYPE, parseTypeInt(row.data2));
                if (row.data3 != null && parseTypeInt(row.data2) == 0) builder.withValue(ContactsContract.CommonDataKinds.SipAddress.LABEL, row.data3);
                break;

            case "vnd.android.cursor.item/group_membership":
                // Skip group membership — groups don't exist on a fresh device.
                // Including this would cause batch failures.
                Log.d(TAG, "  Skipping group_membership row (groups not restored)");
                return null;

            default:
                applyGenericDataFields(builder, row);
                break;
        }

        return builder;
    }

    /**
     * Applies DATA1-DATA15 fields generically for unknown MIME types.
     */
    private static void applyGenericDataFields(ContentProviderOperation.Builder builder, DataRowSnapshot row) {
        if (row.data1 != null) builder.withValue(ContactsContract.Data.DATA1, row.data1);
        if (row.data2 != null) builder.withValue(ContactsContract.Data.DATA2, row.data2);
        if (row.data3 != null) builder.withValue(ContactsContract.Data.DATA3, row.data3);
        if (row.data4 != null) builder.withValue(ContactsContract.Data.DATA4, row.data4);
        if (row.data5 != null) builder.withValue(ContactsContract.Data.DATA5, row.data5);
        if (row.data6 != null) builder.withValue(ContactsContract.Data.DATA6, row.data6);
        if (row.data7 != null) builder.withValue(ContactsContract.Data.DATA7, row.data7);
        if (row.data8 != null) builder.withValue(ContactsContract.Data.DATA8, row.data8);
        if (row.data9 != null) builder.withValue(ContactsContract.Data.DATA9, row.data9);
        if (row.data10 != null) builder.withValue(ContactsContract.Data.DATA10, row.data10);
        if (row.data11 != null) builder.withValue(ContactsContract.Data.DATA11, row.data11);
        if (row.data12 != null) builder.withValue(ContactsContract.Data.DATA12, row.data12);
        if (row.data13 != null) builder.withValue(ContactsContract.Data.DATA13, row.data13);
        if (row.data14 != null) builder.withValue(ContactsContract.Data.DATA14, row.data14);
        if (row.data15 != null && row.data15.length > 0) {
            builder.withValue(ContactsContract.Data.DATA15, row.data15);
        }
    }

    private static int parseTypeInt(String typeStr) {
        if (typeStr == null || typeStr.isEmpty()) return 0;
        try {
            return Integer.parseInt(typeStr);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public interface RestoreProgress {
        void update(String message, int current, int total);
    }
}
