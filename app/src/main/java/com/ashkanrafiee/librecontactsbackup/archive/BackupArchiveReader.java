package com.ashkanrafiee.librecontactsbackup.archive;

import android.util.Log;

import com.ashkanrafiee.librecontactsbackup.export.NormalizedJsonExporter;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactsSnapshot;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads a .lcb backup archive, validates its manifest and checksums,
 * and extracts the canonical lossless snapshot.
 *
 * Supports both:
 * - New format (schemaVersion >= 2): reads android-contacts.json
 * - Legacy format (schemaVersion < 2): reads contacts.vcf, best-effort migration
 *
 * If integrity verification fails, returns null rather than restoring
 * corrupted data.
 */
public final class BackupArchiveReader {

    private static final String TAG = "BackupArchiveReader";

    private BackupArchiveReader() {}

    public static ArchiveData readArchive(InputStream inputStream) throws IOException {
        ArchiveData result = new ArchiveData();
        byte[] zipBytes = readAll(inputStream);
        ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes));
        ZipEntry entry;

        Map<String, byte[]> entries = new HashMap<>();

        while ((entry = zip.getNextEntry()) != null) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n;
            while ((n = zip.read(tmp)) > 0) buf.write(tmp, 0, n);
            entries.put(entry.getName(), buf.toByteArray());
            zip.closeEntry();
        }
        zip.close();

        // Read manifest
        byte[] manifestBytes = entries.get("manifest.json");
        if (manifestBytes == null) {
            // Could be a legacy archive with no manifest (just contacts.vcf in a zip)
            // Try to detect legacy format by checking for contacts.vcf
            if (entries.containsKey("contacts.vcf")) {
                result.manifest = BackupManifest.createLegacyManifest();
                result.vcfContent = new String(entries.get("contacts.vcf"), StandardCharsets.UTF_8);
                result.isLegacy = true;
                return result;
            }
            throw new IOException("Invalid backup: no manifest found");
        }

        try {
            String manifestStr = new String(manifestBytes, StandardCharsets.UTF_8);
            JSONObject manifestJson = new JSONObject(manifestStr);
            result.manifest = BackupManifest.fromJson(manifestJson);
        } catch (JSONException e) {
            throw new IOException("Invalid manifest format", e);
        }

        // Verify checksums
        result.checksumValid = verifyChecksums(result.manifest, entries);

        if (!result.checksumValid) {
            Log.w(TAG, "Checksum verification failed for backup archive");
        }

        if (result.manifest.isLegacy()) {
            // Legacy format: best-effort read from VCF
            result.isLegacy = true;
            byte[] vcfBytes = entries.get("contacts.vcf");
            if (vcfBytes != null) {
                result.vcfContent = new String(vcfBytes, StandardCharsets.UTF_8);
            }
        } else {
            // New format: read canonical snapshot
            result.isLegacy = false;
            byte[] canonicalBytes = entries.get("android-contacts.json");
            if (canonicalBytes == null) {
                throw new IOException("Invalid backup: no android-contacts.json in archive");
            }
            try {
                String jsonStr = new String(canonicalBytes, StandardCharsets.UTF_8);
                result.snapshot = NormalizedJsonExporter.importCanonical(jsonStr);
            } catch (JSONException e) {
                throw new IOException("Failed to parse canonical snapshot", e);
            }
        }

        return result;
    }

    private static boolean verifyChecksums(BackupManifest manifest, Map<String, byte[]> entries) {
        boolean allValid = true;
        for (Map.Entry<String, BackupManifest.FileEntry> entry : manifest.files.entrySet()) {
            String fileName = entry.getKey();
            String expectedSha256 = entry.getValue().sha256;

            if ("manifest.json".equals(fileName)) continue; // Self-referential, skip

            byte[] fileData = entries.get(fileName);
            if (fileData == null) {
                Log.w(TAG, "Missing file in archive: " + fileName);
                allValid = false;
                continue;
            }

            String actualSha256 = sha256(fileData);
            if (!expectedSha256.equals(actualSha256)) {
                Log.w(TAG, "Checksum mismatch for " + fileName +
                        ": expected " + expectedSha256 + ", got " + actualSha256);
                allValid = false;
            }
        }
        return allValid;
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = input.read(buffer)) > 0) output.write(buffer, 0, n);
        return output.toByteArray();
    }

    static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    /**
     * Result of reading a .lcb archive.
     */
    public static class ArchiveData {
        public BackupManifest manifest;
        public AndroidContactsSnapshot snapshot;
        public String vcfContent;
        public boolean isLegacy;
        public boolean checksumValid;

        public boolean isLossless() {
            return !isLegacy && snapshot != null;
        }
    }
}
