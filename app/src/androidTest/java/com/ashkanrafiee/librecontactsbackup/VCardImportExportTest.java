package com.ashkanrafiee.librecontactsbackup;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.ashkanrafiee.librecontactsbackup.export.VCardExporter;
import com.ashkanrafiee.librecontactsbackup.export.VCardImporter;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactSnapshot.DataRowSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactSnapshot.RawContactSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactsSnapshot;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * VCardImporter/VCardExporter operate on plain strings with no Contacts
 * Provider dependency, so these run without any device contacts setup.
 * Covers real parsing bugs found in review: FN+N must merge into one name
 * row (not two), ORG+TITLE into one organization row, and an escaped
 * literal semicolon in a compound field (N/ADR/ORG) must not be mistaken
 * for a field separator.
 */
@RunWith(AndroidJUnit4.class)
public class VCardImportExportTest {

    private static ArrayList<DataRowSnapshot> rowsOfType(AndroidContactsSnapshot snapshot, String mimeType) {
        ArrayList<DataRowSnapshot> result = new ArrayList<>();
        for (AndroidContactSnapshot c : snapshot.contacts) {
            for (RawContactSnapshot rc : c.rawContacts) {
                for (DataRowSnapshot row : rc.dataRows) {
                    if (mimeType.equals(row.mimeType)) result.add(row);
                }
            }
        }
        return result;
    }

    @Test
    public void fnAndNMergeIntoOneNameRow_fnWins() {
        String vcf = "BEGIN:VCARD\r\nVERSION:3.0\r\n"
                + "N:Smith;John;;;\r\n"
                + "FN:Dr. John Smith\r\n"
                + "END:VCARD\r\n";

        AndroidContactsSnapshot snapshot = VCardImporter.importVcf(vcf);
        ArrayList<DataRowSnapshot> nameRows = rowsOfType(snapshot, "vnd.android.cursor.item/name");

        assertEquals("FN and N must merge into exactly one name row", 1, nameRows.size());
        assertEquals("FN must win as the display name over N's synthesized fallback",
                "Dr. John Smith", nameRows.get(0).data1);
        assertEquals("John", nameRows.get(0).data2);
        assertEquals("Smith", nameRows.get(0).data3);
    }

    @Test
    public void nBeforeFn_stillMergesWithFnWinning() {
        // Same as above but with N appearing first, to prove the merge
        // doesn't depend on property order.
        String vcf = "BEGIN:VCARD\r\nVERSION:3.0\r\n"
                + "FN:Dr. Jane Doe\r\n"
                + "N:Doe;Jane;;;\r\n"
                + "END:VCARD\r\n";

        AndroidContactsSnapshot snapshot = VCardImporter.importVcf(vcf);
        ArrayList<DataRowSnapshot> nameRows = rowsOfType(snapshot, "vnd.android.cursor.item/name");

        assertEquals(1, nameRows.size());
        assertEquals("Dr. Jane Doe", nameRows.get(0).data1);
    }

    @Test
    public void orgAndTitleMergeIntoOneOrganizationRow() {
        String vcf = "BEGIN:VCARD\r\nVERSION:3.0\r\n"
                + "FN:Work Person\r\n"
                + "ORG:Acme Corp\r\n"
                + "TITLE:VP of Engineering\r\n"
                + "END:VCARD\r\n";

        AndroidContactsSnapshot snapshot = VCardImporter.importVcf(vcf);
        ArrayList<DataRowSnapshot> orgRows = rowsOfType(snapshot, "vnd.android.cursor.item/organization");

        assertEquals("ORG and TITLE must merge into exactly one organization row", 1, orgRows.size());
        assertEquals("Acme Corp", orgRows.get(0).data1);
        assertEquals("VP of Engineering", orgRows.get(0).data4);
    }

    @Test
    public void escapedSemicolonInAdrIsNotTreatedAsFieldSeparator() {
        // A street address containing a literal, escaped semicolon must
        // survive as one field, not be split into two.
        String vcf = "BEGIN:VCARD\r\nVERSION:3.0\r\n"
                + "FN:Address Test\r\n"
                + "ADR:;;123 Main St\\, Suite 4\\; Building B;Springfield;IL;62704;USA\r\n"
                + "END:VCARD\r\n";

        AndroidContactsSnapshot snapshot = VCardImporter.importVcf(vcf);
        ArrayList<DataRowSnapshot> adrRows = rowsOfType(snapshot, "vnd.android.cursor.item/postal-address_v2");

        assertEquals(1, adrRows.size());
        assertEquals("Escaped semicolon inside the street field must not split it",
                "123 Main St, Suite 4; Building B", adrRows.get(0).data4);
        assertEquals("Springfield", adrRows.get(0).data7);
        assertEquals("IL", adrRows.get(0).data8);
    }

    @Test
    public void bareTextContainingBeginVcardIsNotMistakenForACardBoundary() {
        // A NOTE containing the literal text "BEGIN:VCARD" (e.g. someone
        // pasted vCard-like text into their notes) must not fracture parsing.
        String vcf = "BEGIN:VCARD\r\nVERSION:3.0\r\n"
                + "FN:Note Test\r\n"
                + "NOTE:Remember to say BEGIN:VCARD is a real property line\r\n"
                + "END:VCARD\r\n";

        AndroidContactsSnapshot snapshot = VCardImporter.importVcf(vcf);

        assertEquals("Exactly one contact must be parsed", 1, snapshot.getContactCount());
        ArrayList<DataRowSnapshot> noteRows = rowsOfType(snapshot, "vnd.android.cursor.item/note");
        assertEquals(1, noteRows.size());
        assertTrue(noteRows.get(0).data1.contains("BEGIN:VCARD"));
    }

    @Test
    public void phoneTypeRoundTripsCorrectly() throws Exception {
        AndroidContactsSnapshot snapshot = new AndroidContactsSnapshot();
        AndroidContactSnapshot contact = new AndroidContactSnapshot(1, "Phone Type Person");
        RawContactSnapshot rc = new RawContactSnapshot(1);
        DataRowSnapshot name = new DataRowSnapshot("vnd.android.cursor.item/name");
        name.data1 = "Phone Type Person";
        rc.addDataRow(name);
        DataRowSnapshot mobile = new DataRowSnapshot("vnd.android.cursor.item/phone_v2");
        mobile.data1 = "+1-555-0100";
        mobile.data2 = "2"; // Phone.TYPE_MOBILE
        rc.addDataRow(mobile);
        contact.addRawContact(rc);
        snapshot.addContact(contact);

        String vcf = VCardExporter.exportVcf(snapshot);
        assertTrue("Exported vCard must tag a real mobile number as CELL, not WORK",
                vcf.contains("TEL;TYPE=CELL:+1-555-0100"));

        AndroidContactsSnapshot reimported = VCardImporter.importVcf(vcf);
        ArrayList<DataRowSnapshot> phones = rowsOfType(reimported, "vnd.android.cursor.item/phone_v2");
        assertEquals(1, phones.size());
        assertEquals("Re-imported phone must round-trip back to Phone.TYPE_MOBILE (2), not -1",
                "2", phones.get(0).data2);
    }
}
