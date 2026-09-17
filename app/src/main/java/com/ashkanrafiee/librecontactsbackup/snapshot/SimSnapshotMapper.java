package com.ashkanrafiee.librecontactsbackup.snapshot;

import java.util.List;

/**
 * Maps SIM phonebook entries onto a normal snapshot so they can be restored
 * into the device address book through the exact same pipeline as any other
 * contact. Each SIM entry becomes one local (accountless) RawContact with a
 * name and/or phone number data row.
 *
 * The mapping is strictly one-to-one: one {@link SimContact} in, one
 * {@link AndroidContactSnapshot} out, in the same order — callers rely on
 * this correspondence to track which entries landed where.
 */
public final class SimSnapshotMapper {

    private static final String MIME_NAME = "vnd.android.cursor.item/name";
    private static final String MIME_PHONE = "vnd.android.cursor.item/phone_v2";

    private SimSnapshotMapper() {}

    public static AndroidContactsSnapshot toSnapshot(List<SimContact> simContacts) {
        AndroidContactsSnapshot snapshot = new AndroidContactsSnapshot();
        long id = 1;
        for (SimContact sim : simContacts) {
            String name = sim.name != null ? sim.name : "";
            String number = sim.number != null ? sim.number : "";

            AndroidContactSnapshot contact = new AndroidContactSnapshot(id, name);
            AndroidContactSnapshot.RawContactSnapshot raw = new AndroidContactSnapshot.RawContactSnapshot(id);

            if (!name.isEmpty()) {
                AndroidContactSnapshot.DataRowSnapshot nameRow = new AndroidContactSnapshot.DataRowSnapshot(MIME_NAME);
                nameRow.data1 = name;
                raw.addDataRow(nameRow);
            }
            if (!number.isEmpty()) {
                AndroidContactSnapshot.DataRowSnapshot phoneRow = new AndroidContactSnapshot.DataRowSnapshot(MIME_PHONE);
                phoneRow.data1 = number;
                raw.addDataRow(phoneRow);
            }

            contact.addRawContact(raw);
            snapshot.addContact(contact);
            id++;
        }
        return snapshot;
    }
}