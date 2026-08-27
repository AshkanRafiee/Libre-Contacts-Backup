package com.ashkanrafiee.librecontactsbackup.snapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

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
    public final ArrayList<GroupSnapshot> groups = new ArrayList<>();

    public AndroidContactsSnapshot() {}

    public void addContact(AndroidContactSnapshot contact) {
        contacts.add(contact);
    }

    public void addGroup(GroupSnapshot group) {
        groups.add(group);
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

    public List<GroupSnapshot> getGroups() {
        return Collections.unmodifiableList(groups);
    }

    /**
     * Represents a single row of the Groups table (an account's contact group,
     * e.g. a Google "Label"). Captured so that group_membership Data rows can
     * be restored losslessly instead of being silently dropped.
     */
    public static final class GroupSnapshot {

        public long groupId;
        public String title;
        public String accountName;
        public String accountType;
        public String dataSet;
        public String sourceId;

        public GroupSnapshot() {}

        public JSONObject toJson() throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("groupId", groupId);
            obj.put("title", title != null ? title : "");
            obj.put("accountName", accountName != null ? accountName : "");
            obj.put("accountType", accountType != null ? accountType : "");
            obj.put("dataSet", dataSet != null ? dataSet : "");
            obj.put("sourceId", sourceId != null ? sourceId : "");
            return obj;
        }

        public static GroupSnapshot fromJson(JSONObject obj) throws JSONException {
            GroupSnapshot g = new GroupSnapshot();
            g.groupId = obj.optLong("groupId", 0);
            g.title = obj.optString("title", null);
            g.accountName = obj.optString("accountName", null);
            if (g.accountName != null && g.accountName.isEmpty()) g.accountName = null;
            g.accountType = obj.optString("accountType", null);
            if (g.accountType != null && g.accountType.isEmpty()) g.accountType = null;
            g.dataSet = obj.optString("dataSet", null);
            if (g.dataSet != null && g.dataSet.isEmpty()) g.dataSet = null;
            g.sourceId = obj.optString("sourceId", null);
            if (g.sourceId != null && g.sourceId.isEmpty()) g.sourceId = null;
            return g;
        }
    }
}
