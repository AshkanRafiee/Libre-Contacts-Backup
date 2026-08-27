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
 * If integrity verification fails, {@link ArchiveData#checksumValid} is
 * false and the caller must reject it rather than restoring corrupted data
 * (see {@link com.ashkanrafiee.librecontactsbackup.BackupManager#openArchive}).
 */
public final class BackupArchiveReader {

    private static final String TAG = "BackupArchiveReader";

    private BackupArchiveReader() {}

    // A well-formed .lcb only ever contains a handful of named entries
    // (manifest.json, android-contacts.json, contacts.vcf, contacts.json,
    // contacts.csv). These caps are generous for that real shape while
    // bounding a corrupted or adversarial zip: an unbounded entry count,
    // per-entry size, or total inflated size would let a small file expand
    // into an out-of-memory crash on restore (a "decompression bomb").
    private static final int MAX_ENTRY_COUNT = 50;
    private static final long MAX_ENTRY_SIZE = 200L * 1024 * 1024;
    private static final long MAX_TOTAL_SIZE = 500L * 1024 * 1024;

    public static ArchiveData readArchive(InputStream inputStream) throws IOException {
        ArchiveData result = new ArchiveData();
        byte[] zipBytes = readAll(inputStream);
        ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes));
        ZipEntry entry;

        Map<String, byte[]> entries = new HashMap<>();
        long totalSize = 0;

        while ((entry = zip.getNextEntry()) != null) {
            if (entries.size() >= MAX_ENTRY_COUNT) {
                throw new IOException("Invalid backup: too many entries in archive");
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n;
            long entrySize = 0;
            while ((n = zip.read(tmp)) > 0) {
                entrySize += n;
                if (entrySize > MAX_ENTRY_SIZE) {
                    throw new IOException("Invalid backup: entry too large: " + entry.getName());
                }
                totalSize += n;
                if (totalSize > MAX_TOTAL_SIZE) {
                    throw new IOException("Invalid backup: archive too large when decompressed");
                }
                buf.write(tmp, 0, n);
            }
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

            String actualSha256 = BackupArchiveWriter.sha256(fileData);
            if (!expectedSha256.equals(actualSha256)) {
                Log.w(TAG, "Checksum mismatch for " + fileName +
                        ": expected " + expectedSha256 + ", got " + actualSha256);
                allValid = false;
            }
        }

        // The manifest is itself part of the untrusted archive — it must not
        // be able to declare "everything checks out" by simply omitting the
        // one file the restore is actually going to read. Whichever file
        // this format will use as its data source has to be both present
        // and checksum-covered, or the whole archive isn't valid regardless
        // of what the manifest's own (possibly incomplete) file list says.
        String criticalFile = manifest.isLegacy() ? "contacts.vcf" : "android-contacts.json";
        if (!manifest.files.containsKey(criticalFile)) {
            Log.w(TAG, "Manifest does not list a checksum for " + criticalFile);
            allValid = false;
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
