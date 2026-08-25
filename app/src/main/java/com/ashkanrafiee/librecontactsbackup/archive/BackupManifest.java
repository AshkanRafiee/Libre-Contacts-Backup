package com.ashkanrafiee.librecontactsbackup.archive;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Versioned manifest for the .lcb archive format.
 *
 * The manifest provides:
 * - Format identification and versioning for future migration
 * - Integrity verification via SHA-256 checksums
 * - Contact count metadata for quick validation
 */
public final class BackupManifest {

    public static final String FORMAT_NAME = "libre-contacts-backup";
    public static final int SCHEMA_VERSION = 2;

    private static final String LEGACY_FORMAT = "libre-contacts-backup-legacy";

    public String format;
    public int schemaVersion;
    public String createdAt;
    public String appVersion;
    public int androidApi;

    public int contactCount;
    public int rawContactCount;
    public int dataRowCount;

    public final LinkedHashMap<String, FileEntry> files = new LinkedHashMap<>();

    private String stableNow;

    public BackupManifest() {
        this.format = FORMAT_NAME;
        this.schemaVersion = SCHEMA_VERSION;
        this.stableNow = now();
    }

    public boolean isLegacy() {
        return LEGACY_FORMAT.equals(format) || schemaVersion < 2;
    }

    public boolean isCompatible() {
        return FORMAT_NAME.equals(format) && schemaVersion >= 2;
    }

    public void addFile(String name, String sha256) {
        files.put(name, new FileEntry(sha256));
    }

    public void setFileChecksum(String name, String sha256) {
        files.put(name, new FileEntry(sha256));
    }

    public void setCounts(int contacts, int rawContacts, int dataRows) {
        this.contactCount = contacts;
        this.rawContactCount = rawContacts;
        this.dataRowCount = dataRows;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("format", format);
        obj.put("schemaVersion", schemaVersion);
        obj.put("createdAt", createdAt != null ? createdAt : stableNow);
        obj.put("appVersion", appVersion != null ? appVersion : "unknown");
        obj.put("androidApi", androidApi);
        obj.put("contactCount", contactCount);
        obj.put("rawContactCount", rawContactCount);
        obj.put("dataRowCount", dataRowCount);

        JSONObject filesObj = new JSONObject();
        for (Map.Entry<String, FileEntry> entry : files.entrySet()) {
            JSONObject fileObj = new JSONObject();
            fileObj.put("sha256", entry.getValue().sha256);
            filesObj.put(entry.getKey(), fileObj);
        }
        obj.put("files", filesObj);
        return obj;
    }

    public static BackupManifest fromJson(JSONObject obj) throws JSONException {
        BackupManifest m = new BackupManifest();
        m.format = obj.optString("format", FORMAT_NAME);
        m.schemaVersion = obj.optInt("schemaVersion", 1);
        m.createdAt = obj.optString("createdAt", null);
        m.stableNow = m.createdAt != null ? m.createdAt : m.stableNow;
        m.appVersion = obj.optString("appVersion", null);
        m.androidApi = obj.optInt("androidApi", 0);
        m.contactCount = obj.optInt("contactCount", 0);
        m.rawContactCount = obj.optInt("rawContactCount", 0);
        m.dataRowCount = obj.optInt("dataRowCount", 0);

        JSONObject filesObj = obj.optJSONObject("files");
        if (filesObj != null) {
            java.util.Iterator<String> keys = filesObj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject fileObj = filesObj.getJSONObject(key);
                m.files.put(key, new FileEntry(fileObj.optString("sha256", "")));
            }
        }
        return m;
    }

    public static BackupManifest createLegacyManifest() {
        BackupManifest m = new BackupManifest();
        m.format = LEGACY_FORMAT;
        m.schemaVersion = 1;
        return m;
    }

    private static String now() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date());
    }

    public static final class FileEntry {
        public final String sha256;

        public FileEntry(String sha256) {
            this.sha256 = sha256;
        }
    }
}
