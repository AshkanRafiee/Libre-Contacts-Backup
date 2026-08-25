package com.ashkanrafiee.librecontactsbackup.snapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Root container for a complete lossless snapshot of all contacts
 * from the Android Contacts Provider.
 *
 * This is the canonical representation used inside .lcb archives.
 * It preserves every readable field, every MIME type, every account,
 * and the full Contact → RawContact → Data hierarchy.
 */
public final class AndroidContactsSnapshot {

    public final ArrayList<AndroidContactSnapshot> contacts = new ArrayList<>();

    public AndroidContactsSnapshot() {}

    public void addContact(AndroidContactSnapshot contact) {
        contacts.add(contact);
    }

    public int getContactCount() { return contacts.size(); }

    public int getRawContactCount() {
        int count = 0;
        for (AndroidContactSnapshot c : contacts) count += c.getRawContactCount();
        return count;
    }

    public int getDataRowCount() {
        int count = 0;
        for (AndroidContactSnapshot c : contacts) count += c.getDataRowCount();
        return count;
    }

    public List<AndroidContactSnapshot> getContacts() {
        return Collections.unmodifiableList(contacts);
    }
}
