package com.ashkanrafiee.librecontactsbackup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.ashkanrafiee.librecontactsbackup.export.NormalizedJsonExporter;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactSnapshot.DataRowSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactSnapshot.RawContactSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactsSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.BackupAnalysis;
import com.ashkanrafiee.librecontactsbackup.snapshot.BackupAnalyzer;
import com.ashkanrafiee.librecontactsbackup.snapshot.RestoreCategory;
import com.ashkanrafiee.librecontactsbackup.snapshot.SimContact;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Proves the canonical archive format stays backward-compatible with pre-SIM
 * releases: the new {@code simContacts} key is optional on input, missing
 * keys parse to sensible defaults, and the SIM restore category stays hidden
 * when a backup contains no SIM data.
 */
@RunWith(AndroidJUnit4.class)
public class BackwardCompatibilityTest {

    private static final String MIME_PHONE = "vnd.android.cursor.item/phone_v2";

    private AndroidContactsSnapshot buildAddressBookSnapshot() {
        AndroidContactsSnapshot snapshot = new AndroidContactsSnapshot();
        AndroidContactSnapshot contact = new AndroidContactSnapshot(1, "Alice");
        RawContactSnapshot raw = new RawContactSnapshot(2);
        raw.accountName = "com.google";
        raw.accountType = "com.google";
        DataRowSnapshot phone = new DataRowSnapshot(MIME_PHONE);
        phone.data1 = "+15550000000";
        raw.addDataRow(phone);
        contact.addRawContact(raw);
        snapshot.addContact(contact);
        return snapshot;
    }

    @Test
    public void preSimArchive_contactsPreservedAndSimEmpty() throws Exception {
        // Simulates an android-contacts.json written before SIM support:
        // the "simContacts" key is absent entirely.
        AndroidContactsSnapshot original = buildAddressBookSnapshot();
        JSONObject root = new JSONObject();
        org.json.JSONArray contactsArr = new org.json.JSONArray();
        for (AndroidContactSnapshot c : original.contacts) contactsArr.put(c.toJson());
        root.put("contacts", contactsArr);
        root.put("groups", new org.json.JSONArray());
        String oldJson = root.toString();

        AndroidContactsSnapshot snapshot = NormalizedJsonExporter.importCanonical(oldJson);

        assertEquals(1, snapshot.getContactCount());
        assertEquals("Alice", snapshot.contacts.get(0).displayName);
        assertEquals(0, snapshot.getSimContactCount());
    }

    @Test
    public void preSimArchive_analyzerReportsZeroSimContacts() {
        AndroidContactsSnapshot snapshot = buildAddressBookSnapshot();
        BackupAnalysis analysis = BackupAnalyzer.analyze(snapshot);

        assertEquals(0, analysis.countFor(RestoreCategory.SIM_CONTACTS));
    }

    @Test
    public void minimalSimEntryJson_defaultsSubscriptionAndSlotToNegativeOne() throws JSONException {
        // An entry without subscriptionId or slotIndex — exactly what a leaner
        // future writer (or an older archive retrofitted with a simContacts key)
        // might produce.
        JSONObject entry = new JSONObject();
        entry.put("name", "Eve");
        entry.put("number", "+15550111111");

        SimContact sim = SimContact.fromJson(entry);

        assertFalse(sim.isEmpty());
        assertEquals(-1, sim.subscriptionId);
        assertEquals(-1, sim.slotIndex);
        assertEquals("Eve", sim.name);
        assertEquals("+15550111111", sim.number);
    }

    @Test
    public void fullyEmptySimEntry_isIdentifiedAsEmpty() throws JSONException {
        JSONObject entry = new JSONObject();
        entry.put("name", "");
        entry.put("number", "");

        SimContact sim = SimContact.fromJson(entry);

        assertTrue(sim.isEmpty());
        assertEquals(-1, sim.subscriptionId);
        assertEquals(-1, sim.slotIndex);
    }

    @Test
    public void exportWithSim_reimportsBothAddressBookAndSimEntries() throws Exception {
        AndroidContactsSnapshot snapshot = buildAddressBookSnapshot();
        SimContact sim = new SimContact("Sara", "+15550123");
        sim.subscriptionId = 1;
        sim.slotIndex = 0;
        snapshot.addSimContact(sim);

        String json = NormalizedJsonExporter.exportCanonical(snapshot);
        assertNotNull(json);
        assertTrue("exported JSON must contain the simContacts key", json.contains("\"simContacts\""));

        AndroidContactsSnapshot reimported = NormalizedJsonExporter.importCanonical(json);

        assertEquals(1, reimported.getContactCount());
        assertEquals(1, reimported.getSimContactCount());
        SimContact restoredSim = reimported.simContacts.get(0);
        assertEquals("Sara", restoredSim.name);
        assertEquals("+15550123", restoredSim.number);
        assertEquals(1, restoredSim.subscriptionId);
        assertEquals(0, restoredSim.slotIndex);
    }

    @Test
    public void exportCanonical_containsAllTopLevelKeys() throws Exception {
        String json = NormalizedJsonExporter.exportCanonical(new AndroidContactsSnapshot());
        JSONObject root = new JSONObject(json);
        assertTrue(root.has("contacts"));
        assertTrue(root.has("groups"));
        assertTrue(root.has("simContacts"));
    }
}
