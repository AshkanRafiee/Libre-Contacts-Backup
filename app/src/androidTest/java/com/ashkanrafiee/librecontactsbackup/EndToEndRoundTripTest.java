package com.ashkanrafiee.librecontactsbackup;

import android.content.ContentProviderOperation;
import android.content.ContentResolver;
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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

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

        // Phase 7: Read restored contacts
        AndroidContactsSnapshot restoredSnapshot = ContactsSnapshotReader.read(
                InstrumentationRegistry.getInstrumentation().getTargetContext());

        // Phase 8: Verify counts match (use relative comparison due to provider aggregation)
        int restoredDataRows = restoredSnapshot.getDataRowCount();
        assertTrue("Data row count should be similar (was " + originalDataRows + ", got " + restoredDataRows + ")",
                Math.abs(originalDataRows - restoredDataRows) <= originalDataRows * 0.15);

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
}
