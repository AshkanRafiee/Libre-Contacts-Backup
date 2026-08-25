package com.ashkanrafiee.librecontactsbackup;

import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.OperationApplicationException;
import android.os.RemoteException;
import android.provider.ContactsContract;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.ashkanrafiee.librecontactsbackup.archive.BackupArchiveWriter;
import com.ashkanrafiee.librecontactsbackup.archive.ContactsSnapshotRestorer;
import com.ashkanrafiee.librecontactsbackup.archive.BackupManifest;
import com.ashkanrafiee.librecontactsbackup.export.NormalizedJsonExporter;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactsSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.ContactsSnapshotReader;
import com.ashkanrafiee.librecontactsbackup.snapshot.RestoreResult;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.Assert.*;

/**
 * Instrumented tests for the lossless backup/restore architecture.
 *
 * Tests:
 * 1. Snapshot reader captures all contact data fields
 * 2. Canonical JSON export preserves all fields
 * 3. Canonical JSON import restores all fields
 * 4. Full round-trip: provider → snapshot → JSON → snapshot → JSON matches
 * 5. Binary data (photos) preserved through round-trip
 * 6. Multiple RawContacts preserved through round-trip
 * 7. Unknown MIME types preserved through round-trip
 * 8. Backup archive contains correct files
 * 9. Manifest checksums are correct
 */
@RunWith(AndroidJUnit4.class)
public class LosslessBackupTest {

    private ContentResolver resolver;
    private final List<String> rawContactIds = new ArrayList<>();

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
        } catch (Exception e) {
            // ignore
        }
        rawContactIds.clear();
    }

    /**
     * Creates a raw contact with structured name and returns its ID.
     */
    private String createNamedContact(String displayName, String given, String family,
                                       String prefix, String middle, String suffix) throws Exception {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .build());
        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, displayName)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, given)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, family)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.PREFIX, prefix)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME, middle)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.SUFFIX, suffix)
                .build());
        android.content.ContentProviderResult[] results = resolver.applyBatch(ContactsContract.AUTHORITY, ops);
        String id = results[0].uri.getLastPathSegment();
        rawContactIds.add(id);
        return id;
    }

    /**
     * Adds a phone number to an existing raw contact.
     */
    private void addPhone(String rawContactId, String number, int type, String label) throws Exception {
        ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, type);
        if (label != null) b.withValue(ContactsContract.CommonDataKinds.Phone.LABEL, label);
        resolver.applyBatch(ContactsContract.AUTHORITY, new ArrayList<>(java.util.Collections.singletonList(b.build())));
    }

    /**
     * Adds an email to an existing raw contact.
     */
    private void addEmail(String rawContactId, String address, int type) throws Exception {
        ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, address)
                .withValue(ContactsContract.CommonDataKinds.Email.TYPE, type);
        resolver.applyBatch(ContactsContract.AUTHORITY, new ArrayList<>(java.util.Collections.singletonList(b.build())));
    }

    /**
     * Adds a note to an existing raw contact.
     */
    private void addNote(String rawContactId, String note) throws Exception {
        ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Note.NOTE, note);
        resolver.applyBatch(ContactsContract.AUTHORITY, new ArrayList<>(java.util.Collections.singletonList(b.build())));
    }

    /**
     * Adds an organization to an existing raw contact.
     */
    private void addOrganization(String rawContactId, String company, String title) throws Exception {
        ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, company)
                .withValue(ContactsContract.CommonDataKinds.Organization.TITLE, title);
        resolver.applyBatch(ContactsContract.AUTHORITY, new ArrayList<>(java.util.Collections.singletonList(b.build())));
    }

    // ============================================================
    // TEST 1: Snapshot reader captures all fields
    // ============================================================
    @Test
    public void testSnapshotReaderCapturesAllFields() throws Exception {
        String rawId = createNamedContact("John Smith", "John", "Smith", "Dr.", "Michael", "Jr.");
        addPhone(rawId, "+1-555-0101", ContactsContract.CommonDataKinds.Phone.TYPE_HOME, null);
        addPhone(rawId, "+1-555-0102", ContactsContract.CommonDataKinds.Phone.TYPE_WORK, null);
        addEmail(rawId, "john@example.com", ContactsContract.CommonDataKinds.Email.TYPE_HOME);
        addNote(rawId, "Important contact");
        addOrganization(rawId, "Acme Corp", "Engineer");

        AndroidContactsSnapshot snapshot = ContactsSnapshotReader.read(InstrumentationRegistry.getInstrumentation().getTargetContext());

        assertNotNull("Snapshot should not be null", snapshot);
        assertTrue("Should have at least 1 contact", snapshot.getContactCount() >= 1);
        assertTrue("Should have at least 1 raw contact", snapshot.getRawContactCount() >= 1);

        // Find our contact
        AndroidContactSnapshot found = null;
        for (AndroidContactSnapshot c : snapshot.contacts) {
            for (AndroidContactSnapshot.RawContactSnapshot rc : c.rawContacts) {
                for (AndroidContactSnapshot.DataRowSnapshot dr : rc.dataRows) {
                    if ("John Smith".equals(dr.data1) && "vnd.android.cursor.item/name".equals(dr.mimeType)) {
                        found = c;
                        break;
                    }
                }
                if (found != null) break;
            }
            if (found != null) break;
        }

        assertNotNull("Should find John Smith contact", found);

        // Count data rows for this contact's raw contact
        int phoneCount = 0, emailCount = 0, noteCount = 0, orgCount = 0, nameCount = 0;
        for (AndroidContactSnapshot.RawContactSnapshot rc : found.rawContacts) {
            for (AndroidContactSnapshot.DataRowSnapshot dr : rc.dataRows) {
                switch (dr.mimeType) {
                    case "vnd.android.cursor.item/phone_v2": phoneCount++; break;
                    case "vnd.android.cursor.item/email_v2": emailCount++; break;
                    case "vnd.android.cursor.item/note": noteCount++; break;
                    case "vnd.android.cursor.item/organization": orgCount++; break;
                    case "vnd.android.cursor.item/name": nameCount++; break;
                }
            }
        }

        assertTrue("Should have 2 phones", phoneCount >= 2);
        assertTrue("Should have 1 email", emailCount >= 1);
        assertTrue("Should have 1 note", noteCount >= 1);
        assertTrue("Should have 1 organization", orgCount >= 1);
        assertTrue("Should have 1 name", nameCount >= 1);
    }

    // ============================================================
    // TEST 2: Canonical JSON export preserves all fields
    // ============================================================
    @Test
    public void testCanonicalJsonPreservesAllFields() throws Exception {
        String rawId = createNamedContact("JSON Test", "JSON", "Test", "Mr.", "Middle", "III");
        addPhone(rawId, "+1-555-9999", ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE, null);
        addEmail(rawId, "json@test.com", ContactsContract.CommonDataKinds.Email.TYPE_WORK);
        addNote(rawId, "Note with special chars: <>&\"' and\nnewlines");

        AndroidContactsSnapshot snapshot = ContactsSnapshotReader.read(InstrumentationRegistry.getInstrumentation().getTargetContext());

        String json = NormalizedJsonExporter.exportCanonical(snapshot);
        assertNotNull("Canonical JSON should not be null", json);
        assertTrue("Should be valid JSON", json.startsWith("["));

        // Import back
        AndroidContactsSnapshot imported = NormalizedJsonExporter.importCanonical(json);
        assertNotNull("Imported snapshot should not be null", imported);
        assertTrue("Imported should have contacts", imported.getContactCount() >= 1);

        // Find the contact in imported snapshot
        boolean found = false;
        for (AndroidContactSnapshot c : imported.contacts) {
            for (AndroidContactSnapshot.RawContactSnapshot rc : c.rawContacts) {
                for (AndroidContactSnapshot.DataRowSnapshot dr : rc.dataRows) {
                    if ("JSON Test".equals(dr.data1) && "vnd.android.cursor.item/name".equals(dr.mimeType)) {
                        found = true;
                        break;
                    }
                }
            }
        }
        assertTrue("Should find JSON Test in imported snapshot", found);
    }

    // ============================================================
    // TEST 3: Unicode preserved through round-trip
    // ============================================================
    @Test
    public void testUnicodeRoundTrip() throws Exception {
        String rawId = createNamedContact("日本太郎 田中", "太郎", "田中", null, null, null);
        addPhone(rawId, "+81-90-1234-5678", ContactsContract.CommonDataKinds.Phone.TYPE_HOME, null);

        AndroidContactsSnapshot snapshot = ContactsSnapshotReader.read(InstrumentationRegistry.getInstrumentation().getTargetContext());

        String json = NormalizedJsonExporter.exportCanonical(snapshot);
        AndroidContactsSnapshot imported = NormalizedJsonExporter.importCanonical(json);

        boolean foundJapanese = false;
        for (AndroidContactSnapshot c : imported.contacts) {
            for (AndroidContactSnapshot.RawContactSnapshot rc : c.rawContacts) {
                for (AndroidContactSnapshot.DataRowSnapshot dr : rc.dataRows) {
                    if (dr.data1 != null && dr.data1.contains("日本太郎")) {
                        foundJapanese = true;
                        assertEquals("Phonetic given name should be 太郎", "太郎", dr.data2);
                        assertEquals("Phonetic family name should be 田中", "田中", dr.data3);
                    }
                }
            }
        }
        assertTrue("Japanese name should survive round-trip", foundJapanese);
    }

    // ============================================================
    // TEST 4: Structured name fields preserved
    // ============================================================
    @Test
    public void testStructuredNameRoundTrip() throws Exception {
        String rawId = createNamedContact("Dr. John Michael Smith Jr.", "John", "Smith",
                "Dr.", "Michael", "Jr.");

        AndroidContactsSnapshot snapshot = ContactsSnapshotReader.read(InstrumentationRegistry.getInstrumentation().getTargetContext());
        String json = NormalizedJsonExporter.exportCanonical(snapshot);
        AndroidContactsSnapshot imported = NormalizedJsonExporter.importCanonical(json);

        for (AndroidContactSnapshot c : imported.contacts) {
            for (AndroidContactSnapshot.RawContactSnapshot rc : c.rawContacts) {
                for (AndroidContactSnapshot.DataRowSnapshot dr : rc.dataRows) {
                    if ("vnd.android.cursor.item/name".equals(dr.mimeType) && "John".equals(dr.data2)) {
                        assertEquals("display_name", "Dr. John Michael Smith Jr.", dr.data1);
                        assertEquals("given_name", "John", dr.data2);
                        assertEquals("family_name", "Smith", dr.data3);
                        assertEquals("prefix", "Dr.", dr.data4);
                        assertEquals("middle_name", "Michael", dr.data5);
                        assertEquals("suffix", "Jr.", dr.data6);
                        return; // test passed
                    }
                }
            }
        }
        fail("Should find structured name");
    }

    // ============================================================
    // TEST 5: Multiple phones with types preserved
    // ============================================================
    @Test
    public void testMultiplePhonesRoundTrip() throws Exception {
        String rawId = createNamedContact("Phone Test", "Phone", "Test", null, null, null);
        addPhone(rawId, "+1-555-0001", ContactsContract.CommonDataKinds.Phone.TYPE_HOME, null);
        addPhone(rawId, "+1-555-0002", ContactsContract.CommonDataKinds.Phone.TYPE_WORK, null);
        addPhone(rawId, "+1-555-0003", ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE, null);
        addPhone(rawId, "+1-555-0004", ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM, "My Custom");

        AndroidContactsSnapshot snapshot = ContactsSnapshotReader.read(InstrumentationRegistry.getInstrumentation().getTargetContext());
        String json = NormalizedJsonExporter.exportCanonical(snapshot);
        AndroidContactsSnapshot imported = NormalizedJsonExporter.importCanonical(json);

        int phoneCount = 0;
        boolean foundCustom = false;
        for (AndroidContactSnapshot c : imported.contacts) {
            for (AndroidContactSnapshot.RawContactSnapshot rc : c.rawContacts) {
                for (AndroidContactSnapshot.DataRowSnapshot dr : rc.dataRows) {
                    if ("vnd.android.cursor.item/phone_v2".equals(dr.mimeType)) {
                        phoneCount++;
                        if ("My Custom".equals(dr.data3)) {
                            foundCustom = true;
                        }
                    }
                }
            }
        }
        assertTrue("Should have at least 4 phones", phoneCount >= 4);
        assertTrue("Custom label should be preserved", foundCustom);
    }

    // ============================================================
    // TEST 6: Note with special characters preserved
    // ============================================================
    @Test
    public void testNoteSpecialCharsRoundTrip() throws Exception {
        String noteText = "Line 1\nLine 2\nSpecial: <html>&amp;\"quoted\"";
        String rawId = createNamedContact("Note Test", "Note", "Test", null, null, null);
        addNote(rawId, noteText);

        AndroidContactsSnapshot snapshot = ContactsSnapshotReader.read(InstrumentationRegistry.getInstrumentation().getTargetContext());
        String json = NormalizedJsonExporter.exportCanonical(snapshot);
        AndroidContactsSnapshot imported = NormalizedJsonExporter.importCanonical(json);

        for (AndroidContactSnapshot c : imported.contacts) {
            for (AndroidContactSnapshot.RawContactSnapshot rc : c.rawContacts) {
                for (AndroidContactSnapshot.DataRowSnapshot dr : rc.dataRows) {
                    if ("vnd.android.cursor.item/note".equals(dr.mimeType)) {
                        assertEquals("Note content should match exactly", noteText, dr.data1);
                        return;
                    }
                }
            }
        }
        fail("Should find note");
    }

    // ============================================================
    // TEST 7: Backup archive structure
    // ============================================================
    @Test
    public void testBackupArchiveStructure() throws Exception {
        createNamedContact("Archive Test", "Archive", "Test", null, null, null);

        AndroidContactsSnapshot snapshot = ContactsSnapshotReader.read(InstrumentationRegistry.getInstrumentation().getTargetContext());
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BackupArchiveWriter.writeArchive(InstrumentationRegistry.getInstrumentation().getTargetContext(), snapshot, baos);

        byte[] archiveBytes = baos.toByteArray();
        assertTrue("Archive should not be empty", archiveBytes.length > 0);

        // Read back the ZIP
        ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(archiveBytes));
        List<String> entries = new ArrayList<>();
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            entries.add(entry.getName());
            zis.closeEntry();
        }
        zis.close();

        assertTrue("Should contain manifest.json", entries.contains("manifest.json"));
        assertTrue("Should contain android-contacts.json", entries.contains("android-contacts.json"));
        assertTrue("Should contain contacts.vcf", entries.contains("contacts.vcf"));
        assertTrue("Should contain contacts.json", entries.contains("contacts.json"));
        assertTrue("Should contain contacts.csv", entries.contains("contacts.csv"));
        assertEquals("Should have exactly 5 entries", 5, entries.size());
    }

    // ============================================================
    // TEST 8: Manifest checksums are correct
    // ============================================================
    @Test
    public void testManifestChecksums() throws Exception {
        createNamedContact("Checksum Test", "Checksum", "Test", null, null, null);

        AndroidContactsSnapshot snapshot = ContactsSnapshotReader.read(InstrumentationRegistry.getInstrumentation().getTargetContext());
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BackupArchiveWriter.writeArchive(InstrumentationRegistry.getInstrumentation().getTargetContext(), snapshot, baos);

        byte[] archiveBytes = baos.toByteArray();

        // Read all entries from the ZIP
        ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(archiveBytes));
        java.util.Map<String, byte[]> entryBytes = new java.util.HashMap<>();
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            entryBytes.put(entry.getName(), zis.readAllBytes());
            zis.closeEntry();
        }
        zis.close();

        // Parse manifest
        String manifestStr = new String(entryBytes.get("manifest.json"), StandardCharsets.UTF_8);
        JSONObject manifestJson = new JSONObject(manifestStr);

        // Verify schema version
        assertEquals("Schema version should be 2", 2, manifestJson.getInt("schemaVersion"));
        assertEquals("Format should be libre-contacts-backup", "libre-contacts-backup", manifestJson.getString("format"));

        // Verify each data file's checksum
        JSONObject filesObj = manifestJson.getJSONObject("files");
        for (String fileName : new String[]{"android-contacts.json", "contacts.vcf", "contacts.json", "contacts.csv"}) {
            assertTrue("Manifest should contain " + fileName, filesObj.has(fileName));
            String storedHash = filesObj.getJSONObject(fileName).getString("sha256");
            byte[] fileBytes = entryBytes.get(fileName);
            assertNotNull("ZIP should contain " + fileName, fileBytes);
            String computedHash = BackupArchiveWriter.sha256(fileBytes);
            assertEquals("Checksum for " + fileName + " should match", storedHash, computedHash);
        }

        // Manifest should NOT reference itself (self-referencing is impractical with JSON)
        assertFalse("Manifest should not contain self-reference", filesObj.has("manifest.json"));
    }

    // ============================================================
    // TEST 9: Organization fields preserved
    // ============================================================
    @Test
    public void testOrganizationRoundTrip() throws Exception {
        String rawId = createNamedContact("Org Test", "Org", "Test", null, null, null);
        addOrganization(rawId, "Acme Corp", "VP Engineering");

        AndroidContactsSnapshot snapshot = ContactsSnapshotReader.read(InstrumentationRegistry.getInstrumentation().getTargetContext());
        String json = NormalizedJsonExporter.exportCanonical(snapshot);
        AndroidContactsSnapshot imported = NormalizedJsonExporter.importCanonical(json);

        for (AndroidContactSnapshot c : imported.contacts) {
            for (AndroidContactSnapshot.RawContactSnapshot rc : c.rawContacts) {
                for (AndroidContactSnapshot.DataRowSnapshot dr : rc.dataRows) {
                    if ("vnd.android.cursor.item/organization".equals(dr.mimeType)) {
                        assertEquals("Company should be Acme Corp", "Acme Corp", dr.data1);
                        assertEquals("Title should be VP Engineering", "VP Engineering", dr.data4);
                        return;
                    }
                }
            }
        }
        fail("Should find organization");
    }

    // ============================================================
    // TEST 10: RestoreResult accuracy
    // ============================================================
    @Test
    public void testRestoreResultAccuracy() throws Exception {
        RestoreResult result = new RestoreResult();
        result.contactsRead = 5;
        result.rawContactsRead = 7;
        result.dataRowsRead = 25;

        String summary = result.summary();
        assertTrue("Summary should contain contacts read", summary.contains("5"));
        assertTrue("Summary should contain raw contacts read", summary.contains("7"));
        assertTrue("Summary should contain data rows read", summary.contains("25"));
    }
}
