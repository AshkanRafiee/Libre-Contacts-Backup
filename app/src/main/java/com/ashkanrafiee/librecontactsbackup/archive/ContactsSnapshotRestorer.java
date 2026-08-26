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
import com.ashkanrafiee.librecontactsbackup.snapshot.RestoreCategory;
import com.ashkanrafiee.librecontactsbackup.snapshot.RestoreOptions;
import com.ashkanrafiee.librecontactsbackup.snapshot.RestoreResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
    private static final String MIME_PHOTO = "vnd.android.cursor.item/photo";

    /**
     * MIME types handled explicitly below (everything the app has deliberate
     * semantic mapping for), excluding photo and group_membership which have
     * their own {@link RestoreCategory}. Anything not in this set is treated
     * as provider-specific/unknown and restored via the generic DATA1..DATA15
     * passthrough under {@link RestoreCategory#ADDITIONAL_DATA}.
     *
     * Exposed publicly so {@link com.ashkanrafiee.librecontactsbackup.snapshot.BackupAnalyzer}
     * classifies MIME types identically to how restore actually treats them,
     * instead of maintaining a second, potentially-drifting list.
     */
    public static final Set<String> CORE_CONTACT_MIME_TYPES = Collections.unmodifiableSet(new HashSet<>(java.util.Arrays.asList(
            MIME_NAME,
            "vnd.android.cursor.item/phone_v2",
            "vnd.android.cursor.item/email_v2",
            "vnd.android.cursor.item/postal-address_v2",
            "vnd.android.cursor.item/postal-address",
            "vnd.android.cursor.item/organization",
            "vnd.android.cursor.item/nickname",
            "vnd.android.cursor.item/note",
            "vnd.android.cursor.item/contact_event",
            "vnd.android.cursor.item/website",
            "vnd.android.cursor.item/im",
            "vnd.android.cursor.item/relation",
            "vnd.android.cursor.item/sip-address"
    )));

    private ContactsSnapshotRestorer() {}

    /**
     * Performs a restore of every supported category. Kept as a thin
     * back-compat wrapper around {@link #restore} so existing callers/tests
     * that don't need selective restore are unaffected.
     */
    public static RestoreResult restoreExact(Context context,
                                              AndroidContactsSnapshot snapshot,
                                              RestoreProgress progress) {
        return restore(context, snapshot, RestoreOptions.all(), progress);
    }

    /**
     * Performs a restore: creates new contacts matching the snapshot,
     * materializing only the categories selected in {@code options}.
     * Raw contacts from the same source Contact are consolidated into one.
     *
     * Categories not selected are simply never written to the target
     * Contacts Provider during this call — the snapshot object (and the
     * .lcb archive it came from) is never mutated, so the same snapshot can
     * be restored again later with a different selection.
     */
    public static RestoreResult restore(Context context,
                                         AndroidContactsSnapshot snapshot,
                                         RestoreOptions options,
                                         RestoreProgress progress) {

        RestoreResult result = new RestoreResult();
        result.contactsRead = snapshot.getContactCount();
        result.rawContactsRead = snapshot.getRawContactCount();
        result.dataRowsRead = snapshot.getDataRowCount();

        Log.i(TAG, "=== Starting restore ===");
        Log.i(TAG, "Snapshot: " + snapshot.getContactCount() + " contacts, "
                + snapshot.getRawContactCount() + " raw contacts, "
                + snapshot.getDataRowCount() + " data rows, "
                + snapshot.getGroups().size() + " groups, "
                + "categories=" + options.selectedCategories());

        ContentResolver resolver = context.getContentResolver();

        // Don't create target groups nobody asked for if GROUPS wasn't selected.
        Map<Long, Long> groupIdMapping = options.includes(RestoreCategory.GROUPS)
                ? buildGroupMapping(resolver, snapshot)
                : Collections.emptyMap();

        int totalContacts = snapshot.getContactCount();
        int current = 0;
        ArrayList<Long> restoredRawContactIds = new ArrayList<>();

        for (AndroidContactSnapshot contact : snapshot.contacts) {
            current++;
            if (progress != null) {
                progress.update("Restoring " + current + " of " + totalContacts, current, totalContacts);
            }

            try {
                Long newRawContactId = restoreContact(resolver, contact, result, groupIdMapping, options);
                if (newRawContactId != null) {
                    result.contactsCreated++;
                    restoredRawContactIds.add(newRawContactId);
                } else {
                    result.addError("Failed to restore contact #" + current + ": no RawContact was created");
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to restore contact #" + current + " error=" + e.getMessage());
                result.addError("Failed to restore contact: " + e.getMessage());
            }
        }

        separateAccidentallyMergedContacts(resolver, restoredRawContactIds, result);

        if (result.groupMembershipsUnrestored > 0) {
            result.addWarning(result.groupMembershipsUnrestored + " group memberships could not be restored");
        }
        if (result.skippedByUserChoice > 0) {
            result.addWarning(result.skippedByUserChoice + " data row(s) not restored because their category "
                    + "wasn't selected (still preserved in the backup)");
        }

        Log.i(TAG, "=== Restore complete: " + result.contactsCreated + " contacts, "
                + result.rawContactsCreated + " raw contacts, "
                + result.dataRowsRestored + " data rows restored, "
                + result.mergedRawContacts + " raw contacts merged, "
                + result.deduplicatedDataRows + " rows deduplicated, "
                + result.skippedByUserChoice + " skipped by user choice ===");

        return result;
    }

    /**
     * Restores a single source Contact by consolidating all of its
     * RawContacts into one target RawContact, materializing only the
     * categories selected in {@code options}.
     *
     * @return the new RawContact's ID, or null if it could not be created at all.
     */
    private static Long restoreContact(ContentResolver resolver,
                                        AndroidContactSnapshot contact,
                                        RestoreResult result,
                                        Map<Long, Long> groupIdMapping,
                                        RestoreOptions options) throws Exception {

        // Merge all data rows from all raw contacts, deduplicating by canonical key.
        // This consolidation reflects the source Contact structure and happens
        // regardless of restore-category selection; category filtering is
        // applied afterward, to the already-merged/deduplicated list.
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

        if (!options.includes(RestoreCategory.ACCOUNT_INFO)) {
            bestAccountName = null;
            bestAccountType = null;
        }

        // Apply the user's category selection to the merged/deduplicated rows.
        // Rows dropped here are NOT lost: they remain in the snapshot/.lcb,
        // they are simply not materialized during this particular restore.
        ArrayList<DataRowSnapshot> selectedRows = new ArrayList<>();
        for (DataRowSnapshot row : mergedRows) {
            if (isCategorySelected(row.mimeType, options)) {
                selectedRows.add(row);
            } else {
                result.skippedByUserChoice++;
            }
        }
        mergedRows = selectedRows;

        Log.d(TAG, "Restoring contact: rawContacts=" + contact.rawContacts.size()
                + " selectedDataRows=" + mergedRows.size());

        // Ensure a name data row exists so a nameless RawContact belonging to
        // this source Contact never becomes a separate, unlabeled Contact.
        // Only synthesized when CONTACT_INFO is selected — if the user opted
        // out of contact info entirely, don't manufacture a name for them.
        if (options.includes(RestoreCategory.CONTACT_INFO)) {
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
        }

        if (mergedRows.isEmpty()) {
            Log.w(TAG, "  Contact has zero selected data rows (categories deselected or source was empty)");
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

        // These are properties of the row itself (unmappable group, or
        // unmappable in general) — true regardless of whether the batch
        // insert mechanism below succeeds or falls back, so they're
        // committed to `result` directly and exactly once here, rather than
        // via a local counter that a later success/fallback branch would
        // need to remember to add in.
        ArrayList<DataRowSnapshot> insertedRows = new ArrayList<>();
        for (DataRowSnapshot row : mergedRows) {
            try {
                ContentProviderOperation.Builder dataBuilder = buildDataInsertBuilder(row, groupIdMapping);
                if (dataBuilder != null) {
                    dataBuilder.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0);
                    ops.add(dataBuilder.build());
                    insertedRows.add(row);
                } else if (MIME_GROUP_MEMBERSHIP.equals(row.mimeType)) {
                    result.groupMembershipsUnrestored++;
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
            int restoredHere = ops.size() - 1; // exclude the raw-contact insert itself
            result.dataRowsRestored += restoredHere;
            Log.d(TAG, "  Batch OK ops=" + ops.size());
            for (DataRowSnapshot row : insertedRows) {
                if (isProviderData(row.mimeType)) {
                    result.restoredProviderDataRows++;
                } else {
                    result.restoredUserDataRows++;
                }
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
                    // Only retry rows already proven to build successfully in the
                    // first pass above (insertedRows), not the full mergedRows list:
                    // rows that failed to build or were unmappable were already
                    // accounted for exactly once there. buildDataInsertBuilder is a
                    // pure function of (row, groupIdMapping), so re-attempting an
                    // already-failed/unmappable row here would just fail identically
                    // and double-count it — the whole batch failing is not evidence
                    // that a *different* row is now buildable.
                    for (DataRowSnapshot row : insertedRows) {
                        try {
                            ContentProviderOperation.Builder dataBuilder = buildDataInsertBuilder(row, groupIdMapping);
                            dataBuilder.withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId);
                            resolver.applyBatch(ContactsContract.AUTHORITY,
                                    new ArrayList<>(java.util.Collections.singletonList(dataBuilder.build())));
                            restored++;
                            if (isProviderData(row.mimeType)) {
                                result.restoredProviderDataRows++;
                            } else {
                                result.restoredUserDataRows++;
                            }
                            if (row.data15 != null && row.data15.length > 0) {
                                result.binaryItemsRestored++;
                            }
                        } catch (Exception rowEx) {
                            Log.e(TAG, "  Fallback data row FAILED: mime=" + row.mimeType + " err=" + rowEx.getMessage());
                            result.addFailedRow(row.mimeType, null, rowEx.getMessage());
                            failed++;
                        }
                    }
                    result.dataRowsRestored += restored;
                    // Rows that failed to build or were unmappable (e.g. an
                    // unmappable group membership) were already counted once
                    // during the first pass, before the batch was even attempted.
                    Log.d(TAG, "  Fallback data rows: " + restored + " restored, " + failed + " failed");
                    return Long.parseLong(rawId);
                } else {
                    result.dataRowsFailed += insertedRows.size();
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
     * Whether {@code mimeType} belongs to a {@link RestoreCategory} the user
     * selected. Photo and group_membership rows are gated by their own
     * dedicated categories; every other known "core" contact field is gated
     * by CONTACT_INFO; anything else is provider-specific/unknown and gated
     * by ADDITIONAL_DATA.
     */
    private static boolean isCategorySelected(String mimeType, RestoreOptions options) {
        if (MIME_PHOTO.equals(mimeType)) return options.includes(RestoreCategory.PHOTOS);
        if (MIME_GROUP_MEMBERSHIP.equals(mimeType)) return options.includes(RestoreCategory.GROUPS);
        if (CORE_CONTACT_MIME_TYPES.contains(mimeType)) return options.includes(RestoreCategory.CONTACT_INFO);
        return options.includes(RestoreCategory.ADDITIONAL_DATA);
    }

    /**
     * Whether {@code mimeType} is provider-specific/unknown data (as opposed
     * to core contact info, a photo, or a group membership), for the
     * restoredUserDataRows vs restoredProviderDataRows split in {@link RestoreResult}.
     */
    private static boolean isProviderData(String mimeType) {
        return !CORE_CONTACT_MIME_TYPES.contains(mimeType)
                && !MIME_PHOTO.equals(mimeType)
                && !MIME_GROUP_MEMBERSHIP.equals(mimeType);
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
                applyRemainingGenericFields(builder, row, 1, 2, 3);
                break;

            case "vnd.android.cursor.item/email_v2":
                if (row.data1 != null) builder.withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, row.data1);
                if (row.data2 != null) builder.withValue(ContactsContract.CommonDataKinds.Email.TYPE, parseTypeInt(row.data2));
                if (row.data3 != null && parseTypeInt(row.data2) == 0) builder.withValue(ContactsContract.CommonDataKinds.Email.LABEL, row.data3);
                applyRemainingGenericFields(builder, row, 1, 2, 3);
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
                applyRemainingGenericFields(builder, row, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
                break;

            case "vnd.android.cursor.item/organization":
                // Organization.TYPE/LABEL (data2/3) are real fields (TYPE_WORK/TYPE_OTHER),
                // not just an unused CommonColumns alias — same custom-label-at-type-0
                // convention as phone/email. JOB_DESCRIPTION/SYMBOL/PHONETIC_NAME/
                // OFFICE_LOCATION are documented Organization fields that were
                // previously captured but silently dropped on restore.
                if (row.data1 != null) builder.withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, row.data1);
                if (row.data2 != null) builder.withValue(ContactsContract.CommonDataKinds.Organization.TYPE, parseTypeInt(row.data2));
                if (row.data3 != null && parseTypeInt(row.data2) == 0) builder.withValue(ContactsContract.CommonDataKinds.Organization.LABEL, row.data3);
                if (row.data4 != null) builder.withValue(ContactsContract.CommonDataKinds.Organization.TITLE, row.data4);
                if (row.data5 != null) builder.withValue(ContactsContract.CommonDataKinds.Organization.DEPARTMENT, row.data5);
                if (row.data6 != null) builder.withValue(ContactsContract.CommonDataKinds.Organization.JOB_DESCRIPTION, row.data6);
                if (row.data7 != null) builder.withValue(ContactsContract.CommonDataKinds.Organization.SYMBOL, row.data7);
                if (row.data8 != null) builder.withValue(ContactsContract.CommonDataKinds.Organization.PHONETIC_NAME, row.data8);
                if (row.data9 != null) builder.withValue(ContactsContract.CommonDataKinds.Organization.OFFICE_LOCATION, row.data9);
                // data10 (PHONETIC_NAME_STYLE) is provider-computed, like StructuredName's
                // FULL_NAME_STYLE — deliberately not forced onto a fresh insert.
                applyRemainingGenericFields(builder, row, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
                break;

            case "vnd.android.cursor.item/nickname":
                if (row.data1 != null) builder.withValue(ContactsContract.CommonDataKinds.Nickname.NAME, row.data1);
                if (row.data2 != null) builder.withValue(ContactsContract.CommonDataKinds.Nickname.TYPE, parseTypeInt(row.data2));
                if (row.data3 != null && parseTypeInt(row.data2) == 0) builder.withValue(ContactsContract.CommonDataKinds.Nickname.LABEL, row.data3);
                applyRemainingGenericFields(builder, row, 1, 2, 3);
                break;

            case "vnd.android.cursor.item/note":
                if (row.data1 != null) builder.withValue(ContactsContract.CommonDataKinds.Note.NOTE, row.data1);
                applyRemainingGenericFields(builder, row, 1);
                break;

            case "vnd.android.cursor.item/contact_event":
                if (row.data1 != null) builder.withValue(ContactsContract.CommonDataKinds.Event.START_DATE, row.data1);
                if (row.data2 != null) builder.withValue(ContactsContract.CommonDataKinds.Event.TYPE, parseTypeInt(row.data2));
                if (row.data3 != null && parseTypeInt(row.data2) == 0) builder.withValue(ContactsContract.CommonDataKinds.Event.LABEL, row.data3);
                applyRemainingGenericFields(builder, row, 1, 2, 3);
                break;

            case "vnd.android.cursor.item/website":
                if (row.data1 != null) builder.withValue(ContactsContract.CommonDataKinds.Website.URL, row.data1);
                if (row.data2 != null) builder.withValue(ContactsContract.CommonDataKinds.Website.TYPE, parseTypeInt(row.data2));
                if (row.data3 != null && parseTypeInt(row.data2) == 0) builder.withValue(ContactsContract.CommonDataKinds.Website.LABEL, row.data3);
                applyRemainingGenericFields(builder, row, 1, 2, 3);
                break;

            case "vnd.android.cursor.item/im":
                // Im.TYPE/LABEL (data2/3) are real fields (TYPE_HOME/TYPE_WORK/TYPE_OTHER),
                // independent of PROTOCOL/CUSTOM_PROTOCOL (data5/6) — both were previously
                // captured but only the protocol half was restored.
                if (row.data1 != null) builder.withValue(ContactsContract.CommonDataKinds.Im.DATA, row.data1);
                if (row.data2 != null) builder.withValue(ContactsContract.CommonDataKinds.Im.TYPE, parseTypeInt(row.data2));
                if (row.data3 != null && parseTypeInt(row.data2) == 0) builder.withValue(ContactsContract.CommonDataKinds.Im.LABEL, row.data3);
                if (row.data5 != null) {
                    int proto = parseTypeInt(row.data5);
                    builder.withValue(ContactsContract.CommonDataKinds.Im.PROTOCOL, proto);
                    // Unlike TYPE fields elsewhere (where 0 means "custom"), Im's
                    // own custom sentinel is PROTOCOL_CUSTOM = -1; 0 is the
                    // deprecated PROTOCOL_AIM. Using the wrong constant here
                    // silently dropped CUSTOM_PROTOCOL for every custom-protocol
                    // IM address (the common case, since all named protocols are
                    // deprecated in favor of PROTOCOL_CUSTOM + CUSTOM_PROTOCOL).
                    if (proto == ContactsContract.CommonDataKinds.Im.PROTOCOL_CUSTOM
                            && row.data6 != null && !row.data6.isEmpty()) {
                        builder.withValue(ContactsContract.CommonDataKinds.Im.CUSTOM_PROTOCOL, row.data6);
                    }
                }
                applyRemainingGenericFields(builder, row, 1, 2, 3, 5, 6);
                break;

            case "vnd.android.cursor.item/relation":
                if (row.data1 != null) builder.withValue(ContactsContract.CommonDataKinds.Relation.NAME, row.data1);
                if (row.data2 != null) builder.withValue(ContactsContract.CommonDataKinds.Relation.TYPE, parseTypeInt(row.data2));
                if (row.data3 != null && parseTypeInt(row.data2) == 0) builder.withValue(ContactsContract.CommonDataKinds.Relation.LABEL, row.data3);
                applyRemainingGenericFields(builder, row, 1, 2, 3);
                break;

            case "vnd.android.cursor.item/photo":
                if (row.data15 != null && row.data15.length > 0) {
                    builder.withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, row.data15);
                }
                applyRemainingGenericFields(builder, row, 15);
                break;

            case "vnd.android.cursor.item/sip-address":
                if (row.data1 != null) builder.withValue(ContactsContract.CommonDataKinds.SipAddress.SIP_ADDRESS, row.data1);
                if (row.data2 != null) builder.withValue(ContactsContract.CommonDataKinds.SipAddress.TYPE, parseTypeInt(row.data2));
                if (row.data3 != null && parseTypeInt(row.data2) == 0) builder.withValue(ContactsContract.CommonDataKinds.SipAddress.LABEL, row.data3);
                applyRemainingGenericFields(builder, row, 1, 2, 3);
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

        // Applies to every mappable row regardless of MIME type: these mark
        // the user's chosen default (e.g. "preferred" phone/email), which is
        // real user data, not provider bookkeeping like DATA_VERSION or
        // IS_READ_ONLY (deliberately left alone — provider-managed, and not
        // meaningful to force onto a freshly-inserted row).
        if (row.isPrimary != 0) builder.withValue(ContactsContract.Data.IS_PRIMARY, row.isPrimary);
        if (row.isSuperPrimary != 0) builder.withValue(ContactsContract.Data.IS_SUPER_PRIMARY, row.isSuperPrimary);

        return builder;
    }

    private static final String[] GENERIC_DATA_COLUMNS = {
            null, // index 0 unused (DATA1 is index 1)
            ContactsContract.Data.DATA1, ContactsContract.Data.DATA2, ContactsContract.Data.DATA3,
            ContactsContract.Data.DATA4, ContactsContract.Data.DATA5, ContactsContract.Data.DATA6,
            ContactsContract.Data.DATA7, ContactsContract.Data.DATA8, ContactsContract.Data.DATA9,
            ContactsContract.Data.DATA10, ContactsContract.Data.DATA11, ContactsContract.Data.DATA12,
            ContactsContract.Data.DATA13, ContactsContract.Data.DATA14,
    };

    /**
     * For a KNOWN MIME type, applies any captured DATA column not already
     * covered by that type's semantic field mapping (passed as
     * {@code handledIndices}). Some sync adapters/OEMs stash extra data in
     * columns Android itself doesn't define a meaning for on a known MIME
     * type; "we understand this MIME type" must not become "we know which
     * fields matter" — anything captured is attempted here rather than
     * silently discarded. DATA15 is included unless 15 is in the handled set.
     */
    private static void applyRemainingGenericFields(ContentProviderOperation.Builder builder,
                                                      DataRowSnapshot row, int... handledIndices) {
        Set<Integer> handled = new HashSet<>();
        for (int i : handledIndices) handled.add(i);
        for (int i = 1; i <= 14; i++) {
            if (handled.contains(i)) continue;
            String value = row.getData(i);
            if (value != null) builder.withValue(GENERIC_DATA_COLUMNS[i], value);
        }
        if (!handled.contains(15) && row.data15 != null && row.data15.length > 0) {
            builder.withValue(ContactsContract.Data.DATA15, row.data15);
        }
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
