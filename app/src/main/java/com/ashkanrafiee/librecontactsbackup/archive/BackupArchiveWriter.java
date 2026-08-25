package com.ashkanrafiee.librecontactsbackup.archive;

import android.content.Context;
import android.os.Build;
import android.provider.ContactsContract;

import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactsSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.RestoreResult;
import com.ashkanrafiee.librecontactsbackup.export.VCardExporter;
import com.ashkanrafiee.librecontactsbackup.export.NormalizedJsonExporter;
import com.ashkanrafiee.librecontactsbackup.export.NormalizedCsvExporter;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a .lcb backup archive containing:
 *
 * - manifest.json      (versioned manifest with checksums)
 * - android-contacts.json  (canonical lossless snapshot)
 * - contacts.vcf       (derived VCF for interoperability)
 * - contacts.json      (derived normalized JSON for human readability)
 * - contacts.csv       (derived CSV for human readability)
 *
 * The canonical format is android-contacts.json. VCF, JSON, and CSV
 * are derived exports generated from the snapshot.
 *
 * Binary data (photos etc.) is base64-encoded in the canonical JSON
 * for simplicity. If archives become very large, a media/ directory
 * can be introduced in a future schema version.
 */
public final class BackupArchiveWriter {

    private BackupArchiveWriter() {}

    public static void writeArchive(Context context,
                                     AndroidContactsSnapshot snapshot,
                                     OutputStream outputStream) throws IOException {

        try {
            ZipOutputStream zip = new ZipOutputStream(outputStream);

            // 1. Canonical lossless snapshot
            byte[] canonicalJson = NormalizedJsonExporter.exportCanonical(snapshot).getBytes(StandardCharsets.UTF_8);

            // 2. Derived VCF
            byte[] vcf = VCardExporter.exportVcf(snapshot).getBytes(StandardCharsets.UTF_8);

            // 3. Derived normalized JSON (human-readable)
            byte[] normalizedJson = NormalizedJsonExporter.exportNormalized(snapshot).getBytes(StandardCharsets.UTF_8);

            // 4. Derived CSV
            byte[] csv = NormalizedCsvExporter.exportCsv(snapshot).getBytes(StandardCharsets.UTF_8);

            // Compute checksums
            BackupManifest manifest = new BackupManifest();
            manifest.setCounts(snapshot.getContactCount(), snapshot.getRawContactCount(), snapshot.getDataRowCount());
            manifest.addFile("android-contacts.json", sha256(canonicalJson));
            manifest.addFile("contacts.vcf", sha256(vcf));
            manifest.addFile("contacts.json", sha256(normalizedJson));
            manifest.addFile("contacts.csv", sha256(csv));

            try {
                manifest.appVersion = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            } catch (Exception e) {
                manifest.appVersion = "unknown";
            }
            manifest.androidApi = Build.VERSION.SDK_INT;

            byte[] manifestBytes = manifest.toJson().toString(2).getBytes(StandardCharsets.UTF_8);

            // Write ZIP entries
            addEntry(zip, "manifest.json", manifestBytes);
            addEntry(zip, "android-contacts.json", canonicalJson);
            addEntry(zip, "contacts.vcf", vcf);
            addEntry(zip, "contacts.json", normalizedJson);
            addEntry(zip, "contacts.csv", csv);

            zip.finish();
            zip.close();

        } catch (JSONException e) {
            throw new IOException("Failed to generate manifest JSON", e);
        }
    }

    /**
     * Reads the full snapshot and writes the .lcb archive to the output stream.
     */
    public static void writeBackup(Context context,
                                    AndroidContactsSnapshot snapshot,
                                    OutputStream outputStream) throws IOException {
        writeArchive(context, snapshot, outputStream);
    }

    private static void addEntry(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    public static String sha256(byte[] data) {
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
}
