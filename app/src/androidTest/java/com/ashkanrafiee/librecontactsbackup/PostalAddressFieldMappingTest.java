package com.ashkanrafiee.librecontactsbackup;

import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.database.Cursor;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal;

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
 * Dedicated regression test for spec section 12: verifies every
 * StructuredPostal field independently survives backup + restore, using the
 * real Android SDK semantic constants (StructuredPostal.FORMATTED_ADDRESS,
 * .STREET, .POBOX, etc.) as ground truth on BOTH the insert side and the
 * verification side. This deliberately avoids hand-copying a DATA1..DATA15
 * index table (that is exactly how a swapped-field bug can end up shared
 * between the test fixture and production code) — every field name here
 * resolves to whatever DATA column the installed Android SDK actually uses.
 *
 * Each of the ten fields gets a distinct value so a swap between any two
 * fields (e.g. STREET vs NEIGHBORHOOD) would be caught by this test, not
 * just "the address contains X somewhere".
 */
@RunWith(AndroidJUnit4.class)
public class PostalAddressFieldMappingTest {

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
    }

    private static android.content.Context targetContext() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @Test
    public void testAllStructuredPostalFieldsSurviveIndependently() throws Exception {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, (String) null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, (String) null)
                .build());
        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, "Postal Field Test")
                .build());

        // A CUSTOM type is required for LABEL to be a meaningful, independently
        // verifiable field (a standard TYPE_HOME/WORK ignores LABEL).
        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, StructuredPostal.CONTENT_ITEM_TYPE)
                .withValue(StructuredPostal.FORMATTED_ADDRESS, "FIELD-FORMATTED-ADDRESS")
                .withValue(StructuredPostal.TYPE, StructuredPostal.TYPE_CUSTOM)
                .withValue(StructuredPostal.LABEL, "FIELD-LABEL")
                .withValue(StructuredPostal.POBOX, "FIELD-POBOX")
                .withValue(StructuredPostal.NEIGHBORHOOD, "FIELD-NEIGHBORHOOD")
                .withValue(StructuredPostal.STREET, "FIELD-STREET")
                .withValue(StructuredPostal.CITY, "FIELD-CITY")
                .withValue(StructuredPostal.REGION, "FIELD-REGION")
                .withValue(StructuredPostal.POSTCODE, "FIELD-POSTCODE")
                .withValue(StructuredPostal.COUNTRY, "FIELD-COUNTRY")
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

        // Verify field-by-field via the real ContactsContract semantic
        // constants directly against the provider — the same ground truth
        // used to insert, not a re-derivation of DATA1..DATA15 indices.
        Cursor cursor = resolver.query(
                ContactsContract.Data.CONTENT_URI,
                new String[]{
                        StructuredPostal.FORMATTED_ADDRESS, StructuredPostal.TYPE, StructuredPostal.LABEL,
                        StructuredPostal.POBOX, StructuredPostal.NEIGHBORHOOD, StructuredPostal.STREET,
                        StructuredPostal.CITY, StructuredPostal.REGION, StructuredPostal.POSTCODE, StructuredPostal.COUNTRY
                },
                ContactsContract.Data.MIMETYPE + "=?",
                new String[]{StructuredPostal.CONTENT_ITEM_TYPE},
                null);
        assertNotNull(cursor);
        try {
            assertTrue("Restored StructuredPostal row should exist", cursor.moveToFirst());
            assertEquals("FIELD-FORMATTED-ADDRESS", cursor.getString(0));
            assertEquals(StructuredPostal.TYPE_CUSTOM, cursor.getInt(1));
            assertEquals("FIELD-LABEL", cursor.getString(2));
            assertEquals("FIELD-POBOX", cursor.getString(3));
            assertEquals("FIELD-NEIGHBORHOOD", cursor.getString(4));
            assertEquals("FIELD-STREET", cursor.getString(5));
            assertEquals("FIELD-CITY", cursor.getString(6));
            assertEquals("FIELD-REGION", cursor.getString(7));
            assertEquals("FIELD-POSTCODE", cursor.getString(8));
            assertEquals("FIELD-COUNTRY", cursor.getString(9));
        } finally {
            cursor.close();
        }
    }
}
