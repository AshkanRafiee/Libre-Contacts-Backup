package com.ashkanrafiee.librecontactsbackup.export;

import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactsSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactSnapshot.DataRowSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactSnapshot.RawContactSnapshot;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Exports contacts as JSON in two modes:
 *
 * 1. Canonical: lossless byte-for-byte representation of the snapshot,
 *    including every data row, every MIME type, binary data, and metadata.
 *    This IS the backup representation inside .lcb archives.
 *
 * 2. Normalized: human-readable export derived from the snapshot.
 *    Groups data by semantic type. Intended for user consumption,
 *    NOT for restore.
 */
public final class NormalizedJsonExporter {

    private static final String MIME_NAME = "vnd.android.cursor.item/name";
    private static final String MIME_PHONE = "vnd.android.cursor.item/phone_v2";
    private static final String MIME_EMAIL = "vnd.android.cursor.item/email_v2";
    private static final String MIME_POSTAL = "vnd.android.cursor.item/postal-address_v2";
    private static final String MIME_POSTAL_LEGACY = "vnd.android.cursor.item/postal-address";
    private static final String MIME_ORG = "vnd.android.cursor.item/organization";
    private static final String MIME_NICK = "vnd.android.cursor.item/nickname";
    private static final String MIME_NOTE = "vnd.android.cursor.item/note";
    private static final String MIME_EVENT = "vnd.android.cursor.item/contact_event";
    private static final String MIME_IM = "vnd.android.cursor.item/im";
    private static final String MIME_WEB = "vnd.android.cursor.item/website";
    private static final String MIME_REL = "vnd.android.cursor.item/relation";
    private static final String MIME_PHOTO = "vnd.android.cursor.item/photo";
    private static final String MIME_SIP = "vnd.android.cursor.item/sip-address";
    private static final String MIME_GROUP = "vnd.android.cursor.item/group_membership";

    private NormalizedJsonExporter() {}

    /**
     * Exports the snapshot as a canonical lossless JSON representation.
     * This preserves every row, every column, and every piece of binary data.
     */
    public static String exportCanonical(AndroidContactsSnapshot snapshot) throws JSONException {
        JSONArray arr = new JSONArray();
        for (AndroidContactSnapshot contact : snapshot.contacts) {
            arr.put(contact.toJson());
        }
        return arr.toString(2);
    }

    /**
     * Imports a canonical lossless JSON representation back into a snapshot.
     */
    public static AndroidContactsSnapshot importCanonical(String json) throws JSONException {
        AndroidContactsSnapshot snapshot = new AndroidContactsSnapshot();
        JSONArray arr = new JSONArray(json);
        for (int i = 0; i < arr.length(); i++) {
            snapshot.addContact(AndroidContactSnapshot.fromJson(arr.getJSONObject(i)));
        }
        return snapshot;
    }

    /**
     * Exports contacts as a normalized, human-readable JSON.
     * Groups data by semantic type for easier reading.
     * This is a derived export format, NOT suitable for lossless restore.
     */
    public static String exportNormalized(AndroidContactsSnapshot snapshot) throws JSONException {
        JSONArray arr = new JSONArray();
        for (AndroidContactSnapshot contact : snapshot.contacts) {
            JSONObject obj = new JSONObject();
            obj.put("displayName", contact.displayName);

            JSONArray rawArr = new JSONArray();
            for (RawContactSnapshot rc : contact.rawContacts) {
                JSONObject rawObj = new JSONObject();
                rawObj.put("accountName", nvl(rc.accountName));
                rawObj.put("accountType", nvl(rc.accountType));

                // Collect by semantic type
                JSONArray names = new JSONArray();
                JSONArray phones = new JSONArray();
                JSONArray emails = new JSONArray();
                JSONArray addresses = new JSONArray();
                JSONArray orgs = new JSONArray();
                JSONArray nicknames = new JSONArray();
                JSONArray notes = new JSONArray();
                JSONArray events = new JSONArray();
                JSONArray websites = new JSONArray();
                JSONArray ims = new JSONArray();
                JSONArray relations = new JSONArray();
                JSONArray photos = new JSONArray();
                JSONArray sipAddresses = new JSONArray();
                JSONArray groups = new JSONArray();
                JSONArray other = new JSONArray();

                for (DataRowSnapshot row : rc.dataRows) {
                    String mime = row.mimeType;
                    if (mime == null) continue;

                    switch (mime) {
                        case MIME_NAME:
                            names.put(row.toJson());
                            break;
                        case MIME_PHONE:
                            phones.put(row.toJson());
                            break;
                        case MIME_EMAIL:
                            emails.put(row.toJson());
                            break;
                        case MIME_POSTAL:
                        case MIME_POSTAL_LEGACY:
                            addresses.put(row.toJson());
                            break;
                        case MIME_ORG:
                            orgs.put(row.toJson());
                            break;
                        case MIME_NICK:
                            nicknames.put(row.toJson());
                            break;
                        case MIME_NOTE:
                            notes.put(row.toJson());
                            break;
                        case MIME_EVENT:
                            events.put(row.toJson());
                            break;
                        case MIME_WEB:
                            websites.put(row.toJson());
                            break;
                        case MIME_IM:
                            ims.put(row.toJson());
                            break;
                        case MIME_REL:
                            relations.put(row.toJson());
                            break;
                        case MIME_PHOTO:
                            photos.put(row.toJson());
                            break;
                        case MIME_SIP:
                            sipAddresses.put(row.toJson());
                            break;
                        case MIME_GROUP:
                            groups.put(row.toJson());
                            break;
                        default:
                            other.put(row.toJson());
                            break;
                    }
                }

                if (names.length() > 0) rawObj.put("names", names);
                if (phones.length() > 0) rawObj.put("phones", phones);
                if (emails.length() > 0) rawObj.put("emails", emails);
                if (addresses.length() > 0) rawObj.put("addresses", addresses);
                if (orgs.length() > 0) rawObj.put("organizations", orgs);
                if (nicknames.length() > 0) rawObj.put("nicknames", nicknames);
                if (notes.length() > 0) rawObj.put("notes", notes);
                if (events.length() > 0) rawObj.put("events", events);
                if (websites.length() > 0) rawObj.put("websites", websites);
                if (ims.length() > 0) rawObj.put("ims", ims);
                if (relations.length() > 0) rawObj.put("relations", relations);
                if (photos.length() > 0) rawObj.put("photos", photos);
                if (sipAddresses.length() > 0) rawObj.put("sipAddresses", sipAddresses);
                if (groups.length() > 0) rawObj.put("groups", groups);
                if (other.length() > 0) rawObj.put("otherData", other);

                rawArr.put(rawObj);
            }
            obj.put("rawContacts", rawArr);
            arr.put(obj);
        }
        return arr.toString(2);
    }

    private static String nvl(String s) { return s != null ? s : ""; }
}
