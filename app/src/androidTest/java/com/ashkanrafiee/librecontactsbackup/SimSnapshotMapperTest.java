package com.ashkanrafiee.librecontactsbackup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactsSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.SimContact;
import com.ashkanrafiee.librecontactsbackup.snapshot.SimSnapshotMapper;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for {@link SimSnapshotMapper}: SIM entries must map onto normal
 * snapshots strictly one-to-one, in order, as accountless RawContacts with
 * name/phone data rows.
 */
@RunWith(AndroidJUnit4.class)
public class SimSnapshotMapperTest {

    private static final String MIME_NAME = "vnd.android.cursor.item/name";
    private static final String MIME_PHONE = "vnd.android.cursor.item/phone_v2";

    @Test public void maps_each_entry_to_one_local_raw_contact_in_order() {
        List<SimContact> sims = new ArrayList<>();
        sims.add(new SimContact("Sara", "+15550123"));
        sims.add(new SimContact("", "+15550987"));
        sims.add(new SimContact("Bob", ""));

        AndroidContactsSnapshot snapshot = SimSnapshotMapper.toSnapshot(sims);

        assertEquals(3, snapshot.getContactCount());
        for (AndroidContactSnapshot contact : snapshot.contacts) {
            assertEquals(1, contact.getRawContactCount());
        }
    }

    @Test public void name_and_number_become_core_data_rows() {
        List<SimContact> sims = new ArrayList<>();
        sims.add(new SimContact("Sara", "+15550123"));

        AndroidContactsSnapshot snapshot = SimSnapshotMapper.toSnapshot(sims);
        AndroidContactSnapshot contact = snapshot.contacts.get(0);
        AndroidContactSnapshot.RawContactSnapshot raw = contact.rawContacts.get(0);

        assertEquals(2, raw.dataRows.size());
        assertEquals(MIME_NAME, raw.dataRows.get(0).mimeType);
        assertEquals("Sara", raw.dataRows.get(0).data1);
        assertEquals(MIME_PHONE, raw.dataRows.get(1).mimeType);
        assertEquals("+15550123", raw.dataRows.get(1).data1);
    }

    @Test public void missing_name_keeps_phone_only() {
        List<SimContact> sims = new ArrayList<>();
        sims.add(new SimContact("", "+15550987"));

        AndroidContactsSnapshot snapshot = SimSnapshotMapper.toSnapshot(sims);
        AndroidContactSnapshot.RawContactSnapshot raw = snapshot.contacts.get(0).rawContacts.get(0);

        assertEquals(1, raw.dataRows.size());
        assertEquals(MIME_PHONE, raw.dataRows.get(0).mimeType);
        assertEquals("+15550987", raw.dataRows.get(0).data1);
    }

    @Test public void missing_number_keeps_name_only() {
        List<SimContact> sims = new ArrayList<>();
        sims.add(new SimContact("Bob", null));

        AndroidContactsSnapshot snapshot = SimSnapshotMapper.toSnapshot(sims);
        AndroidContactSnapshot.RawContactSnapshot raw = snapshot.contacts.get(0).rawContacts.get(0);

        assertEquals(1, raw.dataRows.size());
        assertEquals(MIME_NAME, raw.dataRows.get(0).mimeType);
        assertEquals("Bob", raw.dataRows.get(0).data1);
    }

    @Test public void empty_entries_still_map_one_to_one() {
        List<SimContact> sims = new ArrayList<>();
        sims.add(new SimContact("", ""));
        assertTrue(new SimContact("", "").isEmpty());

        AndroidContactsSnapshot snapshot = SimSnapshotMapper.toSnapshot(new ArrayList<>(sims));
        assertEquals(1, snapshot.getContactCount());
    }
}