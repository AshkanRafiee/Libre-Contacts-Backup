package com.ashkanrafiee.librecontactsbackup.archive;

import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.util.Log;

import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactsSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactSnapshot.DataRowSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactSnapshot.RawContactSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactsSnapshot.GroupSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.RestoreResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Restores a lossless snapshot back to the Android Contacts Provider.
 *
 * Restore respects the source Contact grouping as the primary identity
 * boundary: every {@link AndroidContactSnapshot} in the snapshot (one per
 * source Contact) becomes exactly one target RawContact/Contact. All
 * RawContacts that belonged to the same source Contact are consolidated
 * into that single target RawContact; their Data rows are unioned and only
 * genuinely identical rows (same canonical key) are deduplicated.
 *
 * Two source Contacts are NEVER merged into one target Contact, even if
 * they share the same display name. Raw contacts are inserted with normal
 * (default) aggregation — AGGREGATION_MODE_DISABLED is deliberately NOT
 * used, because on this provider it can suspend aggregation entirely and
 * leave a raw contact with no CONTACT_ID at all (an orphan that ordinary
 * delete cannot clean up), not merely make it "its own Contact". Instead,
 * after all raw contacts for this restore have been inserted, a fixup pass
 * ({@link #separateAccidentallyMergedContacts}) checks which ones the
 * platform's own heuristics grouped together and, for any group spanning
 * more than one source Contact, applies AggregationExceptions
 * TYPE_KEEP_SEPARATE to split them back apart — guaranteeing the
 * "same numbers in, same numbers out" contract regardless of what the
 * platform's matching heuristics decided.
 *
 * Group membership is restored by mapping each source Group (captured in
 * the snapshot) onto a matching or newly created target Group; membership
 * rows that cannot be mapped are never silently dropped — they are counted
 * and reported via {@link RestoreResult#groupMembershipsUnrestored}.
 */
public final class ContactsSnapshotRestorer {

    private static final String TAG = "SnapshotRestorer";
    private static final String MIME_GROUP_MEMBERSHIP = "vnd.android.cursor.item/group_membership";
    private static final String MIME_NAME = "vnd.android.cursor.item/name";

    private ContactsSnapshotRestorer() {}

    /**
     * Performs a restore: creates new contacts matching the snapshot.
     * Raw contacts from the same source Contact are consolidated into one.
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
                + snapshot.getDataRowCount() + " data rows, "
                + snapshot.getGroups().size() + " groups");

        ContentResolver resolver = context.getContentResolver();

        Map<Long, Long> groupIdMapping = buildGroupMapping(resolver, snapshot);

        int totalContacts = snapshot.getContactCount();
        int current = 0;
        ArrayList<Long> restoredRawContactIds = new ArrayList<>();

        for (AndroidContactSnapshot contact : snapshot.contacts) {
            current++;
            if (progress != null) {
                progress.update("Restoring " + current + " of " + totalContacts, current, totalContacts);
            }

            try {
                Long newRawContactId = restoreContact(resolver, contact, result, groupIdMapping);
                result.contactsCreated++;
                restoredRawContactIds.add(newRawContactId);
            } catch (Exception e) {
                Log.e(TAG, "Failed to restore contact #" + current + " error=" + e.getMessage());
                result.addError("Failed to restore contact: " + e.getMessage());
            }
        }

        separateAccidentallyMergedContacts(resolver, restoredRawContactIds, result);

        if (result.groupMembershipsUnrestored > 0) {
            result.addWarning(result.groupMembershipsUnrestored + " group memberships could not be restored");
        }

        Log.i(TAG, "=== Restore complete: " + result.contactsCreated + " contacts, "
                + result.rawContactsCreated + " raw contacts, "
                + result.dataRowsRestored + " data rows restored, "
                + result.mergedRawContacts + " raw contacts merged, "
                + result.deduplicatedDataRows + " rows deduplicated ===");

        return result;
    }

    /**
     * Restores a single source Contact by consolidating all of its
     * RawContacts into one target RawContact.
     *
     * @return the new RawContact's ID, or null if it could not be created at all.
     */
    private static Long restoreContact(ContentResolver resolver,
                                        AndroidContactSnapshot contact,
                                        RestoreResult result,
                                        Map<Long, Long> groupIdMapping) throws Exception {

        // Merge all data rows from all raw contacts, deduplicating by canonical key.
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
                } else {
                    result.deduplicatedDataRows++;
                }
            }
        }

        if (contact.rawContacts.size() > 1) {
            result.mergedRawContacts += contact.rawContacts.size() - 1;
        }

        Log.d(TAG, "Restoring contact: rawContacts=" + contact.rawContacts.size()
                + " mergedDataRows=" + mergedRows.size());

        // Ensure a name data row exists so a nameless RawContact belonging to
        // this source Contact never becomes a separate, unlabeled Contact.
        boolean hasNameRow = false;
        for (DataRowSnapshot row : mergedRows) {
            if (MIME_NAME.equals(row.mimeType)) {
                hasNameRow = true;
                break;
            }
        }
        if (!hasNameRow) {
            String nameToUse = null;
            for (RawContactSnapshot rc : contact.rawContacts) {
                if (rc.displayName != null && !rc.displayName.isEmpty()) {
                    nameToUse = rc.displayName;
                    break;
                }
            }
            if (nameToUse == null && contact.displayName != null && !contact.displayName.isEmpty()) {
                nameToUse = contact.displayName;
            }
            if (nameToUse != null) {
                Log.d(TAG, "  Synthesizing name row");
                DataRowSnapshot syntheticName = new DataRowSnapshot();
                syntheticName.mimeType = MIME_NAME;
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

        int unrestoredGroupMemberships = 0;
        for (DataRowSnapshot row : mergedRows) {
            try {
                ContentProviderOperation.Builder dataBuilder = buildDataInsertBuilder(row, groupIdMapping);
                if (dataBuilder != null) {
                    dataBuilder.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0);
                    ops.add(dataBuilder.build());
                } else if (MIME_GROUP_MEMBERSHIP.equals(row.mimeType)) {
                    unrestoredGroupMemberships++;
                    Log.w(TAG, "  Group membership could not be mapped to a target group; row preserved in backup but not restored");
                } else {
                    result.dataRowsSkipped++;
                    Log.w(TAG, "  Skipping data row: mime=" + row.mimeType);
                }
            } catch (Exception e) {
                Log.e(TAG, "  Build data row FAILED: mime=" + row.mimeType + " err=" + e.getMessage());
                result.addFailedRow(row.mimeType, null, e.getMessage());
            }
        }

        // Apply batch
        try {
            android.content.ContentProviderResult[] applied = resolver.applyBatch(ContactsContract.AUTHORITY, ops);
            Long newRawContactId = applied[0].uri != null ? Long.parseLong(applied[0].uri.getLastPathSegment()) : null;
            result.rawContactsCreated++;
            result.groupMembershipsUnrestored += unrestoredGroupMemberships;
            int restoredHere = ops.size() - 1; // exclude the raw-contact insert itself
            result.dataRowsRestored += restoredHere;
            Log.d(TAG, "  Batch OK ops=" + ops.size());
            for (DataRowSnapshot row : mergedRows) {
                if (row.data15 != null && row.data15.length > 0) {
                    result.binaryItemsRestored++;
                }
            }
            return newRawContactId;
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
                    int fallbackUnrestoredGroups = 0;
                    for (DataRowSnapshot row : mergedRows) {
                        try {
                            ContentProviderOperation.Builder dataBuilder = buildDataInsertBuilder(row, groupIdMapping);
                            if (dataBuilder != null) {
                                dataBuilder.withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId);
                                resolver.applyBatch(ContactsContract.AUTHORITY,
                                        new ArrayList<>(java.util.Collections.singletonList(dataBuilder.build())));
                                restored++;
                            } else if (MIME_GROUP_MEMBERSHIP.equals(row.mimeType)) {
                                fallbackUnrestoredGroups++;
                            } else {
                                result.dataRowsSkipped++;
                            }
                        } catch (Exception rowEx) {
                            Log.e(TAG, "  Fallback data row FAILED: mime=" + row.mimeType + " err=" + rowEx.getMessage());
                            result.addFailedRow(row.mimeType, null, rowEx.getMessage());
                            failed++;
                        }
                    }
                    result.dataRowsRestored += restored;
                    result.groupMembershipsUnrestored += fallbackUnrestoredGroups;
                    Log.d(TAG, "  Fallback data rows: " + restored + " restored, " + failed + " failed");
                    return Long.parseLong(rawId);
                } else {
                    result.dataRowsFailed += mergedRows.size();
                    return null;
                }
            } catch (Exception ex) {
                Log.e(TAG, "  Fallback raw contact insert FAILED: " + ex.getMessage());
                result.addFailedRow("raw_contact", null, ex.getMessage());
                result.dataRowsFailed += mergedRows.size();
                return null;
            }
        }
    }

    /**
     * After all raw contacts for this restore have been inserted under
     * normal (default) aggregation, checks which ones the platform's own
     * matching heuristics grouped together. Any group spanning more than
     * one of our restoreContact() calls means two distinct source Contacts
     * got merged — this splits them back apart with AggregationExceptions
     * so restore never silently changes the source Contact count.
     */
    private static void separateAccidentallyMergedContacts(ContentResolver resolver,
                                                             ArrayList<Long> restoredRawContactIds,
                                                             RestoreResult result) {
        ArrayList<Long> validIds = new ArrayList<>();
        for (Long id : restoredRawContactIds) {
            if (id != null) validIds.add(id);
        }
        if (validIds.size() < 2) return;

        StringBuilder where = new StringBuilder(ContactsContract.RawContacts._ID).append(" IN (");
        for (int i = 0; i < validIds.size(); i++) {
            if (i > 0) where.append(',');
            where.append(validIds.get(i));
        }
        where.append(')');

        Map<Long, Long> contactIdByRaw = new HashMap<>();
        Cursor cursor = resolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                new String[]{ContactsContract.RawContacts._ID, ContactsContract.RawContacts.CONTACT_ID},
                where.toString(), null, null);
        if (cursor == null) return;
        try {
            int idxId = cursor.getColumnIndex(ContactsContract.RawContacts._ID);
            int idxContactId = cursor.getColumnIndex(ContactsContract.RawContacts.CONTACT_ID);
            while (cursor.moveToNext()) {
                if (cursor.isNull(idxContactId)) continue;
                contactIdByRaw.put(cursor.getLong(idxId), cursor.getLong(idxContactId));
            }
        } finally {
            cursor.close();
        }

        Map<Long, ArrayList<Long>> rawIdsByContactId = new HashMap<>();
        for (Long rawId : validIds) {
            Long contactId = contactIdByRaw.get(rawId);
            if (contactId == null) continue;
            rawIdsByContactId.computeIfAbsent(contactId, k -> new ArrayList<>()).add(rawId);
        }

        int splitCount = 0;
        for (ArrayList<Long> group : rawIdsByContactId.values()) {
            if (group.size() <= 1) continue;
            // Every raw contact in `group` came from a different
            // restoreContact() call (each inserts exactly one), so the
            // platform incorrectly aggregated distinct source Contacts.
            // Every pair gets its own exception: splitting each member from
            // just the first isn't enough to guarantee the others split
            // from each other too when 3+ source Contacts collided at once.
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    applyAggregationException(resolver, ContactsContract.AggregationExceptions.TYPE_KEEP_SEPARATE,
                            group.get(i), group.get(j));
                    splitCount++;
                }
            }
        }
        if (splitCount > 0) {
            Log.i(TAG, "Split " + splitCount + " raw contact(s) the platform had auto-merged, "
                    + "to preserve distinct source Contact boundaries");
        }
    }

    private static void applyAggregationException(ContentResolver resolver, int type, long rawId1, long rawId2) {
        ContentValues values = new ContentValues();
        values.put(ContactsContract.AggregationExceptions.TYPE, type);
        values.put(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, rawId1);
        values.put(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, rawId2);
        resolver.update(ContactsContract.AggregationExceptions.CONTENT_URI, values, null, null);
    }

    // ================================================================
    // Group restoration
    // ================================================================

    /**
     * Maps each source Group's row ID onto a target Group row ID, creating
     * groups on the target device where a matching one does not already
     * exist. Groups whose account can't be matched or created on the target
     * provider are simply left unmapped — callers must NOT silently drop
     * membership rows that fail to map; they must be counted and reported.
     */
    private static Map<Long, Long> buildGroupMapping(ContentResolver resolver, AndroidContactsSnapshot snapshot) {
        Map<Long, Long> mapping = new HashMap<>();
        if (snapshot.getGroups().isEmpty()) return mapping;

        Map<GroupKey, Long> existingTargetGroups = readExistingGroups(resolver);

        int unmatchedGroups = 0;
        for (GroupSnapshot group : snapshot.getGroups()) {
            GroupKey matchKey = new GroupKey(group.accountName, group.accountType, group.title);
            Long existing = existingTargetGroups.get(matchKey);
            if (existing != null) {
                mapping.put(group.groupId, existing);
                continue;
            }

            Long created = createTargetGroup(resolver, group);
            if (created != null) {
                mapping.put(group.groupId, created);
                existingTargetGroups.put(matchKey, created);
            } else {
                unmatchedGroups++;
            }
        }
        if (unmatchedGroups > 0) {
            // Titles/account names are user data and deliberately not logged here.
            Log.w(TAG, unmatchedGroups + " group(s) could not be created/matched on the target provider");
        }
        return mapping;
    }

    /** Identifies a Group by account + title, without exposing that data through hashCode/equals debugging output. */
    private static final class GroupKey {
        final String accountName, accountType, title;

        GroupKey(String accountName, String accountType, String title) {
            this.accountName = accountName;
            this.accountType = accountType;
            this.title = title;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof GroupKey)) return false;
            GroupKey other = (GroupKey) o;
            return java.util.Objects.equals(accountName, other.accountName)
                    && java.util.Objects.equals(accountType, other.accountType)
                    && java.util.Objects.equals(title, other.title);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(accountName, accountType, title);
        }
    }

    private static Map<GroupKey, Long> readExistingGroups(ContentResolver resolver) {
        Map<GroupKey, Long> result = new HashMap<>();
        Cursor cursor = null;
        try {
            cursor = resolver.query(
                    ContactsContract.Groups.CONTENT_URI,
                    new String[]{
                            ContactsContract.Groups._ID,
                            ContactsContract.Groups.TITLE,
                            ContactsContract.Groups.ACCOUNT_NAME,
                            ContactsContract.Groups.ACCOUNT_TYPE,
                            ContactsContract.Groups.DELETED
                    },
                    null, null, null
            );
        } catch (Exception e) {
            return result;
        }
        if (cursor == null) return result;
        try {
            int idxId = cursor.getColumnIndex(ContactsContract.Groups._ID);
            int idxTitle = cursor.getColumnIndex(ContactsContract.Groups.TITLE);
            int idxAccountName = cursor.getColumnIndex(ContactsContract.Groups.ACCOUNT_NAME);
            int idxAccountType = cursor.getColumnIndex(ContactsContract.Groups.ACCOUNT_TYPE);
            int idxDeleted = cursor.getColumnIndex(ContactsContract.Groups.DELETED);
            while (cursor.moveToNext()) {
                if (idxDeleted >= 0 && cursor.getInt(idxDeleted) != 0) continue;
                String accountName = idxAccountName >= 0 ? cursor.getString(idxAccountName) : null;
                String accountType = idxAccountType >= 0 ? cursor.getString(idxAccountType) : null;
                String title = idxTitle >= 0 ? cursor.getString(idxTitle) : null;
                long id = cursor.getLong(idxId);
                result.put(new GroupKey(accountName, accountType, title), id);
            }
        } finally {
            cursor.close();
        }
        return result;
    }

    private static Long createTargetGroup(ContentResolver resolver, GroupSnapshot group) {
        try {
            ContentValues values = new ContentValues();
            values.put(ContactsContract.Groups.TITLE, group.title != null ? group.title : "");
            values.put(ContactsContract.Groups.GROUP_VISIBLE, 1);
            if (group.accountName != null && group.accountType != null) {
                values.put(ContactsContract.Groups.ACCOUNT_NAME, group.accountName);
                values.put(ContactsContract.Groups.ACCOUNT_TYPE, group.accountType);
            }
            if (group.dataSet != null) {
                values.put(ContactsContract.Groups.DATA_SET, group.dataSet);
            }
            Uri uri = resolver.insert(ContactsContract.Groups.CONTENT_URI, values);
            if (uri == null) return null;
            String lastSegment = uri.getLastPathSegment();
            return lastSegment != null ? Long.parseLong(lastSegment) : null;
        } catch (Exception e) {
            Log.w(TAG, "Failed to create target group: " + e.getMessage());
            return null;
        }
    }

    /**
     * Builds a ContentProviderOperation.Builder to insert a data row.
     * Maps snapshot fields to the appropriate ContactsContract columns.
     * Returns null if the row cannot be mapped (e.g. an unmappable group
     * membership) — callers are responsible for accounting for the loss.
     */
    private static ContentProviderOperation.Builder buildDataInsertBuilder(DataRowSnapshot row,
                                                                             Map<Long, Long> groupIdMapping) throws Exception {
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
                if (row.data1 != null) builder.withValue(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS, row.data1);
                if (row.data4 != null) builder.withValue(ContactsContract.CommonDataKinds.StructuredPostal.STREET, row.data4);
                if (row.data5 != null) builder.withValue(ContactsContract.CommonDataKinds.StructuredPostal.POBOX, row.data5);
                if (row.data6 != null) builder.withValue(ContactsContract.CommonDataKinds.StructuredPostal.NEIGHBORHOOD, row.data6);
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

            case "vnd.android.cursor.item/group_membership": {
                Long oldGroupId = parseLongOrNull(row.data1);
                Long newGroupId = oldGroupId != null ? groupIdMapping.get(oldGroupId) : null;
                if (newGroupId == null) {
                    // Group could not be matched/created on this provider.
                    // The row itself remains intact in the backup (lossless);
                    // the caller records this as an unrestored membership
                    // rather than silently continuing.
                    return null;
                }
                builder.withValue(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID, newGroupId);
                break;
            }

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

    private static Long parseLongOrNull(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public interface RestoreProgress {
        void update(String message, int current, int total);
    }
}
