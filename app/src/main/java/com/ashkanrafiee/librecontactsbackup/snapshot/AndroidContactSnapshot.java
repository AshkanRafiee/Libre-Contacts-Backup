package com.ashkanrafiee.librecontactsbackup.snapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Lossless representation of a single Android Contacts Provider Contact
 * together with all its aggregated RawContacts and their Data rows.
 *
 * Preserves the full Contact → RawContact → Data hierarchy exactly as
 * the Android Contacts Provider models it.
 */
public final class AndroidContactSnapshot {

    public long contactId;
    public String displayName;
    public final ArrayList<RawContactSnapshot> rawContacts = new ArrayList<>();

    public AndroidContactSnapshot() {}

    public AndroidContactSnapshot(long contactId, String displayName) {
        this.contactId = contactId;
        this.displayName = displayName != null ? displayName : "";
    }

    public void addRawContact(RawContactSnapshot rc) {
        rawContacts.add(rc);
    }

    public int getRawContactCount() { return rawContacts.size(); }

    public int getDataRowCount() {
        int count = 0;
        for (RawContactSnapshot rc : rawContacts) count += rc.dataRows.size();
        return count;
    }

    public List<DataRowSnapshot> getAllDataRows() {
        ArrayList<DataRowSnapshot> all = new ArrayList<>();
        for (RawContactSnapshot rc : rawContacts) all.addAll(rc.dataRows);
        return Collections.unmodifiableList(all);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("contactId", contactId);
        obj.put("displayName", displayName);
        JSONArray arr = new JSONArray();
        for (RawContactSnapshot rc : rawContacts) arr.put(rc.toJson());
        obj.put("rawContacts", arr);
        return obj;
    }

    public static AndroidContactSnapshot fromJson(JSONObject obj) throws JSONException {
        AndroidContactSnapshot c = new AndroidContactSnapshot();
        c.contactId = obj.optLong("contactId", 0);
        c.displayName = obj.optString("displayName", "");
        JSONArray arr = obj.optJSONArray("rawContacts");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                c.rawContacts.add(RawContactSnapshot.fromJson(arr.getJSONObject(i)));
            }
        }
        return c;
    }

    /** toJson collapses null to "" for every optional string field; this reverses that on the way back in. */
    private static String nullIfEmpty(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    /**
     * Represents a single RawContact belonging to a specific account/source,
     * containing all its Data rows.
     */
    public static final class RawContactSnapshot {

        public long rawContactId;
        public String displayName;
        public String accountName;
        public String accountType;
        public String dataSet;
        public String sourceId;
        public int starred;
        public int timesContacted;
        public String customRingtone;
        public int sendToVoicemail;
        public final ArrayList<DataRowSnapshot> dataRows = new ArrayList<>();

        public RawContactSnapshot() {}

        public RawContactSnapshot(long rawContactId) {
            this.rawContactId = rawContactId;
        }

        public void addDataRow(DataRowSnapshot row) {
            dataRows.add(row);
        }

        public JSONObject toJson() throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("rawContactId", rawContactId);
            obj.put("displayName", displayName != null ? displayName : "");
            obj.put("accountName", accountName != null ? accountName : "");
            obj.put("accountType", accountType != null ? accountType : "");
            obj.put("dataSet", dataSet != null ? dataSet : "");
            obj.put("sourceId", sourceId != null ? sourceId : "");
            obj.put("starred", starred);
            obj.put("timesContacted", timesContacted);
            obj.put("customRingtone", customRingtone != null ? customRingtone : "");
            obj.put("sendToVoicemail", sendToVoicemail);
            JSONArray arr = new JSONArray();
            for (DataRowSnapshot row : dataRows) arr.put(row.toJson());
            obj.put("dataRows", arr);
            return obj;
        }

        public static RawContactSnapshot fromJson(JSONObject obj) throws JSONException {
            RawContactSnapshot rc = new RawContactSnapshot();
            rc.rawContactId = obj.optLong("rawContactId", 0);
            rc.displayName = nullIfEmpty(obj.optString("displayName", null));
            rc.accountName = nullIfEmpty(obj.optString("accountName", null));
            rc.accountType = nullIfEmpty(obj.optString("accountType", null));
            rc.dataSet = nullIfEmpty(obj.optString("dataSet", null));
            rc.sourceId = nullIfEmpty(obj.optString("sourceId", null));
            rc.starred = obj.optInt("starred", 0);
            rc.timesContacted = obj.optInt("timesContacted", 0);
            rc.customRingtone = nullIfEmpty(obj.optString("customRingtone", null));
            rc.sendToVoicemail = obj.optInt("sendToVoicemail", 0);
            JSONArray arr = obj.optJSONArray("dataRows");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    rc.dataRows.add(DataRowSnapshot.fromJson(arr.getJSONObject(i)));
                }
            }
            return rc;
        }
    }

    /**
     * Represents a single Data row in the Contacts Provider.
     * Preserves every readable field including MIME type, DATA1–DATA15,
     * and all row metadata. Unknown MIME types are preserved exactly.
     */
    public static final class DataRowSnapshot {

        public long dataId;
        public long rawContactId;
        public String mimeType;

        public String data1;
        public String data2;
        public String data3;
        public String data4;
        public String data5;
        public String data6;
        public String data7;
        public String data8;
        public String data9;
        public String data10;
        public String data11;
        public String data12;
        public String data13;
        public String data14;
        public byte[] data15;

        public int isPrimary;
        public int isSuperPrimary;
        public int dataVersion;
        public int isReadOnly;
        public int timesUsed;
        public long lastTimeUsed;
        public String customRingtone;

        public DataRowSnapshot() {}

        public DataRowSnapshot(String mimeType) {
            this.mimeType = mimeType;
        }

        public String getData(int index) {
            switch (index) {
                case 1: return data1;
                case 2: return data2;
                case 3: return data3;
                case 4: return data4;
                case 5: return data5;
                case 6: return data6;
                case 7: return data7;
                case 8: return data8;
                case 9: return data9;
                case 10: return data10;
                case 11: return data11;
                case 12: return data12;
                case 13: return data13;
                case 14: return data14;
                case 15: return null; // DATA15 is binary; access the data15 field directly
                default: return null;
            }
        }

        public boolean hasNonNullData() {
            return data1 != null || data2 != null || data3 != null || data4 != null
                    || data5 != null || data6 != null || data7 != null || data8 != null
                    || data9 != null || data10 != null || data11 != null || data12 != null
                    || data13 != null || data14 != null || (data15 != null && data15.length > 0);
        }

        /**
         * Returns a canonical string key for deduplication/comparison,
         * ignoring provider-generated IDs but including all data values.
         */
        public String canonicalKey() {
            StringBuilder sb = new StringBuilder();
            sb.append(mimeType).append('|');
            sb.append(nvl(data1)).append('|');
            sb.append(nvl(data2)).append('|');
            sb.append(nvl(data3)).append('|');
            sb.append(nvl(data4)).append('|');
            sb.append(nvl(data5)).append('|');
            sb.append(nvl(data6)).append('|');
            sb.append(nvl(data7)).append('|');
            sb.append(nvl(data8)).append('|');
            sb.append(nvl(data9)).append('|');
            sb.append(nvl(data10)).append('|');
            sb.append(nvl(data11)).append('|');
            sb.append(nvl(data12)).append('|');
            sb.append(nvl(data13)).append('|');
            sb.append(nvl(data14)).append('|');
            sb.append(isPrimary).append('|');
            sb.append(isSuperPrimary).append('|');
            if (data15 != null) {
                for (byte b : data15) {
                    sb.append(HEX_DIGITS[(b >> 4) & 0xF]).append(HEX_DIGITS[b & 0xF]);
                }
            }
            return sb.toString();
        }

        private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

        private static String nvl(String s) { return s != null ? s : ""; }

        public JSONObject toJson() throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("dataId", dataId);
            obj.put("mimeType", mimeType);
            if (data1 != null) obj.put("data1", data1);
            if (data2 != null) obj.put("data2", data2);
            if (data3 != null) obj.put("data3", data3);
            if (data4 != null) obj.put("data4", data4);
            if (data5 != null) obj.put("data5", data5);
            if (data6 != null) obj.put("data6", data6);
            if (data7 != null) obj.put("data7", data7);
            if (data8 != null) obj.put("data8", data8);
            if (data9 != null) obj.put("data9", data9);
            if (data10 != null) obj.put("data10", data10);
            if (data11 != null) obj.put("data11", data11);
            if (data12 != null) obj.put("data12", data12);
            if (data13 != null) obj.put("data13", data13);
            if (data14 != null) obj.put("data14", data14);
            if (data15 != null && data15.length > 0) {
                obj.put("data15", android.util.Base64.encodeToString(data15, android.util.Base64.NO_WRAP));
            }
            obj.put("isPrimary", isPrimary);
            obj.put("isSuperPrimary", isSuperPrimary);
            obj.put("dataVersion", dataVersion);
            obj.put("isReadOnly", isReadOnly);
            if (timesUsed != 0) obj.put("timesUsed", timesUsed);
            if (lastTimeUsed != 0) obj.put("lastTimeUsed", lastTimeUsed);
            if (customRingtone != null) obj.put("customRingtone", customRingtone);
            return obj;
        }

        public static DataRowSnapshot fromJson(JSONObject obj) throws JSONException {
            DataRowSnapshot row = new DataRowSnapshot();
            row.dataId = obj.optLong("dataId", 0);
            row.mimeType = obj.optString("mimeType", "");
            row.data1 = obj.optString("data1", null);
            row.data2 = obj.optString("data2", null);
            row.data3 = obj.optString("data3", null);
            row.data4 = obj.optString("data4", null);
            row.data5 = obj.optString("data5", null);
            row.data6 = obj.optString("data6", null);
            row.data7 = obj.optString("data7", null);
            row.data8 = obj.optString("data8", null);
            row.data9 = obj.optString("data9", null);
            row.data10 = obj.optString("data10", null);
            row.data11 = obj.optString("data11", null);
            row.data12 = obj.optString("data12", null);
            row.data13 = obj.optString("data13", null);
            row.data14 = obj.optString("data14", null);
            if (obj.has("data15")) {
                row.data15 = android.util.Base64.decode(obj.getString("data15"), android.util.Base64.NO_WRAP);
            }
            row.isPrimary = obj.optInt("isPrimary", 0);
            row.isSuperPrimary = obj.optInt("isSuperPrimary", 0);
            row.dataVersion = obj.optInt("dataVersion", 0);
            row.isReadOnly = obj.optInt("isReadOnly", 0);
            row.timesUsed = obj.optInt("timesUsed", 0);
            row.lastTimeUsed = obj.optLong("lastTimeUsed", 0);
            row.customRingtone = obj.optString("customRingtone", null);
            return row;
        }
    }
}
