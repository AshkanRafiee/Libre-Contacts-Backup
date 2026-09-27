package com.ashkanrafiee.librecontactsbackup;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.ashkanrafiee.librecontactsbackup.export.VCardExporter;
import com.ashkanrafiee.librecontactsbackup.export.VCardImporter;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactSnapshot.DataRowSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactSnapshot.RawContactSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactsSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactsSnapshot.GroupSnapshot;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * VCardImporter/VCardExporter operate on plain strings with no Contacts
 * Provider dependency, so these run without any device contacts setup.
 * Covers real parsing bugs found in review: FN+N must merge into one name
 * row (not two), ORG+TITLE into one organization row, and an escaped
 * literal semicolon in a compound field (N/ADR/ORG) must not be mistaken
 * for a field separator.
 *
 * Group membership is covered too: a group_membership row references its
 * group by a provider row ID, which is meaningless outside the device it
 * came from, so the export must carry the group's name (vCard CATEGORIES)
 * and the import must turn those names back into real groups.
 */
@RunWith(AndroidJUnit4.class)
public class VCardImportExportTest {

    private static final String MIME_GROUP_MEMBERSHIP = "vnd.android.cursor.item/group_membership";
    private static final String RAW_GROUP_ID_PROPERTY = "X-ANDROID-vnd.android.cursor.item.group_membership";

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

    /** One contact with a name and one raw contact belonging to each of {@code groupRowIds}, in order. */
    private static AndroidContactsSnapshot snapshotInGroups(String displayName, long... groupRowIds) {
        AndroidContactsSnapshot snapshot = new AndroidContactsSnapshot();
        AndroidContactSnapshot contact = new AndroidContactSnapshot(1, displayName);
        RawContactSnapshot rc = new RawContactSnapshot(1);
        DataRowSnapshot name = new DataRowSnapshot("vnd.android.cursor.item/name");
        name.data1 = displayName;
        rc.addDataRow(name);
        for (long groupRowId : groupRowIds) {
            DataRowSnapshot membership = new DataRowSnapshot(MIME_GROUP_MEMBERSHIP);
            membership.data1 = String.valueOf(groupRowId);
            rc.addDataRow(membership);
        }
        contact.addRawContact(rc);
        snapshot.addContact(contact);
        return snapshot;
    }

    private static void addGroup(AndroidContactsSnapshot snapshot, long groupId, String title) {
        GroupSnapshot group = new GroupSnapshot();
        group.groupId = groupId;
        group.title = title;
        snapshot.addGroup(group);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int from = text.indexOf(needle); from >= 0; from = text.indexOf(needle, from + needle.length())) {
            count++;
        }
        return count;
    }

    private static int countMatching(ArrayList<DataRowSnapshot> rows, String value) {
        int count = 0;
        for (DataRowSnapshot row : rows) {
            if (value.equals(row.data1)) count++;
        }
        return count;
    }

    private static String categoriesLineOf(String vcf) {
        for (String line : vcf.split("\r?\n")) {
            if (line.startsWith("CATEGORIES:")) return line;
        }
        return null;
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

    @Test
    public void groupMembershipExportsTheGroupName_notItsRowId() {
        // A group_membership row only holds the group's row ID — a number from
        // this device's Groups table that means nothing anywhere else. The file
        // is worthless if it says "7" and never says what group 7 is.
        AndroidContactsSnapshot snapshot = snapshotInGroups("Grouped Person", 7);
        addGroup(snapshot, 7, "Family");

        String vcf = VCardExporter.exportVcf(snapshot);

        assertTrue("The group must be exported under its actual name",
                vcf.contains("CATEGORIES:Family"));
        assertFalse("The provider group row ID must not be passed off as a group's identity",
                vcf.contains(RAW_GROUP_ID_PROPERTY));
    }

    @Test
    public void everyGroupOfAContactGoesIntoOneCategoriesProperty() {
        AndroidContactsSnapshot snapshot = snapshotInGroups("Busy Person", 3, 4);
        addGroup(snapshot, 3, "Work");
        addGroup(snapshot, 4, "Family");

        String vcf = VCardExporter.exportVcf(snapshot);

        assertTrue("Both groups belong in the single CATEGORIES text-list, in order",
                vcf.contains("CATEGORIES:Work,Family"));
        assertEquals("One CATEGORIES line per card, not one per group",
                1, occurrences(vcf, "CATEGORIES:"));
    }

    @Test
    public void groupSharedBySeveralRawContactsIsListedOnce() {
        // One visible contact can be made of several raw contacts (e.g. a
        // Google one and a device one) that each carry their own membership row
        // for the same group. It is still one group on the contact.
        AndroidContactsSnapshot snapshot = snapshotInGroups("Synced Person", 3);
        RawContactSnapshot second = new RawContactSnapshot(2);
        DataRowSnapshot membership = new DataRowSnapshot(MIME_GROUP_MEMBERSHIP);
        membership.data1 = "3";
        second.addDataRow(membership);
        snapshot.contacts.get(0).addRawContact(second);
        addGroup(snapshot, 3, "Work");

        String vcf = VCardExporter.exportVcf(snapshot);

        assertEquals("A group the contact belongs to through two raw contacts is still one category",
                "CATEGORIES:Work", categoriesLineOf(vcf));
    }

    @Test
    public void groupIdUnknownToTheSnapshotIsKeptVerbatim() {
        // Nothing is lost when a membership can't be resolved: the row is
        // still written, exactly as any other unrecognized field is.
        String vcf = VCardExporter.exportVcf(snapshotInGroups("Orphaned Person", 99));

        assertTrue("An unresolvable membership must still reach the file",
                vcf.contains(RAW_GROUP_ID_PROPERTY + ":99"));
        assertNull("No group name is known, so none can be written", categoriesLineOf(vcf));
    }

    @Test
    public void groupWithNoTitleDoesNotProduceAnEmptyCategory() {
        for (String title : new String[]{null, "", "   "}) {
            AndroidContactsSnapshot snapshot = snapshotInGroups("Unnamed Group Person", 5);
            addGroup(snapshot, 5, title);

            String vcf = VCardExporter.exportVcf(snapshot);

            assertNull("A group with no name must not emit a CATEGORIES line (title=" + title + ")",
                    categoriesLineOf(vcf));
        }
    }

    @Test
    public void membershipRowWithNoGroupIdIsNotACategory() {
        // A membership row whose group reference is missing or isn't the row ID
        // it claims to be must not become a category out of thin air.
        for (String groupRowId : new String[]{null, "", "   ", "not-a-number"}) {
            AndroidContactsSnapshot snapshot = new AndroidContactsSnapshot();
            AndroidContactSnapshot contact = new AndroidContactSnapshot(1, "Odd Membership Person");
            RawContactSnapshot rc = new RawContactSnapshot(1);
            DataRowSnapshot name = new DataRowSnapshot("vnd.android.cursor.item/name");
            name.data1 = "Odd Membership Person";
            rc.addDataRow(name);
            DataRowSnapshot membership = new DataRowSnapshot(MIME_GROUP_MEMBERSHIP);
            membership.data1 = groupRowId;
            rc.addDataRow(membership);
            contact.addRawContact(rc);
            snapshot.addContact(contact);

            assertNull("A membership with no usable group ID (data1=" + groupRowId
                            + ") must not become a category",
                    categoriesLineOf(VCardExporter.exportVcf(snapshot)));
        }
    }

    @Test
    public void groupNameWithACommaStaysOneCategory() {
        // CATEGORIES is comma-separated, so a comma inside a name has to be
        // escaped or the name is split into two categories on the way out.
        AndroidContactsSnapshot snapshot = snapshotInGroups("Punctuated Person", 8);
        addGroup(snapshot, 8, "Work, friends");

        String vcf = VCardExporter.exportVcf(snapshot);

        assertEquals("The comma inside the name must be escaped, not left to split the list",
                "CATEGORIES:Work\\, friends", categoriesLineOf(vcf));

        AndroidContactsSnapshot reimported = VCardImporter.importVcf(vcf);
        assertEquals("The escaped comma must come back as part of the name",
                1, reimported.getGroups().size());
        assertEquals("Work, friends", reimported.getGroups().get(0).title);
    }

    @Test
    public void escapedGroupNamesRoundTrip() {
        // A name ending in a backslash is the awkward one: exported it becomes
        // an escaped backslash, and the comma that follows is a real separator
        // — not a character the previous backslash escaped.
        AndroidContactsSnapshot snapshot = snapshotInGroups("Escaped Person", 3, 4);
        addGroup(snapshot, 3, "Team\\");
        addGroup(snapshot, 4, "R&D; Core");

        String vcf = VCardExporter.exportVcf(snapshot);
        assertEquals("CATEGORIES:Team\\\\,R&D\\; Core", categoriesLineOf(vcf));

        AndroidContactsSnapshot reimported = VCardImporter.importVcf(vcf);

        assertEquals("Both names must come back as two separate groups", 2, reimported.getGroups().size());
        assertEquals("Team\\", reimported.getGroups().get(0).title);
        assertEquals("R&D; Core", reimported.getGroups().get(1).title);
    }

    @Test
    public void categoriesImportIntoNamedGroupsWithMembershipRows() {
        String vcf = "BEGIN:VCARD\r\nVERSION:3.0\r\n"
                + "FN:Alice\r\n"
                + "CATEGORIES:Work\r\n"
                + "END:VCARD\r\n"
                + "BEGIN:VCARD\r\nVERSION:3.0\r\n"
                + "FN:Bob\r\n"
                + "CATEGORIES:Work,Family\r\n"
                + "END:VCARD\r\n";

        AndroidContactsSnapshot snapshot = VCardImporter.importVcf(vcf);

        assertEquals("Both names, and only those, become groups", 2, snapshot.getGroups().size());
        assertEquals("Work", snapshot.getGroups().get(0).title);
        assertEquals("Family", snapshot.getGroups().get(1).title);

        ArrayList<DataRowSnapshot> memberships = rowsOfType(snapshot, MIME_GROUP_MEMBERSHIP);
        assertEquals("One membership row per category per contact", 3, memberships.size());

        // The same name on two cards is one group with two members, not two
        // groups that a restore would then create and match separately.
        String workId = String.valueOf(snapshot.getGroups().get(0).groupId);
        assertEquals("Both cards' Work memberships point at the one Work group", 2, countMatching(memberships, workId));
        assertEquals(1, countMatching(memberships, String.valueOf(snapshot.getGroups().get(1).groupId)));
    }

    @Test
    public void cardWithOnlyCategoriesIsNotImportedAsANamelessContact() {
        // A membership with no contact to attach it to is a stray reference, not
        // a person: importing it would add a nameless, data-less contact — and
        // the group it names would be left behind empty.
        String vcf = "BEGIN:VCARD\r\nVERSION:3.0\r\n"
                + "CATEGORIES:Work\r\n"
                + "END:VCARD\r\n";

        AndroidContactsSnapshot snapshot = VCardImporter.importVcf(vcf);

        assertEquals("No contact can be built out of group memberships alone", 0, snapshot.getContactCount());
        assertEquals("Nor is a group left over from a card that was never a contact",
                0, snapshot.getGroups().size());
    }

    @Test
    public void anAbsurdlyLongCategoriesValueIsIgnoredRatherThanExpanded() {
        // A crafted file can hold one enormous CATEGORIES line. It must not
        // become a pile of groups on restore; the contact itself still imports.
        StringBuilder names = new StringBuilder();
        while (names.length() < 5000) names.append("Group").append(names.length()).append(',');

        String vcf = "BEGIN:VCARD\r\nVERSION:3.0\r\n"
                + "FN:Bounded Person\r\n"
                + "CATEGORIES:" + names + "\r\n"
                + "END:VCARD\r\n";

        AndroidContactsSnapshot snapshot = VCardImporter.importVcf(vcf);

        assertEquals("The contact is still a contact", 1, snapshot.getContactCount());
        assertEquals("An oversized category list must not be expanded into groups",
                0, snapshot.getGroups().size());
    }

    @Test
    public void groupNamesRoundTripThroughExportAndImport() {
        AndroidContactsSnapshot snapshot = snapshotInGroups("Round Trip Person", 3, 4);
        addGroup(snapshot, 3, "Work");
        addGroup(snapshot, 4, "Family");

        AndroidContactsSnapshot reimported = VCardImporter.importVcf(VCardExporter.exportVcf(snapshot));

        assertEquals("Both groups must survive a trip through the vCard file",
                2, reimported.getGroups().size());
        assertEquals("Work", reimported.getGroups().get(0).title);
        assertEquals("Family", reimported.getGroups().get(1).title);
        assertEquals("Re-exporting must produce the same categories, by name",
                "CATEGORIES:Work,Family", categoriesLineOf(VCardExporter.exportVcf(reimported)));

        ArrayList<DataRowSnapshot> memberships = rowsOfType(reimported, MIME_GROUP_MEMBERSHIP);
        assertEquals(2, memberships.size());
        assertEquals("Each membership must reference one of the file's own groups",
                1, countMatching(memberships, String.valueOf(reimported.getGroups().get(0).groupId)));
        assertEquals(1, countMatching(memberships, String.valueOf(reimported.getGroups().get(1).groupId)));
    }

    @Test
    public void contactWithNoGroupsGetsNoCategoriesProperty() {
        AndroidContactsSnapshot snapshot = new AndroidContactsSnapshot();
        AndroidContactSnapshot contact = new AndroidContactSnapshot(1, "Plain Person");
        RawContactSnapshot rc = new RawContactSnapshot(1);
        DataRowSnapshot name = new DataRowSnapshot("vnd.android.cursor.item/name");
        name.data1 = "Plain Person";
        rc.addDataRow(name);
        contact.addRawContact(rc);
        snapshot.addContact(contact);

        String vcf = VCardExporter.exportVcf(snapshot);

        assertFalse("CATEGORIES is a membership property, not a required one",
                vcf.contains("CATEGORIES:"));

        AndroidContactsSnapshot reimported = VCardImporter.importVcf(vcf);
        assertEquals("A contact with no groups must not gain any on the way back", 0, reimported.getGroups().size());
        assertEquals(0, rowsOfType(reimported, MIME_GROUP_MEMBERSHIP).size());
    }
}
