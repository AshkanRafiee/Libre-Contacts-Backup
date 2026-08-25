package com.ashkanrafiee.librecontactsbackup;

import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.ashkanrafiee.librecontactsbackup.archive.BackupArchiveReader;
import com.ashkanrafiee.librecontactsbackup.archive.BackupArchiveWriter;
import com.ashkanrafiee.librecontactsbackup.archive.ContactsSnapshotRestorer;
import com.ashkanrafiee.librecontactsbackup.export.NormalizedJsonExporter;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactsSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.ContactsSnapshotReader;
import com.ashkanrafiee.librecontactsbackup.snapshot.RestoreResult;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * End-to-end round-trip test that runs entirely on the device.
 *
 * Flow:
 * 1. Create comprehensive edge-case contacts directly via ContentProvider
 * 2. Read snapshot from provider
 * 3. Write .lcb archive (ZIP)
 * 4. Read archive back into snapshot
 * 5. Verify snapshot matches (canonical JSON comparison)
 * 6. Clear all contacts from provider
 * 7. Restore snapshot to provider
 * 8. Read snapshot from provider again
 * 9. Verify restored snapshot matches original
 *
 * This is the definitive test of lossless backup/restore.
 */
@RunWith(AndroidJUnit4.class)
public class EndToEndRoundTripTest {

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
        try {
            resolver.delete(ContactsContract.RawContacts.CONTENT_URI, null, null);
        } catch (Exception e) { /* ignore */ }
        // Also purge soft-deleted contacts by marking them deleted and re-deleting
        try {
            resolver.delete(ContactsContract.RawContacts.CONTENT_URI,
                    ContactsContract.RawContacts.DELETED + "=1", null);
        } catch (Exception e) { /* ignore */ }
        // Groups are a separate table and are not cascade-deleted with raw
        // contacts; wipe them too so tests stay hermetic and group-mapping
        // tests genuinely exercise target-side group creation.
        try {
            resolver.delete(ContactsContract.Groups.CONTENT_URI, null, null);
        } catch (Exception e) { /* ignore */ }
    }

    private String insertRawContact(String accountName, String accountType) throws Exception {
        ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI);
        if (accountName != null) {
            b.withValue(ContactsContract.RawContacts.ACCOUNT_NAME, accountName);
            b.withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, accountType);
        } else {
            b.withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null);
            b.withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null);
        }
        android.content.ContentProviderResult[] results = resolver.applyBatch(ContactsContract.AUTHORITY,
                new ArrayList<>(java.util.Collections.singletonList(b.build())));
        return results[0].uri.getLastPathSegment();
    }

    private void insertDataRow(String rawId, String mimeType, String... values) throws Exception {
        ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                .withValue(ContactsContract.Data.MIMETYPE, mimeType);
        for (int i = 0; i < values.length && i < 14; i++) {
            if (values[i] != null) {
                b.withValue("data" + (i + 1), values[i]);
            }
        }
        resolver.applyBatch(ContactsContract.AUTHORITY,
                new ArrayList<>(java.util.Collections.singletonList(b.build())));
    }

    private void insertDataRowWithType(String rawId, String mimeType, int type, String label, String... values) throws Exception {
        ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                .withValue(ContactsContract.Data.MIMETYPE, mimeType)
                .withValue("data2", type);
        if (label != null) b.withValue("data3", label);
        for (int i = 0; i < values.length && i < 14; i++) {
            if (values[i] != null && i != 1) {
                b.withValue("data" + (i + 1), values[i]);
            }
        }
        resolver.applyBatch(ContactsContract.AUTHORITY,
                new ArrayList<>(java.util.Collections.singletonList(b.build())));
    }

    private static Context targetContext() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    /**
     * Forces two raw contacts to aggregate into the same source Contact,
     * regardless of the platform's fuzzy name/email/phone matching
     * heuristics, so grouping-boundary tests are deterministic.
     */
    private void keepTogether(String rawId1, String rawId2) throws Exception {
        ContentValues values = new ContentValues();
        values.put(ContactsContract.AggregationExceptions.TYPE, ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER);
        values.put(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, Long.parseLong(rawId1));
        values.put(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, Long.parseLong(rawId2));
        resolver.update(ContactsContract.AggregationExceptions.CONTENT_URI, values, null, null);
    }

    /**
     * Forces two raw contacts to remain separate source Contacts,
     * regardless of the platform's fuzzy matching heuristics (e.g. two
     * raw contacts that happen to share the same display name), so
     * grouping-boundary tests are deterministic.
     */
    private void keepSeparate(String rawId1, String rawId2) throws Exception {
        ContentValues values = new ContentValues();
        values.put(ContactsContract.AggregationExceptions.TYPE, ContactsContract.AggregationExceptions.TYPE_KEEP_SEPARATE);
        values.put(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, Long.parseLong(rawId1));
        values.put(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, Long.parseLong(rawId2));
        resolver.update(ContactsContract.AggregationExceptions.CONTENT_URI, values, null, null);
    }

    /**
     * Backs up the given snapshot, clears the provider, restores it, and
     * returns what the provider actually contains afterward. Shared by all
     * round-trip tests in this class.
     */
    private AndroidContactsSnapshot backupClearRestore(AndroidContactsSnapshot original) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BackupArchiveWriter.writeArchive(targetContext(), original, baos);

        BackupArchiveReader.ArchiveData archiveData;
        try (InputStream is = new ByteArrayInputStream(baos.toByteArray())) {
            archiveData = BackupArchiveReader.readArchive(is);
        }
        assertTrue("Archive should be valid and lossless", archiveData.isLossless());
        assertTrue("Checksum should be valid", archiveData.checksumValid);

        cleanupContacts();
        Thread.sleep(300);

        ContactsSnapshotRestorer.RestoreProgress progress = (msg, cur, total) -> {};
        ContactsSnapshotRestorer.restoreExact(targetContext(), archiveData.snapshot, progress);
        Thread.sleep(500);

        return ContactsSnapshotReader.read(targetContext());
    }

    // ============================================================
    // Canonical, order-independent, ID-independent comparison helpers.
    //
    // These mirror the consolidation ContactsSnapshotRestorer performs
    // (merge all RawContacts belonging to one source Contact, dedupe
    // identical rows, synthesize a name row if none is present) so the
    // "expected" content can be compared against what restore actually
    // produced without assuming provider IDs or read-back ordering.
    //
    // group_membership rows are intentionally excluded: their DATA1 group
    // ID is remapped on restore (like account remapping, this is an
    // allowed transformation), so it never round-trips byte-for-byte.
    //
    // photo rows are compared by presence/type only here, not by exact
    // DATA15 bytes: the Contacts provider itself re-encodes photo bytes
    // into its own thumbnail representation on every insert (source AND
    // restore), so two independent inserts of logically-identical bytes
    // are not guaranteed to produce byte-identical thumbnails. Exact photo
    // byte fidelity is verified separately (see testPhotoBinaryExactBytesRoundTrip)
    // by comparing what the SOURCE snapshot itself captured (post-provider-
    // processing) against what restore produced, not the raw input bytes.
    //
    // name rows drop GIVEN_NAME (data2) and the FULL_NAME_STYLE/
    // PHONETIC_NAME_STYLE columns (data10/data11) from the comparison: the
    // provider computes these itself from DISPLAY_NAME on insert (e.g. a
    // single-token display name like a phone number gets echoed into
    // GIVEN_NAME), so they are not guaranteed to reproduce whatever was
    // read from the source. Every other structured-name field (family
    // name, prefix, middle name, suffix, phonetic parts) is still compared
    // exactly, and dedicated field-level tests in LosslessBackupTest verify
    // GIVEN_NAME round-trips correctly whenever it was explicitly supplied.
    // ============================================================

    private static final String MIME_NAME = "vnd.android.cursor.item/name";
    private static final String MIME_GROUP_MEMBERSHIP = "vnd.android.cursor.item/group_membership";
    private static final String MIME_PHOTO = "vnd.android.cursor.item/photo";

    private static String comparisonKey(AndroidContactSnapshot.DataRowSnapshot row) {
        if (MIME_PHOTO.equals(row.mimeType)) return MIME_PHOTO + "|present";
        if (MIME_NAME.equals(row.mimeType)) {
            return String.join("|", MIME_NAME,
                    nvl(row.data1), nvl(row.data3), nvl(row.data4), nvl(row.data5),
                    nvl(row.data6), nvl(row.data7), nvl(row.data8), nvl(row.data9));
        }
        return row.canonicalKey();
    }

    private static String nvl(String s) { return s != null ? s : ""; }

    /**
     * Computes the canonical, deduplicated set of data-row content this
     * source Contact is expected to produce after restore.
     */
    private static Set<String> expectedCanonicalRowSet(AndroidContactSnapshot contact) {
        ArrayList<AndroidContactSnapshot.DataRowSnapshot> merged = new ArrayList<>();
        for (AndroidContactSnapshot.RawContactSnapshot rc : contact.rawContacts) {
            for (AndroidContactSnapshot.DataRowSnapshot row : rc.dataRows) {
                boolean duplicate = false;
                for (AndroidContactSnapshot.DataRowSnapshot existing : merged) {
                    if (row.canonicalKey().equals(existing.canonicalKey())) { duplicate = true; break; }
                }
                if (!duplicate) merged.add(row);
            }
        }

        boolean hasName = false;
        for (AndroidContactSnapshot.DataRowSnapshot row : merged) {
            if (MIME_NAME.equals(row.mimeType)) { hasName = true; break; }
        }
        if (!hasName) {
            String nameToUse = null;
            for (AndroidContactSnapshot.RawContactSnapshot rc : contact.rawContacts) {
                if (rc.displayName != null && !rc.displayName.isEmpty()) { nameToUse = rc.displayName; break; }
            }
            if (nameToUse == null && contact.displayName != null && !contact.displayName.isEmpty()) {
                nameToUse = contact.displayName;
            }
            if (nameToUse != null) {
                AndroidContactSnapshot.DataRowSnapshot synthetic = new AndroidContactSnapshot.DataRowSnapshot(MIME_NAME);
                synthetic.data1 = nameToUse;
                merged.add(synthetic);
            }
        }

        Set<String> keys = new HashSet<>();
        for (AndroidContactSnapshot.DataRowSnapshot row : merged) {
            if (MIME_GROUP_MEMBERSHIP.equals(row.mimeType)) continue;
            keys.add(comparisonKey(row));
        }
        return keys;
    }

    /**
     * Computes the canonical set of data-row content actually present for a
     * restored (target-side) Contact, for comparison against
     * {@link #expectedCanonicalRowSet}.
     */
    private static Set<String> actualCanonicalRowSet(AndroidContactSnapshot contact) {
        Set<String> keys = new HashSet<>();
        for (AndroidContactSnapshot.RawContactSnapshot rc : contact.rawContacts) {
            for (AndroidContactSnapshot.DataRowSnapshot row : rc.dataRows) {
                if (MIME_GROUP_MEMBERSHIP.equals(row.mimeType)) continue;
                keys.add(comparisonKey(row));
            }
        }
        return keys;
    }

    /**
     * Asserts that the restored snapshot is an exact, order-independent
     * bijection of the source snapshot: same number of Contacts, and every
     * source Contact's consolidated/deduplicated content is present in
     * exactly one restored Contact (no data loss, no unwanted merging or
     * splitting of distinct source Contacts).
     */
    private static void assertLosslessConsolidation(AndroidContactsSnapshot source, AndroidContactsSnapshot restored) {
        assertEquals("Restored Contact count must equal source Contact count (no merging/splitting of distinct source Contacts)",
                source.getContactCount(), restored.getContactCount());

        List<Set<String>> expectedSets = new ArrayList<>();
        for (AndroidContactSnapshot c : source.contacts) expectedSets.add(expectedCanonicalRowSet(c));

        List<Set<String>> actualSets = new ArrayList<>();
        for (AndroidContactSnapshot c : restored.contacts) actualSets.add(actualCanonicalRowSet(c));

        List<Set<String>> unmatched = new ArrayList<>(actualSets);
        for (Set<String> expected : expectedSets) {
            boolean found = unmatched.remove(expected);
            if (!found) {
                Set<String> closest = null;
                int closestDiff = Integer.MAX_VALUE;
                for (Set<String> candidate : unmatched) {
                    Set<String> sym = new HashSet<>(expected);
                    sym.addAll(candidate);
                    Set<String> intersect = new HashSet<>(expected);
                    intersect.retainAll(candidate);
                    int diff = sym.size() - intersect.size();
                    if (diff < closestDiff) { closestDiff = diff; closest = candidate; }
                }
                StringBuilder msg = new StringBuilder("No restored contact matched expected content exactly.\n");
                if (closest != null) {
                    Set<String> onlyExpected = new HashSet<>(expected);
                    onlyExpected.removeAll(closest);
                    Set<String> onlyActual = new HashSet<>(closest);
                    onlyActual.removeAll(expected);
                    msg.append("Closest candidate — only in expected: ").append(onlyExpected)
                            .append("\nonly in actual: ").append(onlyActual);
                } else {
                    msg.append("No unmatched actual contacts remained. Expected: ").append(expected);
                }
                fail(msg.toString());
            }
        }
        assertTrue("Every restored contact should have matched a source contact", unmatched.isEmpty());
    }

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** Inserts a Data row with up to 14 string fields and an optional DATA15 binary blob. */
    private void insertRowWithBinary(String rawId, String mimeType, String[] textValues, byte[] binary) throws Exception {
        ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                .withValue(ContactsContract.Data.MIMETYPE, mimeType);
        for (int i = 0; i < textValues.length && i < 14; i++) {
            if (textValues[i] != null) b.withValue("data" + (i + 1), textValues[i]);
        }
        if (binary != null) b.withValue(ContactsContract.Data.DATA15, binary);
        resolver.applyBatch(ContactsContract.AUTHORITY, new ArrayList<>(java.util.Collections.singletonList(b.build())));
    }

    private static AndroidContactSnapshot.DataRowSnapshot findRow(AndroidContactsSnapshot snapshot, String mimeType) {
        for (AndroidContactSnapshot c : snapshot.contacts) {
            for (AndroidContactSnapshot.RawContactSnapshot rc : c.rawContacts) {
                for (AndroidContactSnapshot.DataRowSnapshot row : rc.dataRows) {
                    if (mimeType.equals(row.mimeType)) return row;
                }
            }
        }
        return null;
    }

    /** Minimal valid 1x1 transparent PNG, used as deterministic photo test data. */
    private static byte[] samplePhotoBytes() {
        return new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
                0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4,
                (byte) 0x89, 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54, 0x78, (byte) 0x9C, 0x63, 0x00, 0x01, 0x00,
                0x00, 0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, (byte) 0xB4, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
                (byte) 0xAE, 0x42, 0x60, (byte) 0x82
        };
    }

    /**
     * Creates a comprehensive edge-case dataset.
     */
    private void createTestData() throws Exception {
        // 1. Full structured name with phonetic
        String r1 = insertRawContact(null, null);
        insertDataRow(r1, "vnd.android.cursor.item/name",
                "Dr. John Michael Smith Jr.", "John", "Smith", "Dr.", "Michael", "Jr.",
                "Jon", "Mikhayl", "Smyth");

        // 2. Multiple phones with types
        insertDataRowWithType(r1, "vnd.android.cursor.item/phone_v2", 1, null, "+1-555-0101");
        insertDataRowWithType(r1, "vnd.android.cursor.item/phone_v2", 2, null, "+1-555-0102");
        insertDataRowWithType(r1, "vnd.android.cursor.item/phone_v2", 3, null, "+1-555-0103");
        insertDataRowWithType(r1, "vnd.android.cursor.item/phone_v2", 0, "My Custom", "+1-555-0104");

        // 3. Multiple emails
        insertDataRowWithType(r1, "vnd.android.cursor.item/email_v2", 1, null, "john@example.com");
        insertDataRowWithType(r1, "vnd.android.cursor.item/email_v2", 2, null, "john.work@example.com");

        // 4. Address
        insertDataRowWithType(r1, "vnd.android.cursor.item/postal-address_v2", 1, null,
                null, null, null, "PO Box 123", "Downtown", "123 Main St", "Springfield", "IL", "62704", "USA");

        // 5. Organization
        insertDataRow(r1, "vnd.android.cursor.item/organization", "Acme Corp", null, null, "VP of Engineering", "Platform");

        // 6. Nickname, note, websites, relations, IMs
        insertDataRow(r1, "vnd.android.cursor.item/nickname", "Johnny");
        insertDataRow(r1, "vnd.android.cursor.item/note", "Important note\nwith multiple lines\nand special chars: <>&\"'");
        insertDataRowWithType(r1, "vnd.android.cursor.item/website", 1, null, "https://example.com");
        insertDataRowWithType(r1, "vnd.android.cursor.item/website", 2, null, "https://blog.example.com");
        insertDataRowWithType(r1, "vnd.android.cursor.item/relation", 12, null, "Jane Doe");
        insertDataRowWithType(r1, "vnd.android.cursor.item/relation", 0, "Mentor", "Bob Smith");
        insertDataRow(r1, "vnd.android.cursor.item/im", "user@jabber.org", null, null, null, "6");
        insertDataRow(r1, "vnd.android.cursor.item/im", "skype.user", null, null, null, "3");

        // 7. Events
        insertDataRowWithType(r1, "vnd.android.cursor.item/contact_event", 1, null, "1990-05-15");
        insertDataRowWithType(r1, "vnd.android.cursor.item/contact_event", 2, null, "2020-06-20");
        insertDataRowWithType(r1, "vnd.android.cursor.item/contact_event", 0, "Holiday Party", "2024-12-25");

        // 8. SIP address
        insertDataRowWithType(r1, "vnd.android.cursor.item/sip-address", 1, null, "sip:user@example.com");

        // 9. Unicode contact
        String r2 = insertRawContact(null, null);
        insertDataRow(r2, "vnd.android.cursor.item/name", "日本太郎 田中", "太郎", "田中");
        insertDataRowWithType(r2, "vnd.android.cursor.item/phone_v2", 1, null, "+81-90-1234-5678");

        // 10. Arabic contact
        String r3 = insertRawContact(null, null);
        insertDataRow(r3, "vnd.android.cursor.item/name", "محمد بن سلمان");
        insertDataRowWithType(r3, "vnd.android.cursor.item/phone_v2", 1, null, "+966-50-000-0000");

        // 11. Cyrillic contact
        String r4 = insertRawContact(null, null);
        insertDataRow(r4, "vnd.android.cursor.item/name", "Владимир Путин");
        insertDataRowWithType(r4, "vnd.android.cursor.item/phone_v2", 1, null, "+7-495-123-4567");

        // 12. Nameless contact (phone only)
        String r5 = insertRawContact(null, null);
        insertDataRowWithType(r5, "vnd.android.cursor.item/phone_v2", 1, null, "+1-555-000-0001");

        // 13. Multi-account aggregated contact
        String r6 = insertRawContact("user@gmail.com", "com.google");
        insertDataRow(r6, "vnd.android.cursor.item/name", "Multi Account", "Multi", "Account");
        insertDataRowWithType(r6, "vnd.android.cursor.item/phone_v2", 1, null, "+1-555-1001");
        insertDataRowWithType(r6, "vnd.android.cursor.item/email_v2", 2, null, "multi@gmail.com");

        String r7 = insertRawContact(null, "com.android.contacts");
        insertDataRow(r7, "vnd.android.cursor.item/name", "Multi Account", "Multi", "Account");
        insertDataRowWithType(r7, "vnd.android.cursor.item/phone_v2", 2, null, "+1-555-1002");
        insertDataRowWithType(r7, "vnd.android.cursor.item/email_v2", 1, null, "local@personal.com");

        // 14. Contact with long note
        String r8 = insertRawContact(null, null);
        StringBuilder longNote = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            longNote.append("Line ").append(i).append(": The quick brown fox jumps over the lazy dog. ");
        }
        insertDataRow(r8, "vnd.android.cursor.item/name", "Long Note Test");
        insertDataRow(r8, "vnd.android.cursor.item/note", longNote.toString());
    }

    // ============================================================
    // THE DEFINITIVE TEST: Full round-trip
    // ============================================================
    @Test
    public void testFullRoundTrip() throws Exception {
        // Phase 1: Create edge-case contacts
        createTestData();
        Thread.sleep(500); // Let the provider's own aggregation settle before snapshotting

        AndroidContactsSnapshot originalSnapshot = ContactsSnapshotReader.read(
                InstrumentationRegistry.getInstrumentation().getTargetContext());

        int originalContacts = originalSnapshot.getContactCount();
        int originalRawContacts = originalSnapshot.getRawContactCount();
        int originalDataRows = originalSnapshot.getDataRowCount();

        android.util.Log.e("E2E", "Created: " + originalContacts + " contacts, " + originalRawContacts + " raw contacts, " + originalDataRows + " data rows");

        assertTrue("Should have at least 1 contact", originalContacts >= 1);
        assertTrue("Should have at least 5 raw contacts", originalRawContacts >= 5);
        assertTrue("Should have at least 20 data rows", originalDataRows >= 20);

        // Phase 2: Write .lcb archive
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BackupArchiveWriter.writeArchive(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                originalSnapshot, baos);
        byte[] archive = baos.toByteArray();
        assertTrue("Archive should not be empty", archive.length > 0);

        // Phase 3: Read archive back
        BackupArchiveReader.ArchiveData archiveData;
        try (InputStream is = new ByteArrayInputStream(archive)) {
            archiveData = BackupArchiveReader.readArchive(is);
        }
        assertTrue("Archive should be valid", archiveData.isLossless());
        assertNotNull("Should have snapshot", archiveData.snapshot);
        assertTrue("Checksum should be valid", archiveData.checksumValid);

        // Phase 4: Verify snapshot survived archive round-trip
        String originalJson = NormalizedJsonExporter.exportCanonical(originalSnapshot);
        String archivedJson = NormalizedJsonExporter.exportCanonical(archiveData.snapshot);
        assertEquals("Canonical JSON should match after archive round-trip", originalJson, archivedJson);

        // Phase 5: Clear all contacts
        cleanupContacts();
        Thread.sleep(500); // Wait for provider to settle
        int afterClear = ContactsSnapshotReader.read(
                InstrumentationRegistry.getInstrumentation().getTargetContext()).getDataRowCount();
        assertTrue("Contacts should be mostly cleared (got " + afterClear + " data rows)", afterClear < originalDataRows / 2);

        // Phase 6: Restore from snapshot
        ContactsSnapshotRestorer.RestoreProgress progress = (msg, cur, total) -> {};
        ContactsSnapshotRestorer.restoreExact(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                archiveData.snapshot, progress);
        Thread.sleep(500); // Let the provider settle before reading it back

        // Phase 7: Read restored contacts
        AndroidContactsSnapshot restoredSnapshot = ContactsSnapshotReader.read(
                InstrumentationRegistry.getInstrumentation().getTargetContext());

        // Phase 8: Canonical lossless comparison. This is the definitive check:
        // the restored Contact count must exactly equal the source Contact count,
        // and every source Contact's consolidated/deduplicated content must be
        // present in exactly one restored Contact. No tolerance, no approximation.
        assertLosslessConsolidation(originalSnapshot, restoredSnapshot);

        // Phase 9: Verify specific content survived
        String restoredJson = NormalizedJsonExporter.exportCanonical(restoredSnapshot);

        // Collect all MIME types in restored snapshot for debugging
        java.util.Set<String> mimeTypes = new java.util.TreeSet<>();
        for (AndroidContactSnapshot c : restoredSnapshot.contacts) {
            for (AndroidContactSnapshot.RawContactSnapshot rc : c.rawContacts) {
                for (AndroidContactSnapshot.DataRowSnapshot dr : rc.dataRows) {
                    mimeTypes.add(dr.mimeType);
                }
            }
        }
        android.util.Log.e("E2E", "Restored MIME types: " + mimeTypes);
        android.util.Log.e("E2E", "Restored JSON length: " + restoredJson.length());

        // Check specific data points
        assertTrue("Full name should survive", restoredJson.contains("Dr. John Michael Smith Jr."));
        assertTrue("Given name should survive", restoredJson.contains("John"));
        assertTrue("Family name should survive", restoredJson.contains("Smith"));
        assertTrue("Prefix should survive", restoredJson.contains("Dr."));
        assertTrue("Middle name should survive", restoredJson.contains("Michael"));
        assertTrue("Suffix should survive", restoredJson.contains("Jr."));
        assertTrue("Phone should survive", restoredJson.contains("+1-555-0101"));
        assertTrue("Phone type custom should survive", restoredJson.contains("My Custom"));
        assertTrue("Email should survive", restoredJson.contains("john@example.com"));
        assertTrue("Address street should survive", restoredJson.contains("123 Main St"));
        assertTrue("Address city should survive", restoredJson.contains("Springfield"));
        assertTrue("Organization should survive", restoredJson.contains("Acme Corp"));
        assertTrue("Title should survive", restoredJson.contains("VP of Engineering"));
        assertTrue("Nickname should survive", restoredJson.contains("Johnny"));
        assertTrue("Note should survive", restoredJson.contains("Important note"));
        assertTrue("Unicode Japanese should survive", restoredJson.contains("日本太郎"));
        assertTrue("Unicode Arabic should survive", restoredJson.contains("محمد بن سلمان"));
        assertTrue("Unicode Cyrillic should survive", restoredJson.contains("Владимир"));
        assertTrue("SIP should survive", restoredJson.contains("sip:user@example.com"));
        assertTrue("Relation should survive", restoredJson.contains("Jane Doe"));
        assertTrue("IM should survive", restoredJson.contains("user@jabber.org"));
        assertTrue("Event should survive", restoredJson.contains("1990-05-15"));
        assertTrue("Website should survive", restoredJson.contains("example.com"));
        assertTrue("Long note should survive", restoredJson.contains("Line 49"));

        // Check account metadata survived
        assertTrue("Google account should survive", restoredJson.contains("com.google"));
        assertTrue("Google email should survive", restoredJson.contains("user@gmail.com"));

        System.out.println("=== ROUND-TRIP RESULT ===");
        System.out.println("Original: " + originalContacts + " contacts, " + originalRawContacts + " raw contacts, " + originalDataRows + " data rows");
        System.out.println("Restored: " + restoredSnapshot.getContactCount() + " contacts, " + restoredSnapshot.getRawContactCount() + " raw contacts, " + restoredSnapshot.getDataRowCount() + " data rows");
        System.out.println("Archive size: " + archive.length + " bytes");
        System.out.println("PASS: Full round-trip test");
    }

    // ============================================================
    // Grouping-boundary tests: source Contact grouping must be the
    // strongest identity boundary during restore.
    // ============================================================

    /** Case A: multiple RawContacts under one source Contact merge into one target Contact. */
    @Test
    public void testCaseA_MultipleRawContactsMergeIntoOneContact() throws Exception {
        String r1 = insertRawContact("acct@google.com", "com.google");
        insertDataRow(r1, "vnd.android.cursor.item/name", "Case A Person", "Case", "A");
        insertDataRowWithType(r1, "vnd.android.cursor.item/phone_v2", 1, null, "+1-555-2001");

        String r2 = insertRawContact(null, "com.android.contacts");
        insertDataRowWithType(r2, "vnd.android.cursor.item/email_v2", 2, null, "casea@example.com");
        insertDataRowWithType(r2, "vnd.android.cursor.item/postal-address_v2", 1, null,
                null, null, null, null, null, "1 Case St", "Testville", "TS", "00001", "USA");

        keepTogether(r1, r2);
        Thread.sleep(500);

        AndroidContactsSnapshot original = ContactsSnapshotReader.read(targetContext());
        assertEquals("Should be exactly one source Contact", 1, original.getContactCount());
        assertEquals("Both raw contacts should be under that one source Contact",
                2, original.contacts.get(0).rawContacts.size());

        AndroidContactsSnapshot restored = backupClearRestore(original);
        assertLosslessConsolidation(original, restored);
        assertEquals("Restore should still produce exactly one target Contact", 1, restored.getContactCount());
    }

    /** Case B: two separate source Contacts with an identical name must stay separate. */
    @Test
    public void testCaseB_SameNameDifferentSourceContactsStaySeparate() throws Exception {
        String r1 = insertRawContact(null, null);
        insertDataRow(r1, "vnd.android.cursor.item/name", "Case B Duplicate");
        insertDataRowWithType(r1, "vnd.android.cursor.item/phone_v2", 1, null, "+1-555-3001");

        String r2 = insertRawContact(null, null);
        insertDataRow(r2, "vnd.android.cursor.item/name", "Case B Duplicate");
        insertDataRowWithType(r2, "vnd.android.cursor.item/phone_v2", 2, null, "+1-555-3002");

        keepSeparate(r1, r2);
        Thread.sleep(500);

        AndroidContactsSnapshot original = ContactsSnapshotReader.read(targetContext());
        assertEquals("Two identically-named source Contacts must remain separate", 2, original.getContactCount());

        AndroidContactsSnapshot restored = backupClearRestore(original);
        assertLosslessConsolidation(original, restored);
        assertEquals("Restore must not merge same-named source Contacts just because names match",
                2, restored.getContactCount());
    }

    /** Case C: a named RawContact + a nameless RawContact under the same source Contact merge into one. */
    @Test
    public void testCaseC_NamedAndNamelessRawContactMergeIntoOne() throws Exception {
        String r1 = insertRawContact(null, null);
        insertDataRow(r1, "vnd.android.cursor.item/name", "Case C Person");
        insertDataRowWithType(r1, "vnd.android.cursor.item/phone_v2", 1, null, "+1-555-4001");

        String r2 = insertRawContact(null, null);
        insertDataRowWithType(r2, "vnd.android.cursor.item/email_v2", 2, null, "casec@example.com");
        insertDataRow(r2, "vnd.android.cursor.item/note", "Doctor");

        keepTogether(r1, r2);
        Thread.sleep(500);

        AndroidContactsSnapshot original = ContactsSnapshotReader.read(targetContext());
        assertEquals(1, original.getContactCount());
        assertEquals(2, original.contacts.get(0).rawContacts.size());

        AndroidContactsSnapshot restored = backupClearRestore(original);
        assertLosslessConsolidation(original, restored);
        assertEquals("Nameless RawContact's data must not become a separate/lost record", 1, restored.getContactCount());

        String restoredJson = NormalizedJsonExporter.exportCanonical(restored);
        assertTrue("Name should survive", restoredJson.contains("Case C Person"));
        assertTrue("Nameless raw contact's email should survive", restoredJson.contains("casec@example.com"));
        assertTrue("Nameless raw contact's note should survive", restoredJson.contains("Doctor"));
    }

    /** Case D: two independent nameless source Contacts must stay separate, not be discarded or merged. */
    @Test
    public void testCaseD_TwoNamelessSourceContactsStaySeparate() throws Exception {
        String r1 = insertRawContact(null, null);
        insertDataRowWithType(r1, "vnd.android.cursor.item/phone_v2", 1, null, "+1-555-5001");
        insertDataRow(r1, "vnd.android.cursor.item/note", "Pizza delivery");

        String r2 = insertRawContact(null, null);
        insertDataRowWithType(r2, "vnd.android.cursor.item/phone_v2", 1, null, "+1-555-5002");
        insertDataRow(r2, "vnd.android.cursor.item/note", "Doctor");

        keepSeparate(r1, r2);
        Thread.sleep(500);

        AndroidContactsSnapshot original = ContactsSnapshotReader.read(targetContext());
        assertEquals("Two independent nameless source Contacts must remain separate", 2, original.getContactCount());

        AndroidContactsSnapshot restored = backupClearRestore(original);
        assertLosslessConsolidation(original, restored);
        assertEquals("Restore must not merge or drop nameless source Contacts", 2, restored.getContactCount());
    }

    // ============================================================
    // Full field coverage spread across multiple RawContacts, plus
    // unknown-MIME and binary/photo exactness tests.
    // ============================================================

    /** Every important field type, spread across three RawContacts under one source Contact. */
    @Test
    public void testFieldCoverageAcrossMultipleRawContacts() throws Exception {
        // "Google" raw contact: structured name (incl. phonetic) + phones + emails
        String rGoogle = insertRawContact("coverage@gmail.com", "com.google");
        insertDataRow(rGoogle, "vnd.android.cursor.item/name",
                "Dr. Coverage Test Person Jr.", "Coverage", "Person", "Dr.", "Test", "Jr.",
                "Cov", "Tst", "Prsn");
        insertDataRowWithType(rGoogle, "vnd.android.cursor.item/phone_v2", 1, null, "+1-555-6001");
        insertDataRowWithType(rGoogle, "vnd.android.cursor.item/phone_v2", 0, "Batphone", "+1-555-6002");
        insertDataRowWithType(rGoogle, "vnd.android.cursor.item/email_v2", 2, null, "coverage.work@example.com");
        insertDataRowWithType(rGoogle, "vnd.android.cursor.item/email_v2", 0, "Alt", "coverage.alt@example.com");

        // "Local" raw contact: address, org/title/department, nickname, notes,
        // websites, relations, IM, events, SIP.
        String rLocal = insertRawContact(null, "com.android.contacts");
        insertDataRowWithType(rLocal, "vnd.android.cursor.item/postal-address_v2", 1, null,
                null, null, null, null, null, "1 Coverage Way", "Fieldtown", "FT", "10001", "USA");
        insertDataRow(rLocal, "vnd.android.cursor.item/organization", "Coverage Corp", null, null, "Chief Tester", "QA");
        insertDataRow(rLocal, "vnd.android.cursor.item/nickname", "Covvy");
        insertDataRow(rLocal, "vnd.android.cursor.item/note", "Spans multiple raw contacts\nwith unicode: 日本語 & <special>");
        insertDataRowWithType(rLocal, "vnd.android.cursor.item/website", 1, null, "https://coverage.example.com");
        insertDataRowWithType(rLocal, "vnd.android.cursor.item/relation", 12, null, "Related Person");
        insertDataRow(rLocal, "vnd.android.cursor.item/im", "coverage@jabber.org", null, null, null, "6");
        insertDataRowWithType(rLocal, "vnd.android.cursor.item/contact_event", 1, null, "1985-03-03");
        insertDataRowWithType(rLocal, "vnd.android.cursor.item/sip-address", 1, null, "sip:coverage@example.com");

        // "SIM"-like raw contact: just a photo (binary DATA15).
        String rSim = insertRawContact(null, null);
        byte[] photoBytes = samplePhotoBytes();
        insertRowWithBinary(rSim, "vnd.android.cursor.item/photo", new String[0], photoBytes);

        keepTogether(rGoogle, rLocal);
        keepTogether(rLocal, rSim);
        Thread.sleep(500);

        AndroidContactsSnapshot original = ContactsSnapshotReader.read(targetContext());
        assertEquals("All three raw contacts should consolidate under one source Contact", 1, original.getContactCount());
        assertEquals(3, original.contacts.get(0).rawContacts.size());

        AndroidContactsSnapshot restored = backupClearRestore(original);
        assertLosslessConsolidation(original, restored);
        assertEquals(1, restored.getContactCount());

        String restoredJson = NormalizedJsonExporter.exportCanonical(restored);
        assertTrue("Full structured name should survive", restoredJson.contains("Dr. Coverage Test Person Jr."));
        assertTrue("Custom phone label should survive", restoredJson.contains("Batphone"));
        assertTrue("Custom email label's address should survive", restoredJson.contains("coverage.alt@example.com"));
        assertTrue("Address street should survive", restoredJson.contains("1 Coverage Way"));
        assertTrue("Organization should survive", restoredJson.contains("Coverage Corp"));
        assertTrue("Title should survive", restoredJson.contains("Chief Tester"));
        assertTrue("Nickname should survive", restoredJson.contains("Covvy"));
        assertTrue("Unicode note should survive", restoredJson.contains("日本語"));
        assertTrue("Website should survive", restoredJson.contains("coverage.example.com"));
        assertTrue("Relation should survive", restoredJson.contains("Related Person"));
        assertTrue("IM should survive", restoredJson.contains("coverage@jabber.org"));
        assertTrue("Event should survive", restoredJson.contains("1985-03-03"));
        assertTrue("SIP address should survive", restoredJson.contains("sip:coverage@example.com"));

        AndroidContactSnapshot.DataRowSnapshot sourcePhoto = findRow(original, "vnd.android.cursor.item/photo");
        assertNotNull(sourcePhoto);
        AndroidContactSnapshot.DataRowSnapshot restoredPhoto = findRow(restored, "vnd.android.cursor.item/photo");
        assertNotNull("Photo row from the third raw contact should survive consolidation", restoredPhoto);
        assertEquals("Photo bytes must match what the source snapshot captured (SHA-256)",
                sha256Hex(sourcePhoto.data15), sha256Hex(restoredPhoto.data15));
    }

    /** Unknown/custom MIME types must be attempted on restore and never silently dropped. */
    @Test
    public void testUnknownMimeTypeRoundTrip() throws Exception {
        String r1 = insertRawContact(null, null);
        insertDataRow(r1, "vnd.android.cursor.item/name", "Unknown Mime Test");
        byte[] binary = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        insertRowWithBinary(r1, "application/x-test-contact",
                new String[]{"value1", "value2", "value3", "value4", "value5", "value6", "value7",
                        "value8", "value9", "value10", "value11", "value12", "value13", "value14"},
                binary);

        AndroidContactsSnapshot original = ContactsSnapshotReader.read(targetContext());
        AndroidContactSnapshot.DataRowSnapshot originalUnknown = findRow(original, "application/x-test-contact");
        assertNotNull("Should capture the unknown MIME row in the backup", originalUnknown);
        assertEquals("value7", originalUnknown.data7);
        assertEquals("value14", originalUnknown.data14);

        AndroidContactsSnapshot restored = backupClearRestore(original);
        assertLosslessConsolidation(original, restored);

        AndroidContactSnapshot.DataRowSnapshot restoredUnknown = findRow(restored, "application/x-test-contact");
        assertNotNull("Unknown MIME row must be attempted on restore, not silently dropped", restoredUnknown);
        for (int i = 1; i <= 14; i++) {
            assertEquals("data" + i + " should survive exactly", originalUnknown.getData(i), restoredUnknown.getData(i));
        }
        assertArrayEquals("data15 binary should survive exactly", originalUnknown.data15, restoredUnknown.data15);
    }

    /** Photo/binary data must round-trip byte-for-byte, verified via SHA-256. */
    @Test
    public void testPhotoBinaryExactBytesRoundTrip() throws Exception {
        String r1 = insertRawContact(null, null);
        insertDataRow(r1, "vnd.android.cursor.item/name", "Photo Test");
        byte[] photo = samplePhotoBytes();
        insertRowWithBinary(r1, "vnd.android.cursor.item/photo", new String[0], photo);

        // Compare against what the SOURCE snapshot itself captured (i.e. what
        // the Contacts provider produced from our input), not the raw input
        // bytes: the provider re-encodes photo data into its own thumbnail
        // representation on every insert, on both the source device and the
        // restore target, so this is the only byte-for-byte comparison that
        // is meaningfully within this app's control.
        AndroidContactsSnapshot original = ContactsSnapshotReader.read(targetContext());
        AndroidContactSnapshot.DataRowSnapshot sourcePhoto = findRow(original, "vnd.android.cursor.item/photo");
        assertNotNull("Source snapshot should have captured the photo row", sourcePhoto);
        assertNotNull("Source snapshot should have captured non-empty photo bytes", sourcePhoto.data15);

        AndroidContactsSnapshot restored = backupClearRestore(original);
        assertLosslessConsolidation(original, restored);

        AndroidContactSnapshot.DataRowSnapshot restoredPhoto = findRow(restored, "vnd.android.cursor.item/photo");
        assertNotNull("Photo row must survive restore, not be silently dropped", restoredPhoto);
        assertNotNull(restoredPhoto.data15);
        assertTrue("Restored photo must not be empty", restoredPhoto.data15.length > 0);
        assertEquals("SHA-256 of restored photo must match what the source snapshot captured",
                sha256Hex(sourcePhoto.data15), sha256Hex(restoredPhoto.data15));
    }

    /** Group membership must be restored (by mapping to a matching/created target group), not silently dropped. */
    @Test
    public void testGroupMembershipRestoredNotSilentlyDropped() throws Exception {
        ContentValues groupValues = new ContentValues();
        groupValues.put(ContactsContract.Groups.TITLE, "Test Group Alpha");
        groupValues.put(ContactsContract.Groups.ACCOUNT_NAME, "group-test@example.com");
        groupValues.put(ContactsContract.Groups.ACCOUNT_TYPE, "com.google");
        groupValues.put(ContactsContract.Groups.GROUP_VISIBLE, 1);
        Uri groupUri = resolver.insert(ContactsContract.Groups.CONTENT_URI, groupValues);
        assertNotNull("Group should be created", groupUri);
        long groupId = Long.parseLong(groupUri.getLastPathSegment());

        String r1 = insertRawContact("group-test@example.com", "com.google");
        insertDataRow(r1, "vnd.android.cursor.item/name", "Group Member");
        ContentProviderOperation.Builder gm = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, r1)
                .withValue(ContactsContract.Data.MIMETYPE, "vnd.android.cursor.item/group_membership")
                .withValue(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID, groupId);
        resolver.applyBatch(ContactsContract.AUTHORITY, new ArrayList<>(java.util.Collections.singletonList(gm.build())));

        AndroidContactsSnapshot original = ContactsSnapshotReader.read(targetContext());
        assertEquals(1, original.getContactCount());
        assertEquals("Group should be captured in the snapshot", 1, original.getGroups().size());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BackupArchiveWriter.writeArchive(targetContext(), original, baos);
        BackupArchiveReader.ArchiveData archiveData;
        try (InputStream is = new ByteArrayInputStream(baos.toByteArray())) {
            archiveData = BackupArchiveReader.readArchive(is);
        }
        assertEquals("Group should survive archive round-trip", 1, archiveData.snapshot.getGroups().size());

        cleanupContacts(); // wipes both raw_contacts and groups
        Thread.sleep(300);

        ContactsSnapshotRestorer.RestoreProgress progress = (msg, cur, total) -> {};
        RestoreResult result = ContactsSnapshotRestorer.restoreExact(targetContext(), archiveData.snapshot, progress);
        Thread.sleep(500);

        assertEquals("Group membership should be restored, not silently dropped", 0, result.groupMembershipsUnrestored);

        // The original (now soft-deleted, per Android's deletion-queue
        // behavior) group row can still be visible through this query
        // alongside the fresh one restore created — filter to live groups
        // only, matching what a well-behaved reader (including our own
        // ContactsSnapshotRestorer) does.
        Cursor c = resolver.query(ContactsContract.Groups.CONTENT_URI,
                new String[]{ContactsContract.Groups._ID},
                ContactsContract.Groups.TITLE + "=? AND " + ContactsContract.Groups.DELETED + "=0",
                new String[]{"Test Group Alpha"}, null);
        assertNotNull(c);
        assertTrue("A live group with the same title should exist on the target", c.moveToFirst());
        long newGroupId = c.getLong(0);
        c.close();

        AndroidContactsSnapshot restored = ContactsSnapshotReader.read(targetContext());
        boolean found = false;
        for (AndroidContactSnapshot contact : restored.contacts) {
            for (AndroidContactSnapshot.RawContactSnapshot rc : contact.rawContacts) {
                for (AndroidContactSnapshot.DataRowSnapshot row : rc.dataRows) {
                    if ("vnd.android.cursor.item/group_membership".equals(row.mimeType)
                            && String.valueOf(newGroupId).equals(row.data1)) {
                        found = true;
                    }
                }
            }
        }
        assertTrue("Restored contact should reference the mapped target group", found);
    }

    // ============================================================
    // Realistic-scale dataset tests (spec section 18): prove correctness
    // (not just "doesn't crash") holds up at 10 / 100 / 500+ contacts,
    // including a mix of single- and multi-RawContact source Contacts.
    // ============================================================

    private void createScaleDataset(int contactCount) throws Exception {
        for (int i = 0; i < contactCount; i++) {
            if (i % 5 == 0) {
                // Every 5th contact: two RawContacts (different accounts)
                // consolidating into one source Contact.
                String rA = insertRawContact("scale" + i + "@gmail.com", "com.google");
                insertDataRow(rA, "vnd.android.cursor.item/name", "Scale Person " + i, "Scale", "Person" + i);
                insertDataRowWithType(rA, "vnd.android.cursor.item/phone_v2", 1, null, "+1-555-" + String.format("%04d", i));

                String rB = insertRawContact(null, "com.android.contacts");
                insertDataRowWithType(rB, "vnd.android.cursor.item/email_v2", 2, null, "scale" + i + "@example.com");
                insertDataRow(rB, "vnd.android.cursor.item/note", "Scale test note " + i);
                keepTogether(rA, rB);
            } else {
                String r = insertRawContact(null, null);
                insertDataRow(r, "vnd.android.cursor.item/name", "Scale Person " + i, "Scale", "Person" + i);
                insertDataRowWithType(r, "vnd.android.cursor.item/phone_v2", 1, null, "+1-555-" + String.format("%04d", i));
                insertDataRowWithType(r, "vnd.android.cursor.item/email_v2", 2, null, "scale" + i + "@example.com");
            }
        }
    }

    private void runScaleRoundTrip(int contactCount) throws Exception {
        createScaleDataset(contactCount);
        Thread.sleep(Math.min(5000, 200 + contactCount * 5L));

        AndroidContactsSnapshot original = ContactsSnapshotReader.read(targetContext());
        assertEquals("Should have exactly " + contactCount + " source Contacts",
                contactCount, original.getContactCount());

        long start = System.currentTimeMillis();
        AndroidContactsSnapshot restored = backupClearRestore(original);
        long elapsed = System.currentTimeMillis() - start;

        assertLosslessConsolidation(original, restored);
        System.out.println("Scale test (" + contactCount + " contacts): backup+restore took " + elapsed + "ms");
    }

    @Test
    public void testScale10Contacts() throws Exception {
        runScaleRoundTrip(10);
    }

    @Test
    public void testScale100Contacts() throws Exception {
        runScaleRoundTrip(100);
    }

    @Test
    public void testScale500Contacts() throws Exception {
        runScaleRoundTrip(500);
    }
}
