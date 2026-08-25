package com.ashkanrafiee.librecontactsbackup.snapshot;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.provider.ContactsContract;

import java.util.LinkedHashMap;

/**
 * Reads a complete, lossless snapshot of the Android Contacts Provider.
 *
 * Preserves the Contact → RawContact → Data hierarchy and captures every
 * readable column, including unknown MIME types and binary data.
 *
 * This reader does NOT filter by known MIME types. It reads everything
 * the provider gives us.
 *
 * Design principle: "Capture the provider row first; interpret it second."
 */
public final class ContactsSnapshotReader {

    private static final String[] CONTACT_PROJECTION = {
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.DISPLAY_NAME_ALTERNATIVE,
            ContactsContract.Contacts.PHOTO_THUMBNAIL_URI
    };

    private static final String[] RAW_CONTACT_PROJECTION = {
            ContactsContract.RawContacts._ID,
            ContactsContract.RawContacts.CONTACT_ID,
            ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.RawContacts.ACCOUNT_NAME,
            ContactsContract.RawContacts.ACCOUNT_TYPE,
            ContactsContract.RawContacts.DATA_SET,
            ContactsContract.RawContacts.SOURCE_ID,
            ContactsContract.RawContacts.STARRED,
            ContactsContract.RawContacts.TIMES_CONTACTED,
            ContactsContract.RawContacts.CUSTOM_RINGTONE,
            ContactsContract.RawContacts.SEND_TO_VOICEMAIL
    };

    private static final String[] DATA_PROJECTION = {
            ContactsContract.Data._ID,
            ContactsContract.Data.RAW_CONTACT_ID,
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.DATA1,
            ContactsContract.Data.DATA2,
            ContactsContract.Data.DATA3,
            ContactsContract.Data.DATA4,
            ContactsContract.Data.DATA5,
            ContactsContract.Data.DATA6,
            ContactsContract.Data.DATA7,
            ContactsContract.Data.DATA8,
            ContactsContract.Data.DATA9,
            ContactsContract.Data.DATA10,
            ContactsContract.Data.DATA11,
            ContactsContract.Data.DATA12,
            ContactsContract.Data.DATA13,
            ContactsContract.Data.DATA14,
            ContactsContract.Data.DATA15,
            ContactsContract.Data.IS_PRIMARY,
            ContactsContract.Data.IS_SUPER_PRIMARY,
            ContactsContract.Data.DATA_VERSION,
            ContactsContract.Data.CUSTOM_RINGTONE
    };

    private static final String[] DATA_PROJECTION_EXTRAS = {
            ContactsContract.Data.IS_READ_ONLY,
            ContactsContract.Data.TIMES_USED
    };

    private ContactsSnapshotReader() {}

    /**
     * Reads a complete lossless snapshot of all contacts from the provider.
     *
     * Step 1: Query Contacts table for display names.
     * Step 2: Query RawContacts table for account metadata.
     * Step 3: Query Data table for every row, every column, every MIME type.
     * Step 4: Assemble into Contact → RawContact → Data hierarchy.
     *
     * @param context Android context for ContentResolver access
     * @return Complete lossless snapshot of all contacts
     */
    public static AndroidContactsSnapshot read(Context context) {
        AndroidContactsSnapshot snapshot = new AndroidContactsSnapshot();
        ContentResolver resolver = context.getContentResolver();

        LinkedHashMap<Long, AndroidContactSnapshot> contactMap = new LinkedHashMap<>();
        LinkedHashMap<Long, AndroidContactSnapshot.RawContactSnapshot> rawContactMap = new LinkedHashMap<>();

        readContacts(resolver, contactMap);
        readRawContacts(resolver, contactMap, rawContactMap);
        readDataRows(resolver, rawContactMap, contactMap);

        snapshot.contacts.addAll(contactMap.values());
        return snapshot;
    }

    /**
     * Reads display names from the Contacts table.
     */
    private static void readContacts(ContentResolver resolver,
                                      LinkedHashMap<Long, AndroidContactSnapshot> contactMap) {

        Cursor cursor = resolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                CONTACT_PROJECTION,
                null, null,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " ASC"
        );

        if (cursor == null) return;

        try {
            int idxId = cursor.getColumnIndex(ContactsContract.Contacts._ID);
            int idxDisplayName = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY);
            int idxAltName = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_ALTERNATIVE);

            while (cursor.moveToNext()) {
                long contactId = cursor.getLong(idxId);
                String displayName = safeString(cursor, idxDisplayName);
                if (displayName == null || displayName.isEmpty()) {
                    displayName = safeString(cursor, idxAltName);
                }
                AndroidContactSnapshot contact = new AndroidContactSnapshot(contactId, displayName);
                contactMap.put(contactId, contact);
            }
        } finally {
            cursor.close();
        }
    }

    private static void readRawContacts(ContentResolver resolver,
                                         LinkedHashMap<Long, AndroidContactSnapshot> contactMap,
                                         LinkedHashMap<Long, AndroidContactSnapshot.RawContactSnapshot> rawContactMap) {

        Cursor cursor = resolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                RAW_CONTACT_PROJECTION,
                null, null,
                ContactsContract.RawContacts.CONTACT_ID + " ASC"
        );

        if (cursor == null) return;

        try {
            int idxId = cursor.getColumnIndex(ContactsContract.RawContacts._ID);
            int idxContactId = cursor.getColumnIndex(ContactsContract.RawContacts.CONTACT_ID);
            int idxDisplayName = cursor.getColumnIndex(ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY);
            int idxAccountName = cursor.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_NAME);
            int idxAccountType = cursor.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_TYPE);
            int idxDataSet = cursor.getColumnIndex(ContactsContract.RawContacts.DATA_SET);
            int idxSourceId = cursor.getColumnIndex(ContactsContract.RawContacts.SOURCE_ID);
            int idxStarred = cursor.getColumnIndex(ContactsContract.RawContacts.STARRED);
            int idxTimesContacted = cursor.getColumnIndex(ContactsContract.RawContacts.TIMES_CONTACTED);
            int idxCustomRingtone = cursor.getColumnIndex(ContactsContract.RawContacts.CUSTOM_RINGTONE);
            int idxSendToVoicemail = cursor.getColumnIndex(ContactsContract.RawContacts.SEND_TO_VOICEMAIL);

            while (cursor.moveToNext()) {
                long rawContactId = cursor.getLong(idxId);
                long contactId = cursor.getLong(idxContactId);

                AndroidContactSnapshot contact = contactMap.get(contactId);
                if (contact == null) {
                    contact = new AndroidContactSnapshot(contactId, null);
                    contactMap.put(contactId, contact);
                }

                AndroidContactSnapshot.RawContactSnapshot rawContact = new AndroidContactSnapshot.RawContactSnapshot(rawContactId);
                rawContact.displayName = safeString(cursor, idxDisplayName);
                rawContact.accountName = safeString(cursor, idxAccountName);
                rawContact.accountType = safeString(cursor, idxAccountType);
                rawContact.dataSet = safeString(cursor, idxDataSet);
                rawContact.sourceId = safeString(cursor, idxSourceId);
                rawContact.starred = safeInt(cursor, idxStarred);
                rawContact.timesContacted = safeInt(cursor, idxTimesContacted);
                rawContact.customRingtone = safeString(cursor, idxCustomRingtone);
                rawContact.sendToVoicemail = safeInt(cursor, idxSendToVoicemail);

                contact.addRawContact(rawContact);
                rawContactMap.put(rawContactId, rawContact);
            }
        } finally {
            cursor.close();
        }
    }

    private static void readDataRows(ContentResolver resolver,
                                      LinkedHashMap<Long, AndroidContactSnapshot.RawContactSnapshot> rawContactMap,
                                      LinkedHashMap<Long, AndroidContactSnapshot> contactMap) {

        Cursor cursor = resolver.query(
                ContactsContract.Data.CONTENT_URI,
                DATA_PROJECTION,
                null, null,
                ContactsContract.Data.RAW_CONTACT_ID + " ASC"
        );

        if (cursor == null) return;

        try {
            int idxId = cursor.getColumnIndex(ContactsContract.Data._ID);
            int idxRawContactId = cursor.getColumnIndex(ContactsContract.Data.RAW_CONTACT_ID);
            int idxMimetype = cursor.getColumnIndex(ContactsContract.Data.MIMETYPE);
            int idxData1 = cursor.getColumnIndex(ContactsContract.Data.DATA1);
            int idxData2 = cursor.getColumnIndex(ContactsContract.Data.DATA2);
            int idxData3 = cursor.getColumnIndex(ContactsContract.Data.DATA3);
            int idxData4 = cursor.getColumnIndex(ContactsContract.Data.DATA4);
            int idxData5 = cursor.getColumnIndex(ContactsContract.Data.DATA5);
            int idxData6 = cursor.getColumnIndex(ContactsContract.Data.DATA6);
            int idxData7 = cursor.getColumnIndex(ContactsContract.Data.DATA7);
            int idxData8 = cursor.getColumnIndex(ContactsContract.Data.DATA8);
            int idxData9 = cursor.getColumnIndex(ContactsContract.Data.DATA9);
            int idxData10 = cursor.getColumnIndex(ContactsContract.Data.DATA10);
            int idxData11 = cursor.getColumnIndex(ContactsContract.Data.DATA11);
            int idxData12 = cursor.getColumnIndex(ContactsContract.Data.DATA12);
            int idxData13 = cursor.getColumnIndex(ContactsContract.Data.DATA13);
            int idxData14 = cursor.getColumnIndex(ContactsContract.Data.DATA14);
            int idxData15 = cursor.getColumnIndex(ContactsContract.Data.DATA15);
            int idxIsPrimary = cursor.getColumnIndex(ContactsContract.Data.IS_PRIMARY);
            int idxIsSuperPrimary = cursor.getColumnIndex(ContactsContract.Data.IS_SUPER_PRIMARY);
            int idxDataVersion = cursor.getColumnIndex(ContactsContract.Data.DATA_VERSION);
            int idxCustomRingtone = cursor.getColumnIndex(ContactsContract.Data.CUSTOM_RINGTONE);

            while (cursor.moveToNext()) {
                long rawContactId = cursor.getLong(idxRawContactId);
                String mimeType = safeString(cursor, idxMimetype);
                if (mimeType == null || mimeType.isEmpty()) continue;

                AndroidContactSnapshot.RawContactSnapshot rawContact = rawContactMap.get(rawContactId);
                if (rawContact == null) continue;

                AndroidContactSnapshot.DataRowSnapshot row = new AndroidContactSnapshot.DataRowSnapshot();
                row.dataId = cursor.getLong(idxId);
                row.rawContactId = rawContactId;
                row.mimeType = mimeType;

                row.data1 = safeString(cursor, idxData1);
                row.data2 = safeString(cursor, idxData2);
                row.data3 = safeString(cursor, idxData3);
                row.data4 = safeString(cursor, idxData4);
                row.data5 = safeString(cursor, idxData5);
                row.data6 = safeString(cursor, idxData6);
                row.data7 = safeString(cursor, idxData7);
                row.data8 = safeString(cursor, idxData8);
                row.data9 = safeString(cursor, idxData9);
                row.data10 = safeString(cursor, idxData10);
                row.data11 = safeString(cursor, idxData11);
                row.data12 = safeString(cursor, idxData12);
                row.data13 = safeString(cursor, idxData13);
                row.data14 = safeString(cursor, idxData14);

                byte[] blob = null;
                if (idxData15 >= 0) {
                    blob = cursor.getBlob(idxData15);
                }
                row.data15 = blob;

                row.isPrimary = safeInt(cursor, idxIsPrimary);
                row.isSuperPrimary = safeInt(cursor, idxIsSuperPrimary);
                row.dataVersion = safeInt(cursor, idxDataVersion);
                row.customRingtone = safeString(cursor, idxCustomRingtone);

                rawContact.addDataRow(row);
            }
        } finally {
            cursor.close();
        }

        // Try to read optional metadata columns (IS_READ_ONLY, TIMES_USED)
        // via a separate query. These columns may not exist on all API levels.
        readOptionalMetadata(resolver, rawContactMap);
    }

    /**
     * Attempts to read IS_READ_ONLY and TIMES_USED via a separate query.
     * These columns are not available on all API levels or provider implementations.
     * Matches rows by data ID and updates the snapshot in-place.
     */
    private static void readOptionalMetadata(ContentResolver resolver,
                                              LinkedHashMap<Long, AndroidContactSnapshot.RawContactSnapshot> rawContactMap) {
        Cursor cursor = null;
        try {
            cursor = resolver.query(
                    ContactsContract.Data.CONTENT_URI,
                    new String[]{ ContactsContract.Data._ID, ContactsContract.Data.IS_READ_ONLY, ContactsContract.Data.TIMES_USED },
                    null, null, null
            );
        } catch (Exception e) {
            // IS_READ_ONLY or TIMES_USED not supported on this API level
            return;
        }

        if (cursor == null) return;

        try {
            int idxId = cursor.getColumnIndex(ContactsContract.Data._ID);
            int idxReadOnly = cursor.getColumnIndex(ContactsContract.Data.IS_READ_ONLY);
            int idxTimesUsed = cursor.getColumnIndex(ContactsContract.Data.TIMES_USED);
            if (idxId < 0) return;

            while (cursor.moveToNext()) {
                long dataId = cursor.getLong(idxId);
                int readOnly = idxReadOnly >= 0 ? safeInt(cursor, idxReadOnly) : 0;
                int timesUsed = idxTimesUsed >= 0 ? safeInt(cursor, idxTimesUsed) : 0;

                for (AndroidContactSnapshot.RawContactSnapshot rc : rawContactMap.values()) {
                    for (AndroidContactSnapshot.DataRowSnapshot dr : rc.dataRows) {
                        if (dr.dataId == dataId) {
                            dr.isReadOnly = readOnly;
                            dr.timesUsed = timesUsed;
                        }
                    }
                }
            }
        } finally {
            cursor.close();
        }
    }

    private static String safeString(Cursor cursor, int index) {
        if (index < 0) return null;
        return cursor.isNull(index) ? null : cursor.getString(index);
    }

    private static int safeInt(Cursor cursor, int index) {
        if (index < 0) return 0;
        return cursor.isNull(index) ? 0 : cursor.getInt(index);
    }
}
