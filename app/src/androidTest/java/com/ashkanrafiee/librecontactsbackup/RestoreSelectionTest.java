package com.ashkanrafiee.librecontactsbackup;

import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.ashkanrafiee.librecontactsbackup.archive.ContactsSnapshotRestorer;
import com.ashkanrafiee.librecontactsbackup.export.NormalizedJsonExporter;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactsSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.ContactsSnapshotReader;
import com.ashkanrafiee.librecontactsbackup.snapshot.RestoreCategory;
import com.ashkanrafiee.librecontactsbackup.snapshot.RestoreOptions;
import com.ashkanrafiee.librecontactsbackup.snapshot.RestoreResult;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Restore-selection tests (spec section 19). Builds one "rich" source
 * Contact covering all five {@link RestoreCategory} values, then restores
 * it under different {@link RestoreOptions} and verifies — by querying the
 * Contacts Provider directly, not by inspecting RestoreResult counts alone —
 * that only the selected categories were materialized, while confirming the
 * in-memory snapshot itself is never mutated by any restore call (so the
 * same backup can be restored again later with a different selection).
 */
@RunWith(AndroidJUnit4.class)
public class RestoreSelectionTest {

    private static final String UNKNOWN_MIME = "application/x-test-provider-field";

    private ContentResolver resolver;

    @Before
    public void setUp() {
        android.app.UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS);
        resolver = InstrumentationRegistry.getInstrumentation().getTargetContext().getContentResolver();
        cleanupContacts();
    }

    @After
    public void tearDown() {
        cleanupContacts();
    }

    private void cleanupContacts() {
        // A normal (non-syncadapter) delete of a RawContact that has a real
        // account only soft-deletes it (marks it dirty for sync), leaving a
        // zombie row other tests/runs can still match by account+source-id.
        // Deleting through the syncadapter URI forces a real delete, so
        // cleanup between tests is actually clean.
        Uri syncAdapterUri = ContactsContract.RawContacts.CONTENT_URI.buildUpon()
                .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                .build();
        try { resolver.delete(syncAdapterUri, null, null); } catch (Exception e) { /* ignore */ }
        try { resolver.delete(ContactsContract.Groups.CONTENT_URI, null, null); } catch (Exception e) { /* ignore */ }
    }

    private static android.content.Context targetContext() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private String insertRawContact() throws Exception {
        return insertRawContact(null, null, null);
    }

    private String insertRawContact(String accountName, String accountType, String sourceId) throws Exception {
        ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, accountName)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, accountType);
        if (sourceId != null) {
            b.withValue(ContactsContract.RawContacts.SOURCE_ID, sourceId);
        }
        android.content.ContentProviderResult[] results = resolver.applyBatch(ContactsContract.AUTHORITY,
                new ArrayList<>(java.util.Collections.singletonList(b.build())));
        return results[0].uri.getLastPathSegment();
    }

    /**
     * Inserts a RawContact with a raw-contact-level cached DISPLAY_NAME_PRIMARY
     * but no corresponding "name" Data row — exactly what messaging apps'
     * "shadow" RawContacts commonly look like (they cache the display name
     * for their own UI without ever writing a StructuredName row). Requires
     * presenting as a sync adapter, since the platform normally computes
     * DISPLAY_NAME_PRIMARY itself and refuses to let a regular app set it
     * directly.
     */
    private String insertShadowRawContact(String accountName, String accountType, String cachedDisplayName) throws Exception {
        Uri syncAdapterUri = ContactsContract.RawContacts.CONTENT_URI.buildUpon()
                .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                .build();
        ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(syncAdapterUri)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, accountName)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, accountType)
                .withValue(ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY, cachedDisplayName);
        android.content.ContentProviderResult[] results = resolver.applyBatch(ContactsContract.AUTHORITY,
                new ArrayList<>(java.util.Collections.singletonList(b.build())));
        return results[0].uri.getLastPathSegment();
    }

    private void insertRow(String rawId, String mimeType, String... values) throws Exception {
        ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                .withValue(ContactsContract.Data.MIMETYPE, mimeType);
        for (int i = 0; i < values.length && i < 14; i++) {
            if (values[i] != null) b.withValue("data" + (i + 1), values[i]);
        }
        resolver.applyBatch(ContactsContract.AUTHORITY, new ArrayList<>(java.util.Collections.singletonList(b.build())));
    }

    private void insertPhoto(String rawId, byte[] bytes) throws Exception {
        ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                .withValue(ContactsContract.Data.MIMETYPE, "vnd.android.cursor.item/photo")
                .withValue(ContactsContract.Data.DATA15, bytes);
        resolver.applyBatch(ContactsContract.AUTHORITY, new ArrayList<>(java.util.Collections.singletonList(b.build())));
    }

    private long insertGroup(String title) {
        ContentValues values = new ContentValues();
        values.put(ContactsContract.Groups.TITLE, title);
        values.put(ContactsContract.Groups.GROUP_VISIBLE, 1);
        Uri uri = resolver.insert(ContactsContract.Groups.CONTENT_URI, values);
        assertNotNull(uri);
        return Long.parseLong(uri.getLastPathSegment());
    }

    private void insertGroupMembership(String rawId, long groupId) throws Exception {
        ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                .withValue(ContactsContract.Data.MIMETYPE, "vnd.android.cursor.item/group_membership")
                .withValue(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID, groupId);
        resolver.applyBatch(ContactsContract.AUTHORITY, new ArrayList<>(java.util.Collections.singletonList(b.build())));
    }

    private static byte[] samplePhotoBytes() {
        return new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
                0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4,
                (byte) 0x89, 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54, 0x78, (byte) 0x9C, 0x63, 0x00, 0x01, 0x00,
                0x00, 0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, (byte) 0xB4, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
                (byte) 0xAE, 0x42, 0x60, (byte) 0x82
        };
    }

    /** Builds one rich source Contact covering all five RestoreCategory values, and returns its snapshot. */
    private AndroidContactsSnapshot buildRichSnapshot() throws Exception {
        String r1 = insertRawContact();
        insertRow(r1, "vnd.android.cursor.item/name", "Selection Test Person");
        insertRow(r1, "vnd.android.cursor.item/phone_v2", "+1-555-7001", "1");
        insertPhoto(r1, samplePhotoBytes());
        long groupId = insertGroup("Selection Test Group");
        insertGroupMembership(r1, groupId);
        insertRow(r1, UNKNOWN_MIME, "provider-value-1", "provider-value-2");
        Thread.sleep(300);
        return ContactsSnapshotReader.read(targetContext());
    }

    private boolean targetHasMime(String mimeType) {
        Cursor c = resolver.query(ContactsContract.Data.CONTENT_URI,
                new String[]{ContactsContract.Data._ID},
                ContactsContract.Data.MIMETYPE + "=?", new String[]{mimeType}, null);
        if (c == null) return false;
        try { return c.getCount() > 0; } finally { c.close(); }
    }

    private boolean targetHasPhone() {
        return targetHasPhoneNumber("+1-555-7001");
    }

    private boolean targetHasPhoneNumber(String number) {
        Cursor c = resolver.query(ContactsContract.Data.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER},
                ContactsContract.Data.MIMETYPE + "=? AND " + ContactsContract.CommonDataKinds.Phone.NUMBER + "=?",
                new String[]{"vnd.android.cursor.item/phone_v2", number}, null);
        if (c == null) return false;
        try { return c.moveToFirst(); } finally { c.close(); }
    }

    // ============================================================
    // Scenario 1: restore all categories -> everything present.
    // ============================================================
    @Test
    public void scenario1_restoreAllCategories() throws Exception {
        AndroidContactsSnapshot snapshot = buildRichSnapshot();
        cleanupContacts();

        RestoreResult result = ContactsSnapshotRestorer.restore(targetContext(), snapshot, RestoreOptions.all(), (m, c, t) -> {});

        assertTrue(targetHasPhone());
        assertTrue("Photo should be restored", targetHasMime("vnd.android.cursor.item/photo"));
        assertTrue("Group membership should be restored", targetHasMime("vnd.android.cursor.item/group_membership"));
        assertTrue("Unknown/provider-specific field should be restored", targetHasMime(UNKNOWN_MIME));
        assertEquals(0, result.skippedByUserChoice);
    }

    // ============================================================
    // Scenario 2: contacts-only -> photos/groups/provider data NOT
    // materialized, but the snapshot object still has them.
    // ============================================================
    @Test
    public void scenario2_contactsOnly() throws Exception {
        AndroidContactsSnapshot snapshot = buildRichSnapshot();
        int originalDataRows = snapshot.getDataRowCount();
        cleanupContacts();

        RestoreResult result = ContactsSnapshotRestorer.restore(targetContext(), snapshot,
                RestoreOptions.of(RestoreCategory.CONTACT_INFO), (m, c, t) -> {});

        assertTrue("Core contact info should be restored", targetHasPhone());
        assertFalse("Photos should not be materialized when not selected", targetHasMime("vnd.android.cursor.item/photo"));
        assertFalse("Groups should not be materialized when not selected", targetHasMime("vnd.android.cursor.item/group_membership"));
        assertFalse("Provider-specific data should not be materialized when not selected", targetHasMime(UNKNOWN_MIME));
        assertTrue("Deselected rows must be reported, not silently dropped", result.skippedByUserChoice > 0);

        // The snapshot itself (and therefore the .lcb it would be written to)
        // must be completely unaffected by the restore selection.
        assertEquals("Restore must never mutate the in-memory snapshot",
                originalDataRows, snapshot.getDataRowCount());
    }

    // ============================================================
    // Scenario 3: contacts + photos -> photos present, groups/provider absent.
    // ============================================================
    @Test
    public void scenario3_contactsAndPhotos() throws Exception {
        AndroidContactsSnapshot snapshot = buildRichSnapshot();
        cleanupContacts();

        ContactsSnapshotRestorer.restore(targetContext(), snapshot,
                RestoreOptions.of(RestoreCategory.CONTACT_INFO, RestoreCategory.PHOTOS), (m, c, t) -> {});

        assertTrue(targetHasPhone());
        assertTrue("Photos should be restored when selected", targetHasMime("vnd.android.cursor.item/photo"));
        assertFalse(targetHasMime("vnd.android.cursor.item/group_membership"));
        assertFalse(targetHasMime(UNKNOWN_MIME));
    }

    // ============================================================
    // Scenario 4: contacts + groups -> group memberships restored.
    // ============================================================
    @Test
    public void scenario4_contactsAndGroups() throws Exception {
        AndroidContactsSnapshot snapshot = buildRichSnapshot();
        cleanupContacts();

        RestoreResult result = ContactsSnapshotRestorer.restore(targetContext(), snapshot,
                RestoreOptions.of(RestoreCategory.CONTACT_INFO, RestoreCategory.GROUPS), (m, c, t) -> {});

        assertTrue(targetHasPhone());
        assertTrue("Group membership should be restored when GROUPS is selected", targetHasMime("vnd.android.cursor.item/group_membership"));
        assertEquals("The mapped group membership should not be reported as unrestored",
                0, result.groupMembershipsUnrestored);
        assertFalse(targetHasMime("vnd.android.cursor.item/photo"));
        assertFalse(targetHasMime(UNKNOWN_MIME));

        Cursor c = resolver.query(ContactsContract.Groups.CONTENT_URI,
                new String[]{ContactsContract.Groups._ID},
                ContactsContract.Groups.TITLE + "=? AND " + ContactsContract.Groups.DELETED + "=0",
                new String[]{"Selection Test Group"}, null);
        assertNotNull(c);
        try {
            assertTrue("A live target group with the same title should have been created", c.moveToFirst());
        } finally {
            c.close();
        }
    }

    // ============================================================
    // Scenario 5: contacts + additional data -> provider-specific rows restored.
    // ============================================================
    @Test
    public void scenario5_contactsAndAdditionalData() throws Exception {
        AndroidContactsSnapshot snapshot = buildRichSnapshot();
        cleanupContacts();

        ContactsSnapshotRestorer.restore(targetContext(), snapshot,
                RestoreOptions.of(RestoreCategory.CONTACT_INFO, RestoreCategory.ADDITIONAL_DATA), (m, c, t) -> {});

        assertTrue(targetHasPhone());
        assertTrue("Provider-specific/unknown field should be restored when ADDITIONAL_DATA is selected",
                targetHasMime(UNKNOWN_MIME));
        assertFalse(targetHasMime("vnd.android.cursor.item/photo"));
        assertFalse(targetHasMime("vnd.android.cursor.item/group_membership"));
    }

    // ============================================================
    // Scenario 6: the same in-memory snapshot restored twice with different
    // selections -> proves the backup/snapshot was never mutated by the
    // first restore, and each restore honors its own selection independently.
    // ============================================================
    @Test
    public void scenario6_sameSnapshotRestoredTwiceWithDifferentSelections() throws Exception {
        AndroidContactsSnapshot snapshot = buildRichSnapshot();
        String canonicalBefore = NormalizedJsonExporter.exportCanonical(snapshot);
        cleanupContacts();

        // First restore: contacts only.
        ContactsSnapshotRestorer.restore(targetContext(), snapshot,
                RestoreOptions.of(RestoreCategory.CONTACT_INFO), (m, c, t) -> {});
        assertTrue(targetHasPhone());
        assertFalse(targetHasMime("vnd.android.cursor.item/photo"));

        String canonicalAfterFirstRestore = NormalizedJsonExporter.exportCanonical(snapshot);
        assertEquals("Snapshot must be byte-for-byte unchanged after a restore",
                canonicalBefore, canonicalAfterFirstRestore);

        cleanupContacts();

        // Second restore of the SAME snapshot object: everything.
        ContactsSnapshotRestorer.restore(targetContext(), snapshot, RestoreOptions.all(), (m, c, t) -> {});
        assertTrue(targetHasPhone());
        assertTrue("Second restore with a broader selection should now materialize photos",
                targetHasMime("vnd.android.cursor.item/photo"));
        assertTrue(targetHasMime("vnd.android.cursor.item/group_membership"));
        assertTrue(targetHasMime(UNKNOWN_MIME));

        String canonicalAfterSecondRestore = NormalizedJsonExporter.exportCanonical(snapshot);
        assertEquals("Snapshot must still be unchanged after a second restore with a different selection",
                canonicalBefore, canonicalAfterSecondRestore);
    }

    // ============================================================
    // Scenario 8: a nameless "shadow" source Contact that only ever carried
    // provider-specific data (e.g. a messaging app's internal RawContact,
    // with no name/phone/email of its own) must NOT become an empty,
    // duplicate-looking contact when Additional data/Account info aren't
    // selected — it should simply not be created at all, since there is
    // nothing left to restore for it.
    // ============================================================
    @Test
    public void scenario8_shadowContactWithNothingSelectedIsNotCreatedEmpty() throws Exception {
        String realContact = insertRawContact();
        insertRow(realContact, "vnd.android.cursor.item/name", "Real Person");
        insertRow(realContact, "vnd.android.cursor.item/phone_v2", "+1-555-9001", "1");

        // A separate source Contact (no name/phone to aggregate on, so Android
        // won't merge it with the real one) carrying only provider-specific
        // data — mirrors what a messaging app's internal bookkeeping entry
        // looks like: no name, no phone, just its own proprietary field.
        String shadowContact = insertRawContact();
        insertRow(shadowContact, UNKNOWN_MIME, "telegram-internal-id-12345");
        Thread.sleep(300);

        AndroidContactsSnapshot snapshot = ContactsSnapshotReader.read(targetContext());
        assertEquals("Should be two distinct source Contacts", 2, snapshot.getContactCount());

        cleanupContacts();

        RestoreResult result = ContactsSnapshotRestorer.restore(targetContext(), snapshot,
                RestoreOptions.of(RestoreCategory.CONTACT_INFO), (m, c, t) -> {});

        assertEquals("Only the real contact should be created", 1, result.contactsCreated);
        assertEquals("The shadow contact should be skipped, not created empty", 1, result.emptyContactsSkipped);

        Cursor phoneCursor = resolver.query(ContactsContract.Data.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER},
                ContactsContract.Data.MIMETYPE + "=?",
                new String[]{"vnd.android.cursor.item/phone_v2"}, null);
        assertNotNull(phoneCursor);
        try {
            assertTrue("The real contact's phone should be restored", phoneCursor.moveToFirst());
            assertEquals("+1-555-9001", phoneCursor.getString(0));
        } finally {
            phoneCursor.close();
        }

        Cursor c = resolver.query(ContactsContract.RawContacts.CONTENT_URI,
                new String[]{ContactsContract.RawContacts._ID},
                ContactsContract.RawContacts.DELETED + "=0", null, null);
        assertNotNull(c);
        try {
            assertEquals("Exactly one RawContact should exist on the device — no empty duplicate",
                    1, c.getCount());
        } finally {
            c.close();
        }
    }

    // ============================================================
    // Scenario 9: a shadow RawContact whose only "content" is a raw-contact-
    // level cached display name (no actual name/phone/email Data row of its
    // own) — exactly how Telegram/WhatsApp-style shadow entries look — must
    // not be resurrected as a name-only contact once its one real data row
    // (a provider-specific field) is filtered out. All identifiers here are
    // synthetic/fake, not real user data.
    // ============================================================
    @Test
    public void scenario9_shadowContactWithOnlyCachedDisplayNameIsNotResurrected() throws Exception {
        String realContact = insertRawContact();
        insertRow(realContact, "vnd.android.cursor.item/name", "Fake Real Person");
        insertRow(realContact, "vnd.android.cursor.item/phone_v2", "+1-555-0000", "1");

        // Simulates a messaging app's shadow RawContact: a cached display
        // name at the RawContacts level, an account tying it to that app,
        // but zero real Data rows besides its own proprietary field.
        String shadowContact = insertShadowRawContact(
                "fake-shadow-account@example.invalid", "com.example.fakemessagingapp", "Fake Real Person");
        insertRow(shadowContact, UNKNOWN_MIME, "fake-messaging-app-internal-id-999");
        Thread.sleep(300);

        AndroidContactsSnapshot snapshot = ContactsSnapshotReader.read(targetContext());
        assertEquals("Should be two distinct source Contacts", 2, snapshot.getContactCount());

        cleanupContacts();

        // Contact info selected, Additional data and Account info NOT selected —
        // the shadow's one real row (provider-specific) is filtered out, and its
        // account is stripped, leaving only its cached display name behind.
        RestoreResult result = ContactsSnapshotRestorer.restore(targetContext(), snapshot,
                RestoreOptions.of(RestoreCategory.CONTACT_INFO), (m, c, t) -> {});

        assertEquals("Only the real contact should be created", 1, result.contactsCreated);
        assertEquals("The shadow contact must not be resurrected via its cached display name alone",
                1, result.emptyContactsSkipped);

        Cursor c = resolver.query(ContactsContract.RawContacts.CONTENT_URI,
                new String[]{ContactsContract.RawContacts._ID},
                ContactsContract.RawContacts.DELETED + "=0", null, null);
        assertNotNull(c);
        try {
            assertEquals("Exactly one RawContact should exist — no name-only duplicate",
                    1, c.getCount());
        } finally {
            c.close();
        }
    }

    // ============================================================
    // Scenario 7: data present in the backup that cannot be mapped onto the
    // target provider (here: a group_membership referencing a group ID that
    // isn't part of the snapshot's captured Groups, e.g. because the source
    // group's account no longer exists) must be clearly reported, never
    // silently dropped. This is the deterministic, reproducible case of
    // "provider-specific data present in the source but not restorable" —
    // forcing an actual ContentProvider insert exception is not reliable
    // across devices/API levels, so this exercises the same reporting path
    // (RestoreResult.groupMembershipsUnrestored) via a real, common cause:
    // an orphaned group reference.
    // ============================================================
    @Test
    public void scenario7_unrestorableProviderDataIsReportedNotDropped() throws Exception {
        String r1 = insertRawContact();
        insertRow(r1, "vnd.android.cursor.item/name", "Orphan Group Reference");
        // Reference a group ID that was never captured in the snapshot's
        // Groups list (simulating a group whose account is gone by the time
        // of restore).
        insertGroupMembership(r1, 999999999L);
        Thread.sleep(300);

        AndroidContactsSnapshot snapshot = ContactsSnapshotReader.read(targetContext());
        AndroidContactSnapshot.DataRowSnapshot membershipRow = null;
        for (AndroidContactSnapshot c : snapshot.contacts) {
            for (AndroidContactSnapshot.RawContactSnapshot rc : c.rawContacts) {
                for (AndroidContactSnapshot.DataRowSnapshot row : rc.dataRows) {
                    if ("vnd.android.cursor.item/group_membership".equals(row.mimeType)) membershipRow = row;
                }
            }
        }
        assertNotNull("The unmappable group_membership row must still be captured in the backup", membershipRow);
        assertEquals("999999999", membershipRow.data1);

        cleanupContacts();

        RestoreResult result = ContactsSnapshotRestorer.restore(targetContext(), snapshot,
                RestoreOptions.of(RestoreCategory.CONTACT_INFO, RestoreCategory.GROUPS), (m, c, t) -> {});

        assertEquals("Unmappable group membership must be reported, not silently dropped",
                1, result.groupMembershipsUnrestored);
        assertTrue("Unrestorable data must produce a warning the user can see", result.hasWarnings());
        assertFalse("The unrestorable membership must not silently appear on the target",
                targetHasMime("vnd.android.cursor.item/group_membership"));
    }

    private void linkAsOneContact(String rawId1, String rawId2) {
        ContentValues values = new ContentValues();
        values.put(ContactsContract.AggregationExceptions.TYPE, ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER);
        values.put(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, Long.parseLong(rawId1));
        values.put(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, Long.parseLong(rawId2));
        resolver.update(ContactsContract.AggregationExceptions.CONTENT_URI, values, null, null);
    }

    // ============================================================
    // Scenario 10: reproduces the real-world duplicate-contact bug reported
    // against a device where a messaging app (e.g. Telegram) syncs its own
    // RawContact, already aggregation-linked with the phone-native RawContact
    // for the same person. Restore used to consolidate both into a single
    // new RawContact carrying the union of their rows — which meant the
    // messaging app's sync adapter could never recognize a RawContact of its
    // own (it identifies "its" RawContact by account+source-id) after
    // restore, and would create a fresh one on its next sync instead of
    // updating in place — the actual cause of contacts doubling after
    // restore even though the app's own data was correct.
    //
    // This asserts the fix directly: each source RawContact is recreated as
    // its own target RawContact, keeping its own account/type/source-id, and
    // the two are linked via AggregationExceptions so they still present as
    // one Contact.
    // ============================================================
    @Test
    public void scenario10_multiAccountRawContactsPreservedAndLinkedNotMerged() throws Exception {
        String local = insertRawContact();
        insertRow(local, "vnd.android.cursor.item/name", "Multi Source Person");
        insertRow(local, "vnd.android.cursor.item/phone_v2", "+1-555-8001", "1");

        String messenger = insertRawContact("fake-messenger-account@example.invalid",
                "com.example.fakemessenger", "fake-messenger-source-id-42");
        insertRow(messenger, "vnd.android.cursor.item/name", "Multi Source Person");
        insertRow(messenger, UNKNOWN_MIME, "messenger-marker-row");

        // Mirrors the platform having already aggregation-linked these two on
        // the source device (whether organically or via a manual merge).
        linkAsOneContact(local, messenger);
        Thread.sleep(300);

        AndroidContactsSnapshot snapshot = ContactsSnapshotReader.read(targetContext());
        AndroidContactSnapshot sourceContact = null;
        for (AndroidContactSnapshot c : snapshot.contacts) {
            if (c.rawContacts.size() == 2) sourceContact = c;
        }
        assertNotNull("The two RawContacts should have been captured as one source Contact", sourceContact);

        cleanupContacts();

        RestoreResult result = ContactsSnapshotRestorer.restore(targetContext(), snapshot,
                RestoreOptions.of(RestoreCategory.CONTACT_INFO, RestoreCategory.ADDITIONAL_DATA, RestoreCategory.ACCOUNT_INFO),
                (m, c, t) -> {});

        assertEquals("No error restoring the multi-account contact", 0, result.errors.size());
        Thread.sleep(500); // Let the provider's own aggregation settle before querying it

        // Scoped lookups by distinguishing marks, not an unfiltered table
        // scan — the emulator/device's RawContacts table normally holds many
        // thousands of unrelated rows (demo/seeded contacts, prior test
        // leftovers, etc.) that a blanket query would also pick up.
        Long localRawId = rawContactIdForData("vnd.android.cursor.item/phone_v2", "+1-555-8001");
        Long messengerRawId = rawContactIdForAccount("com.example.fakemessenger", "fake-messenger-source-id-42");

        assertNotNull("Restored local RawContact should be found by its phone number", localRawId);
        assertNotNull("Restored messenger RawContact should be found by its own preserved account/source-id "
                        + "(this is what lets its sync adapter recognize it as its own on next sync, "
                        + "instead of creating a duplicate)",
                messengerRawId);
        assertFalse("The two must remain distinct RawContacts, not merged into one",
                localRawId.equals(messengerRawId));

        assertEquals("The two RawContacts must be linked as exactly one target Contact",
                contactIdOf(localRawId), contactIdOf(messengerRawId));
        assertEquals("Exactly one extra RawContact was linked (not merged) for this multi-source contact",
                1, result.linkedRawContacts);
    }

    private Long rawContactIdForData(String mimeType, String data1) {
        Cursor c = resolver.query(ContactsContract.Data.CONTENT_URI,
                new String[]{ContactsContract.Data.RAW_CONTACT_ID},
                ContactsContract.Data.MIMETYPE + "=? AND " + ContactsContract.Data.DATA1 + "=?",
                new String[]{mimeType, data1}, null);
        if (c == null) return null;
        try { return c.moveToFirst() ? c.getLong(0) : null; } finally { c.close(); }
    }

    private Long rawContactIdForAccount(String accountType, String sourceId) {
        Cursor c = resolver.query(ContactsContract.RawContacts.CONTENT_URI,
                new String[]{ContactsContract.RawContacts._ID},
                ContactsContract.RawContacts.ACCOUNT_TYPE + "=? AND " + ContactsContract.RawContacts.SOURCE_ID + "=? AND "
                        + ContactsContract.RawContacts.DELETED + "=0",
                new String[]{accountType, sourceId}, null);
        if (c == null) return null;
        try { return c.moveToFirst() ? c.getLong(0) : null; } finally { c.close(); }
    }

    private Long contactIdOf(long rawContactId) {
        Cursor c = resolver.query(ContactsContract.RawContacts.CONTENT_URI,
                new String[]{ContactsContract.RawContacts.CONTACT_ID},
                ContactsContract.RawContacts._ID + "=?", new String[]{String.valueOf(rawContactId)}, null);
        if (c == null) return null;
        try { return c.moveToFirst() ? c.getLong(0) : null; } finally { c.close(); }
    }

    // ============================================================
    // Scenario 11: a standalone RawContact from a synced/messaging-app
    // account whose ONLY real content is a bare name (no phone, email, or
    // any other field of its own) — e.g. a messaging app caching a contact's
    // name without ever sharing a phone number — must be excluded when
    // ADDITIONAL_DATA isn't selected, even though CONTACT_INFO is selected
    // and the name row is genuinely captured (not synthesized). Reproduces:
    // "I unselected Additional data but I still got a Telegram contact back
    // with just a name and last name, no number."
    // ============================================================
    @Test
    public void scenario11_otherTypeRawContactRestoredWholeOrNotAtAll() throws Exception {
        // Mirrors a real messaging-app-linked RawContact: its own name row
        // AND its own phone number, PLUS a provider-specific marker row of
        // its own (e.g. Telegram's profile/call markers). Because it carries
        // provider-specific data, it's an "other type" entry — ADDITIONAL_DATA
        // must decide ALL of it (including its name and phone), not just the
        // marker row, so a deselection never leaves behind a partial,
        // not-the-original version of it.
        String messengerOnly = insertRawContact("fake-messenger-account@example.invalid",
                "com.example.fakemessenger", "fake-messenger-source-id-77");
        insertRow(messengerOnly, "vnd.android.cursor.item/name", "Other Type Person");
        insertRow(messengerOnly, "vnd.android.cursor.item/phone_v2", "+1-555-8801", "1");
        insertRow(messengerOnly, UNKNOWN_MIME, "messenger-marker-row");
        Thread.sleep(300);

        AndroidContactsSnapshot snapshot = ContactsSnapshotReader.read(targetContext());
        cleanupContacts();

        RestoreResult result = ContactsSnapshotRestorer.restore(targetContext(), snapshot,
                RestoreOptions.of(RestoreCategory.CONTACT_INFO, RestoreCategory.PHOTOS,
                        RestoreCategory.GROUPS, RestoreCategory.ADDITIONAL_DATA),
                (m, c, t) -> {});
        assertEquals("The other-type entry should be created whole when Additional data is selected",
                1, result.contactsCreated);
        assertTrue("...including its own phone number", targetHasPhoneNumber("+1-555-8801"));
        assertTrue("...and its own provider-specific marker row", targetHasMime(UNKNOWN_MIME));

        cleanupContacts();

        result = ContactsSnapshotRestorer.restore(targetContext(), snapshot,
                RestoreOptions.of(RestoreCategory.CONTACT_INFO, RestoreCategory.PHOTOS, RestoreCategory.GROUPS),
                (m, c, t) -> {});

        assertEquals("The other-type entry must NOT be created at all when Additional data is deselected — "
                        + "not even partially, with just its name or phone left behind",
                0, result.contactsCreated);
        assertFalse("No trace of its phone number should be restored", targetHasPhoneNumber("+1-555-8801"));
        assertFalse("No trace of its marker row should be restored", targetHasMime(UNKNOWN_MIME));
        assertEquals("It must be reported as intentionally skipped, not silently dropped",
                1, result.emptyContactsSkipped);
    }

    // ============================================================
    // Scenario 12: a RawContact that carries NO provider-specific data of
    // its own — just an ordinary name — is a "normal" vCard-style contact
    // regardless of which account it came from, and stays governed by
    // CONTACT_INFO as always. Covers both a locally-entered contact and a
    // synced-account contact that genuinely has nothing beyond a name.
    // ============================================================
    @Test
    public void scenario12_bareNameWithNoProviderDataUnaffectedByAdditionalDataToggle() throws Exception {
        String local = insertRawContact();
        insertRow(local, "vnd.android.cursor.item/name", "Just A Name Locally");

        String syncedNoExtras = insertRawContact("plain-account@example.invalid", "com.example.plainsync", "plain-source-1");
        insertRow(syncedNoExtras, "vnd.android.cursor.item/name", "Just A Name Synced");
        Thread.sleep(300);

        AndroidContactsSnapshot snapshot = ContactsSnapshotReader.read(targetContext());
        cleanupContacts();

        RestoreResult result = ContactsSnapshotRestorer.restore(targetContext(), snapshot,
                RestoreOptions.of(RestoreCategory.CONTACT_INFO, RestoreCategory.PHOTOS, RestoreCategory.GROUPS),
                (m, c, t) -> {});

        assertEquals("Both bare-name contacts (local and synced) must still be restored "
                        + "when Additional data is deselected, since neither carries any provider-specific data",
                2, result.contactsCreated);
    }

    // ============================================================
    // Scenario 13: selecting Additional data for an "other type" entry must
    // preserve its own account/source-id too, even when Account info is
    // NOT selected — restoring such an entry's data without its account
    // would leave it unrecognizable to the app that owns it (e.g. a
    // messaging app), which would just create another copy on its next
    // sync anyway. Account info alone still governs ordinary contacts.
    // ============================================================
    @Test
    public void scenario13_additionalDataAloneCarriesAccountForOtherTypeEntries() throws Exception {
        String messengerOnly = insertRawContact("fake-messenger-account@example.invalid",
                "com.example.fakemessenger", "fake-messenger-source-id-99");
        insertRow(messengerOnly, "vnd.android.cursor.item/name", "Coupled Account Person");
        insertRow(messengerOnly, UNKNOWN_MIME, "messenger-marker-row");

        String googleContact = insertRawContact("real-google-account@example.invalid", "com.google", null);
        insertRow(googleContact, "vnd.android.cursor.item/name", "Ordinary Google Person");
        insertRow(googleContact, "vnd.android.cursor.item/phone_v2", "+1-555-9101", "1");
        Thread.sleep(300);

        AndroidContactsSnapshot snapshot = ContactsSnapshotReader.read(targetContext());
        cleanupContacts();

        // Additional data selected, Account info NOT selected.
        ContactsSnapshotRestorer.restore(targetContext(), snapshot,
                RestoreOptions.of(RestoreCategory.CONTACT_INFO, RestoreCategory.PHOTOS,
                        RestoreCategory.GROUPS, RestoreCategory.ADDITIONAL_DATA),
                (m, c, t) -> {});

        Long messengerRawId = rawContactIdForAccount("com.example.fakemessenger", "fake-messenger-source-id-99");
        assertNotNull("The other-type entry's own account must be preserved because Additional data was selected, "
                        + "even though Account info was not",
                messengerRawId);

        Cursor c = resolver.query(ContactsContract.RawContacts.CONTENT_URI,
                new String[]{ContactsContract.RawContacts._ID},
                ContactsContract.RawContacts.ACCOUNT_TYPE + "=?", new String[]{"com.google"}, null);
        assertNotNull(c);
        try {
            assertFalse("The ordinary Google contact must have been restored as local, not under its Google account",
                    c.moveToFirst());
        } finally {
            c.close();
        }
        assertTrue("The ordinary Google contact's own data should still be restored (just as local)",
                targetHasPhoneNumber("+1-555-9101"));
    }
}
