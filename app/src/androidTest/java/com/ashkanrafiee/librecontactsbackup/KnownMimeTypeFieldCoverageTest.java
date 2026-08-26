package com.ashkanrafiee.librecontactsbackup;

import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.database.Cursor;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Im;
import android.provider.ContactsContract.CommonDataKinds.Nickname;
import android.provider.ContactsContract.CommonDataKinds.Organization;
import android.provider.ContactsContract.CommonDataKinds.Phone;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.ashkanrafiee.librecontactsbackup.archive.BackupArchiveReader;
import com.ashkanrafiee.librecontactsbackup.archive.BackupArchiveWriter;
import com.ashkanrafiee.librecontactsbackup.archive.ContactsSnapshotRestorer;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression test for fields that were previously captured in the backup but
 * silently dropped on restore because "known MIME type" handling only applied
 * the subset of semantic fields the restorer happened to implement, not every
 * field the real ContactsContract.CommonDataKinds class actually defines:
 *
 * - Organization.TYPE/LABEL/JOB_DESCRIPTION/SYMBOL/PHONETIC_NAME/OFFICE_LOCATION
 * - Nickname.TYPE/LABEL
 * - Im.TYPE/LABEL (independent of PROTOCOL/CUSTOM_PROTOCOL)
 * - Data.IS_PRIMARY / IS_SUPER_PRIMARY (the user's chosen default phone/etc.)
 *
 * As with {@link PostalAddressFieldMappingTest}, both the insert and the
 * verification use the real SDK semantic constants, not a hand-copied DATA
 * column index table, so a future field-mapping regression would be caught
 * here rather than silently reintroduced.
 */
@RunWith(AndroidJUnit4.class)
public class KnownMimeTypeFieldCoverageTest {

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
        try { resolver.delete(ContactsContract.RawContacts.CONTENT_URI, null, null); } catch (Exception e) { /* ignore */ }
    }

    private static android.content.Context targetContext() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @Test
    public void testOrganizationNicknameImAndPrimaryFlagsSurviveRestore() throws Exception {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, (String) null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, (String) null)
                .build());
        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, "Field Coverage Test")
                .build());

        // Two phones: the second explicitly marked as the user's chosen default.
        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                .withValue(Phone.NUMBER, "+1-555-1111")
                .withValue(Phone.TYPE, Phone.TYPE_HOME)
                .build());
        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                .withValue(Phone.NUMBER, "+1-555-2222")
                .withValue(Phone.TYPE, Phone.TYPE_MOBILE)
                .withValue(ContactsContract.Data.IS_SUPER_PRIMARY, 1)
                .withValue(ContactsContract.Data.IS_PRIMARY, 1)
                .build());

        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, Organization.CONTENT_ITEM_TYPE)
                .withValue(Organization.COMPANY, "FIELD-COMPANY")
                .withValue(Organization.TYPE, Organization.TYPE_CUSTOM)
                .withValue(Organization.LABEL, "FIELD-ORG-LABEL")
                .withValue(Organization.TITLE, "FIELD-TITLE")
                .withValue(Organization.DEPARTMENT, "FIELD-DEPARTMENT")
                .withValue(Organization.JOB_DESCRIPTION, "FIELD-JOB-DESC")
                .withValue(Organization.SYMBOL, "FIELD-SYMBOL")
                .withValue(Organization.PHONETIC_NAME, "FIELD-PHONETIC-ORG")
                .withValue(Organization.OFFICE_LOCATION, "FIELD-OFFICE")
                .build());

        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, Nickname.CONTENT_ITEM_TYPE)
                .withValue(Nickname.NAME, "FIELD-NICK")
                .withValue(Nickname.TYPE, Nickname.TYPE_OTHER_NAME)
                .build());

        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, Im.CONTENT_ITEM_TYPE)
                .withValue(Im.DATA, "FIELD-IM-HANDLE")
                .withValue(Im.TYPE, Im.TYPE_CUSTOM)
                .withValue(Im.LABEL, "FIELD-IM-LABEL")
                .withValue(Im.PROTOCOL, Im.PROTOCOL_CUSTOM)
                .withValue(Im.CUSTOM_PROTOCOL, "FIELD-CUSTOM-PROTOCOL")
                .build());

        resolver.applyBatch(ContactsContract.AUTHORITY, ops);
        Thread.sleep(300);

        AndroidContactsSnapshot original = ContactsSnapshotReader.read(targetContext());
        assertEquals(1, original.getContactCount());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BackupArchiveWriter.writeArchive(targetContext(), original, baos);
        BackupArchiveReader.ArchiveData archiveData;
        try (InputStream is = new ByteArrayInputStream(baos.toByteArray())) {
            archiveData = BackupArchiveReader.readArchive(is);
        }
        assertTrue("Archive should be lossless", archiveData.isLossless());
        assertTrue("Checksum should be valid", archiveData.checksumValid);

        cleanupContacts();
        Thread.sleep(300);

        ContactsSnapshotRestorer.RestoreProgress progress = (msg, cur, total) -> {};
        ContactsSnapshotRestorer.restoreExact(targetContext(), archiveData.snapshot, progress);
        Thread.sleep(500);

        // --- Organization: every field, verified via the real SDK constants ---
        Cursor c = resolver.query(ContactsContract.Data.CONTENT_URI,
                new String[]{Organization.COMPANY, Organization.TYPE, Organization.LABEL, Organization.TITLE,
                        Organization.DEPARTMENT, Organization.JOB_DESCRIPTION, Organization.SYMBOL,
                        Organization.PHONETIC_NAME, Organization.OFFICE_LOCATION},
                ContactsContract.Data.MIMETYPE + "=?", new String[]{Organization.CONTENT_ITEM_TYPE}, null);
        assertNotNull(c);
        try {
            assertTrue("Organization row should exist after restore", c.moveToFirst());
            assertEquals("FIELD-COMPANY", c.getString(0));
            assertEquals(Organization.TYPE_CUSTOM, c.getInt(1));
            assertEquals("FIELD-ORG-LABEL", c.getString(2));
            assertEquals("FIELD-TITLE", c.getString(3));
            assertEquals("FIELD-DEPARTMENT", c.getString(4));
            assertEquals("FIELD-JOB-DESC", c.getString(5));
            assertEquals("FIELD-SYMBOL", c.getString(6));
            assertEquals("FIELD-PHONETIC-ORG", c.getString(7));
            assertEquals("FIELD-OFFICE", c.getString(8));
        } finally {
            c.close();
        }

        // --- Nickname: NAME + TYPE ---
        c = resolver.query(ContactsContract.Data.CONTENT_URI,
                new String[]{Nickname.NAME, Nickname.TYPE},
                ContactsContract.Data.MIMETYPE + "=?", new String[]{Nickname.CONTENT_ITEM_TYPE}, null);
        assertNotNull(c);
        try {
            assertTrue("Nickname row should exist after restore", c.moveToFirst());
            assertEquals("FIELD-NICK", c.getString(0));
            assertEquals(Nickname.TYPE_OTHER_NAME, c.getInt(1));
        } finally {
            c.close();
        }

        // --- Im: DATA + TYPE + LABEL, independent of PROTOCOL/CUSTOM_PROTOCOL ---
        c = resolver.query(ContactsContract.Data.CONTENT_URI,
                new String[]{Im.DATA, Im.TYPE, Im.LABEL, Im.PROTOCOL, Im.CUSTOM_PROTOCOL},
                ContactsContract.Data.MIMETYPE + "=?", new String[]{Im.CONTENT_ITEM_TYPE}, null);
        assertNotNull(c);
        try {
            assertTrue("Im row should exist after restore", c.moveToFirst());
            assertEquals("FIELD-IM-HANDLE", c.getString(0));
            assertEquals(0, c.getInt(1)); // custom type placeholder resolves to 0
            assertEquals("FIELD-IM-LABEL", c.getString(2));
            assertEquals(Im.PROTOCOL_CUSTOM, c.getInt(3));
            assertEquals("FIELD-CUSTOM-PROTOCOL", c.getString(4));
        } finally {
            c.close();
        }

        // --- IS_PRIMARY / IS_SUPER_PRIMARY: the user's chosen default phone ---
        c = resolver.query(ContactsContract.Data.CONTENT_URI,
                new String[]{Phone.NUMBER, ContactsContract.Data.IS_PRIMARY, ContactsContract.Data.IS_SUPER_PRIMARY},
                ContactsContract.Data.MIMETYPE + "=? AND " + Phone.NUMBER + "=?",
                new String[]{Phone.CONTENT_ITEM_TYPE, "+1-555-2222"}, null);
        assertNotNull(c);
        try {
            assertTrue("The super-primary phone row should exist after restore", c.moveToFirst());
            assertEquals(1, c.getInt(1));
            assertEquals(1, c.getInt(2));
        } finally {
            c.close();
        }

        // The non-primary phone must not have inherited the primary flag.
        c = resolver.query(ContactsContract.Data.CONTENT_URI,
                new String[]{ContactsContract.Data.IS_PRIMARY, ContactsContract.Data.IS_SUPER_PRIMARY},
                ContactsContract.Data.MIMETYPE + "=? AND " + Phone.NUMBER + "=?",
                new String[]{Phone.CONTENT_ITEM_TYPE, "+1-555-1111"}, null);
        assertNotNull(c);
        try {
            assertTrue(c.moveToFirst());
            assertEquals(0, c.getInt(0));
            assertEquals(0, c.getInt(1));
        } finally {
            c.close();
        }
    }
}
