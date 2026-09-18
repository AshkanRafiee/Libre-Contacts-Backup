package com.ashkanrafiee.librecontactsbackup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.ashkanrafiee.librecontactsbackup.archive.BackupArchiveReader;
import com.ashkanrafiee.librecontactsbackup.archive.BackupArchiveWriter;
import com.ashkanrafiee.librecontactsbackup.archive.ContactsSnapshotRestorer;
import com.ashkanrafiee.librecontactsbackup.export.NormalizedJsonExporter;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactsSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.RestoreCategory;
import com.ashkanrafiee.librecontactsbackup.snapshot.RestoreOptions;
import com.ashkanrafiee.librecontactsbackup.snapshot.RestoreResult;
import com.ashkanrafiee.librecontactsbackup.snapshot.SimContact;
import com.ashkanrafiee.librecontactsbackup.snapshot.SimRestoreDestination;
import com.ashkanrafiee.librecontactsbackup.snapshot.SimTarget;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Tests for SIM card contact backup/restore: canonical-archive round-trip
 * (with backward compatibility for archives written without SIM support) and
 * the restore destinations — device, SIM card, and both.
 *
 * The test device may or may not have a usable SIM phonebook (the emulator
 * exposes a virtual SIM card), so SIM-card write-back assertions are
 * deliberately invariant-shaped: each entry must either land on the SIM, land
 * in the device address book (the defined fallback), or be reported as a
 * failure — never silently vanish.
 */
@RunWith(AndroidJUnit4.class)
public class SimBackupRestoreTest {

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
        Uri syncAdapterUri = ContactsContract.RawContacts.CONTENT_URI.buildUpon()
                .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                .build();
        try { resolver.delete(syncAdapterUri, null, null); } catch (Exception e) { /* ignore */ }
        try { resolver.delete(ContactsContract.Groups.CONTENT_URI, null, null); } catch (Exception e) { /* ignore */ }
    }

    private static android.content.Context targetContext() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private static AndroidContactsSnapshot twoEntrySimSnapshot() {
        AndroidContactsSnapshot snapshot = new AndroidContactsSnapshot();
        snapshot.addSimContact(new SimContact("Sara Sim", "+15550123"));
        SimContact bob = new SimContact("Bob Sim", "+15550987");
        bob.subscriptionId = 1;
        bob.slotIndex = 0;
        snapshot.addSimContact(bob);
        return snapshot;
    }

    @Test public void canonical_round_trip_preserves_sim_contacts() throws Exception {
        AndroidContactsSnapshot snapshot = twoEntrySimSnapshot();

        String json = NormalizedJsonExporter.exportCanonical(snapshot);
        JSONObject root = new JSONObject(json);
        JSONArray simArr = root.getJSONArray("simContacts");
        assertEquals(2, simArr.length());
        assertEquals("Sara Sim", simArr.getJSONObject(0).getString("name"));
        assertEquals("+15550123", simArr.getJSONObject(0).getString("number"));
        assertEquals("Bob Sim", simArr.getJSONObject(1).getString("name"));
        assertEquals(1, simArr.getJSONObject(1).getInt("subscriptionId"));
        assertEquals(0, simArr.getJSONObject(1).getInt("slotIndex"));

        AndroidContactsSnapshot imported = NormalizedJsonExporter.importCanonical(json);
        assertEquals(0, imported.getContactCount());
        assertEquals(0, imported.getGroups().size());
        assertEquals(2, imported.getSimContactCount());
        SimContact sara = imported.getSimContacts().get(0);
        assertEquals("Sara Sim", sara.name);
        assertEquals("+15550123", sara.number);
        assertEquals(-1, sara.subscriptionId);
        assertEquals(-1, sara.slotIndex);
        SimContact bob = imported.getSimContacts().get(1);
        assertEquals("Bob Sim", bob.name);
        assertEquals("+15550987", bob.number);
        assertEquals(1, bob.subscriptionId);
        assertEquals(0, bob.slotIndex);
    }

    @Test public void canonical_json_without_sim_key_is_backward_compatible() throws Exception {
        AndroidContactsSnapshot imported = NormalizedJsonExporter.importCanonical("{\"contacts\":[],\"groups\":[]}");
        assertEquals(0, imported.getSimContactCount());

        // Legacy bare-array snapshot (pre-object-canonical format).
        imported = NormalizedJsonExporter.importCanonical("[]");
        assertEquals(0, imported.getSimContactCount());
        assertEquals(0, imported.getContactCount());
    }

    @Test public void canonical_strings_match_after_round_trip() throws Exception {
        String before = NormalizedJsonExporter.exportCanonical(twoEntrySimSnapshot());
        AndroidContactsSnapshot imported = NormalizedJsonExporter.importCanonical(before);
        String after = NormalizedJsonExporter.exportCanonical(imported);
        assertEquals("Canonical JSON must be byte-identical after a round trip", before, after);
    }

    @Test public void sim_contacts_survive_the_full_archive_round_trip() throws Exception {
        AndroidContactsSnapshot original = twoEntrySimSnapshot();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BackupArchiveWriter.writeArchive(targetContext(), original, baos);

        BackupArchiveReader.ArchiveData archiveData;
        try (InputStream is = new ByteArrayInputStream(baos.toByteArray())) {
            archiveData = BackupArchiveReader.readArchive(is);
        }
        assertNotNull("Archive must be readable", archiveData.snapshot);
        assertEquals("the .lcb archive must carry the SIM entries", 2, archiveData.snapshot.getSimContactCount());
        SimContact sara = archiveData.snapshot.getSimContacts().get(0);
        assertEquals("Sara Sim", sara.name);
        assertEquals("+15550123", sara.number);
        assertEquals(-1, sara.subscriptionId);
        SimContact bob = archiveData.snapshot.getSimContacts().get(1);
        assertEquals("Bob Sim", bob.name);
        assertEquals("+15550987", bob.number);
        assertEquals(1, bob.subscriptionId);
        assertEquals(0, bob.slotIndex);

        RestoreResult result = ContactsSnapshotRestorer.restore(targetContext(), archiveData.snapshot,
                RestoreOptions.of(RestoreCategory.SIM_CONTACTS), SimRestoreDestination.DEVICE, (m, c, t) -> {});
        assertEquals(2, result.simContactsRead);
        assertEquals(2, result.simContactsRestoredDevice);
        assertEquals(0, result.simRestoreFailed);
        assertTrue("SIM numbers must land in the address book after an archive round trip",
                providerHasNumber("+15550123") && providerHasNumber("+15550987"));
    }

    @Test public void restore_to_device_creates_local_contacts() throws Exception {
        AndroidContactsSnapshot snapshot = twoEntrySimSnapshot();

        RestoreResult result = ContactsSnapshotRestorer.restore(targetContext(), snapshot,
                RestoreOptions.of(RestoreCategory.SIM_CONTACTS), SimRestoreDestination.DEVICE, (m, c, t) -> {});

        assertEquals(2, result.simContactsRead);
        assertEquals(2, result.simContactsRestoredDevice);
        assertEquals(0, result.simRestoreFailed);
        assertEquals(2, result.contactsCreated);
        assertEquals(2, result.rawContactsCreated);

        assertTrue("both SIM numbers must now be present in the address book",
                providerHasNumber("+15550123") && providerHasNumber("+15550987"));
        assertEquals("exactly two raw contacts restored", 2, rawContactCount());
    }

    @Test public void restore_to_sim_is_best_effort_with_defined_fallback() throws Exception {
        AndroidContactsSnapshot snapshot = new AndroidContactsSnapshot();
        snapshot.addSimContact(new SimContact("Sara Sim", "+15550123"));

        RestoreResult result = ContactsSnapshotRestorer.restore(targetContext(), snapshot,
                RestoreOptions.of(RestoreCategory.SIM_CONTACTS), SimRestoreDestination.SIM_CARD, (m, c, t) -> {});

        assertEquals(1, result.simContactsRead);
        // The single entry must land on the SIM, land on the device (the
        // no-data-loss fallback for a full/unsupported SIM), or be reported
        // as failed — never more than one of those, never zero.
        int landed = result.simContactsRestoredSim + result.simContactsRestoredDevice + result.simRestoreFailed;
        assertEquals("the SIM entry must end up somewhere or be reported", 1, landed);
    }

    @Test public void restore_to_sim_never_writes_a_known_card_to_a_different_sim() throws Exception {
        AndroidContactsSnapshot snapshot = new AndroidContactsSnapshot();
        SimContact sim = new SimContact("Old Card Sara", "+15550123");
        // A subscription this device does not currently have: the entry must
        // NOT silently land on whichever SIM happens to be inserted now.
        sim.subscriptionId = 999999;
        snapshot.addSimContact(sim);

        RestoreResult result = ContactsSnapshotRestorer.restore(targetContext(), snapshot,
                RestoreOptions.of(RestoreCategory.SIM_CONTACTS), SimRestoreDestination.SIM_CARD, (m, c, t) -> {});

        assertEquals(1, result.simContactsRead);
        assertEquals("a known-absent card must not redirect the entry to another SIM",
                0, result.simContactsRestoredSim);
        assertTrue("the entry must fall back to the device or be reported, never vanish",
                result.simContactsRestoredDevice == 1 || result.simRestoreFailed == 1);
    }

    @Test public void restore_to_both_writes_device_copy_and_attempts_sim() throws Exception {
        AndroidContactsSnapshot snapshot = new AndroidContactsSnapshot();
        snapshot.addSimContact(new SimContact("Sara Sim", "+15550123"));

        RestoreResult result = ContactsSnapshotRestorer.restore(targetContext(), snapshot,
                RestoreOptions.of(RestoreCategory.SIM_CONTACTS), SimRestoreDestination.BOTH, (m, c, t) -> {});

        assertEquals(1, result.simContactsRead);
        assertEquals("the device copy is unconditional", 1, result.simContactsRestoredDevice);
        // SIM write: either it landed on the card, or the failure is surfaced.
        assertTrue("SIM write must either succeed or be reported as a warning",
                result.simContactsRestoredSim == 1 || result.hasWarnings());
    }

    @Test public void sim_contacts_only_are_ignored_when_category_skipped() throws Exception {
        AndroidContactsSnapshot snapshot = twoEntrySimSnapshot();

        RestoreResult result = ContactsSnapshotRestorer.restore(targetContext(), snapshot,
                RestoreOptions.of(RestoreCategory.CONTACT_INFO), SimRestoreDestination.BOTH, (m, c, t) -> {});

        assertEquals(0, result.simContactsRead + result.simContactsRestoredDevice
                + result.simContactsRestoredSim + result.simRestoreFailed);
        assertEquals(0, rawContactCount());
    }

    @Test public void restore_to_a_chosen_target_card_redirects_every_entry() throws Exception {
        AndroidContactsSnapshot snapshot = twoEntrySimSnapshot();

        // An explicit target redirects ALL entries to that single card — even
        // entries whose original card is recorded elsewhere. An absent target
        // must leave nothing on any currently inserted card.
        RestoreResult result = ContactsSnapshotRestorer.restore(targetContext(), snapshot,
                RestoreOptions.of(RestoreCategory.SIM_CONTACTS), SimRestoreDestination.SIM_CARD,
                999999, (m, c, t) -> {});

        assertEquals(2, result.simContactsRead);
        assertEquals("redirected entries must not land on any inserted card",
                0, result.simContactsRestoredSim);
        int rescued = result.simContactsRestoredDevice + result.simRestoreFailed;
        assertEquals("every entry must fall back to the device or be reported", 2, rescued);
    }

    @Test public void original_cards_target_is_the_default_of_the_public_overload() throws Exception {
        AndroidContactsSnapshot snapshot = new AndroidContactsSnapshot();
        snapshot.addSimContact(new SimContact("Sara Sim", "+15550123"));

        RestoreResult via5arg = ContactsSnapshotRestorer.restore(targetContext(), snapshot,
                RestoreOptions.of(RestoreCategory.SIM_CONTACTS), SimRestoreDestination.SIM_CARD, (m, c, t) -> {});
        RestoreResult via6arg = ContactsSnapshotRestorer.restore(targetContext(), snapshot,
                RestoreOptions.of(RestoreCategory.SIM_CONTACTS), SimRestoreDestination.SIM_CARD,
                SimTarget.ORIGINAL_CARDS, (m, c, t) -> {});

        assertEquals("the 5-arg restore must behave exactly like the original-cards target",
                via5arg.simContactsRestoredSim, via6arg.simContactsRestoredSim);
        assertEquals(via5arg.simContactsRestoredDevice, via6arg.simContactsRestoredDevice);
        assertEquals(via5arg.simRestoreFailed, via6arg.simRestoreFailed);
        assertEquals(1, via6arg.simContactsRead);
    }

    private boolean providerHasNumber(String number) {
        Cursor cursor = resolver.query(ContactsContract.Data.CONTENT_URI,
                new String[]{ContactsContract.Data.DATA1},
                ContactsContract.Data.MIMETYPE + "=?",
                new String[]{"vnd.android.cursor.item/phone_v2"}, null);
        if (cursor == null) return false;
        try {
            while (cursor.moveToNext()) {
                if (number.equals(cursor.getString(0))) return true;
            }
            return false;
        } finally {
            cursor.close();
        }
    }

    private int rawContactCount() {
        Cursor cursor = resolver.query(ContactsContract.RawContacts.CONTENT_URI,
                new String[]{ContactsContract.RawContacts._ID}, null, null, null);
        if (cursor == null) return 0;
        try {
            return cursor.getCount();
        } finally {
            cursor.close();
        }
    }
}