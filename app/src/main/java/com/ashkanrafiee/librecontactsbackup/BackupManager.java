package com.ashkanrafiee.librecontactsbackup;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.DocumentsContract;
import android.util.Base64;

import com.ashkanrafiee.librecontactsbackup.archive.BackupArchiveReader;
import com.ashkanrafiee.librecontactsbackup.archive.BackupArchiveWriter;
import com.ashkanrafiee.librecontactsbackup.archive.ContactsSnapshotRestorer;
import com.ashkanrafiee.librecontactsbackup.export.NormalizedCsvExporter;
import com.ashkanrafiee.librecontactsbackup.export.NormalizedJsonExporter;
import com.ashkanrafiee.librecontactsbackup.export.VCardExporter;
import com.ashkanrafiee.librecontactsbackup.export.VCardImporter;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactsSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.ContactsSnapshotReader;
import com.ashkanrafiee.librecontactsbackup.snapshot.RestoreResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

/**
 * Coordination layer for backup and restore operations.
 *
 * Architecture:
 *   Android Contacts Provider → ContactsSnapshotReader → AndroidContactsSnapshot
 *   AndroidContactsSnapshot → BackupArchiveWriter → .lcb archive
 *   .lcb archive → BackupArchiveReader → AndroidContactsSnapshot
 *   AndroidContactsSnapshot → ContactsSnapshotRestorer → Android Contacts Provider
 *
 * The .lcb archive contains:
 *   manifest.json        (versioned manifest with checksums)
 *   android-contacts.json (canonical lossless snapshot)
 *   contacts.vcf          (derived VCF for interoperability)
 *   contacts.json         (derived normalized JSON)
 *   contacts.csv          (derived CSV)
 *
 * VCF, JSON, and CSV are derived export formats.
 * The canonical backup representation is android-contacts.json.
 *
 * Legacy .lcb files (schemaVersion < 2) are detected and handled
 * via best-effort migration through VCF import.
 */
public final class BackupManager {
    private static final String PREFS = "librecontacts", KEY_URI = "folder", KEY_ALIAS = "LibreContactsPasswordKey";
    private static final byte[] MAGIC = "LIBRECB1".getBytes(StandardCharsets.US_ASCII);

    public static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    public static String folder(Context c) { return prefs(c).getString(KEY_URI, ""); }

    public static String folderLabel(Context c) {
        String value = folder(c); if (value.isEmpty()) return "Not selected";
        try {
            Uri tree = Uri.parse(value); Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            Cursor cursor = c.getContentResolver().query(document, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (cursor != null) { if (cursor.moveToFirst() && cursor.getString(0) != null) { String name = cursor.getString(0); cursor.close(); return name; } cursor.close(); }
        } catch (Exception ignored) { }
        String id = Uri.decode(Uri.parse(value).getPath()); if (id == null) return "Selected"; int colon = id.lastIndexOf(':'); if (colon >= 0) id = id.substring(colon + 1); int slash = id.lastIndexOf('/'); return slash >= 0 ? id.substring(slash + 1) : id;
    }

    /**
     * Runs a lossless backup: reads all contacts from the provider into
     * a snapshot, then writes the .lcb archive with canonical + derived formats.
     */
    public static String runBackup(Context c, boolean notify) {
        try {
            if (folder(c).isEmpty()) return "Choose a folder first";
            Uri tree = Uri.parse(folder(c));

            // Step 1: Read lossless snapshot from provider
            AndroidContactsSnapshot snapshot = ContactsSnapshotReader.read(c);

            // Step 2: Write .lcb archive
            String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
            boolean encrypted = prefs(c).getBoolean("encrypted", false);
            String password = encrypted ? loadEncryptionPassword(c) : null;
            if (encrypted && (password == null || password.isEmpty())) return "Set an encryption password first";

            ByteArrayOutputStream zipOutput = new ByteArrayOutputStream();
            BackupArchiveWriter.writeArchive(c, snapshot, zipOutput);
            byte[] archive = zipOutput.toByteArray();
            if (encrypted) archive = encryptArchive(archive, password);

            writeArchive(c, tree, "librecontacts_" + stamp + (encrypted ? ".lcb.enc" : ".lcb"), archive);
            trim(c, tree);
            prefs(c).edit().putLong("last", System.currentTimeMillis()).apply();

            if (notify) MainActivity.notice(c, "Backup complete",
                    "Saved " + snapshot.getContactCount() + " contacts");
            return "Saved " + snapshot.getContactCount() + " contacts";
        } catch (Exception e) {
            if (notify) MainActivity.notice(c, "Backup failed", e.getMessage());
            return "Backup failed: " + e.getMessage();
        }
    }

    /**
     * Restores contacts from a .lcb backup file.
     * Handles both new format (canonical snapshot) and legacy format (VCF-only).
     * Returns a RestoreResult with detailed statistics.
     */
    public static RestoreResult restoreWithResult(Context c, Uri file, String password,
                                                    RestoreProgress progress) throws Exception {
        progress.update("Opening backup", 0, 0);
        byte[] data;
        try (InputStream in = c.getContentResolver().openInputStream(file)) {
            data = readAll(in);
        }

        progress.update("Decrypting backup", 0, 0);
        data = decryptArchive(data, password);

        progress.update("Reading archive", 0, 0);
        BackupArchiveReader.ArchiveData archiveData;
        try (InputStream zipIn = new ByteArrayInputStream(data)) {
            archiveData = BackupArchiveReader.readArchive(zipIn);
        }

        if (!archiveData.checksumValid) {
            throw new IOException("Backup integrity check failed: checksums do not match");
        }

        if (archiveData.isLegacy) {
            // Legacy format: restore from VCF (best-effort, may be lossy)
            return restoreFromVcf(c, archiveData.vcfContent, progress);
        } else if (archiveData.isLossless() && archiveData.snapshot != null) {
            // New format: lossless restore from canonical snapshot
            return ContactsSnapshotRestorer.restoreExact(c, archiveData.snapshot,
                    (message, current, total) -> progress.update(message, current, total));
        } else {
            throw new IOException("Invalid backup format");
        }
    }

    /**
     * Legacy-compatible restore method called by MainActivity.
     * Wraps restoreWithResult and stores results in SharedPreferences.
     */
    public static void restore(Context c, Uri file, String password, RestoreProgress progress) throws Exception {
        RestoreResult result = restoreWithResult(c, file, password, progress);
        prefs(c).edit()
                .putLong("lastRestore", System.currentTimeMillis())
                .putInt("lastRestoreCount", result.contactsCreated)
                .apply();
        String message = result.briefSummary();
        if (result.hasErrors()) {
            message += "\n" + result.errors.size() + " errors occurred";
        }
        MainActivity.notice(c, "Restore complete", message);
    }

    /**
     * Legacy restore from VCF content. Used as fallback for old .lcb files.
     */
    private static RestoreResult restoreFromVcf(Context c, String vcfContent, RestoreProgress progress) throws Exception {
        if (vcfContent == null || vcfContent.isEmpty()) {
            throw new IOException("No VCF content found in backup");
        }

        // Import VCF into a snapshot
        AndroidContactsSnapshot snapshot = VCardImporter.importVcf(vcfContent);
        if (snapshot.getContactCount() == 0) {
            throw new IOException("No contacts found in backup");
        }

        // Restore from the snapshot
        return ContactsSnapshotRestorer.restoreExact(c, snapshot,
                (message, current, total) -> progress.update(message, current, total));
    }

    // ============================================================
    // Manual Export (derived formats only - not for backup)
    // ============================================================

    /**
     * Writes a manual export in the requested format.
     * These are derived export formats, not the canonical backup.
     */
    public static void writeManualExport(Context c, Uri destination, String format) throws Exception {
        AndroidContactsSnapshot snapshot = ContactsSnapshotReader.read(c);
        String value;
        String mime;

        if (format.equals("vcf")) {
            value = VCardExporter.exportVcf(snapshot);
            mime = "text/x-vcard";
        } else if (format.equals("xls")) {
            value = excel(snapshot);
            mime = "application/vnd.ms-excel";
        } else {
            value = NormalizedCsvExporter.exportCsv(snapshot);
            mime = "text/csv";
        }

        try (OutputStream out = c.getContentResolver().openOutputStream(destination)) {
            if (out == null) throw new IOException("Cannot open export destination");
            out.write(value.getBytes(StandardCharsets.UTF_8));
        }
        MainActivity.notice(c, "Manual export complete", "Saved " + snapshot.getContactCount() + " contacts");
    }

    // ============================================================
    // Encryption (preserved from original implementation)
    // ============================================================

    public static void saveEncryptionPassword(Context c, String password) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key()); byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8)); ByteArrayOutputStream data = new ByteArrayOutputStream(); data.write(iv); data.write(encrypted);
        prefs(c).edit().putString("password", Base64.encodeToString(data.toByteArray(), Base64.NO_WRAP)).apply();
    }

    private static String loadEncryptionPassword(Context c) throws Exception {
        String encoded = prefs(c).getString("password", ""); if (encoded.isEmpty()) return null; byte[] data = Base64.decode(encoded, Base64.DEFAULT);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, data, 0, 12)); return new String(cipher.doFinal(data, 12, data.length - 12), StandardCharsets.UTF_8);
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(KEY_ALIAS)) { KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"); generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()); generator.generateKey(); }
        return ((KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null)).getSecretKey();
    }

    private static byte[] encryptArchive(byte[] input, String password) throws Exception {
        byte[] salt = new byte[16], iv = new byte[12]; SecureRandom random = new SecureRandom(); random.nextBytes(salt); random.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), new GCMParameterSpec(128, iv)); byte[] encrypted = cipher.doFinal(input);
        ByteArrayOutputStream output = new ByteArrayOutputStream(); output.write(MAGIC); output.write(salt); output.write(iv); output.write(encrypted); return output.toByteArray();
    }

    private static byte[] decryptArchive(byte[] input, String password) throws Exception {
        if (input.length < MAGIC.length + 28 || !Arrays.equals(Arrays.copyOf(input, MAGIC.length), MAGIC)) return input;
        if (password == null || password.isEmpty()) throw new SecurityException("Password required"); byte[] salt = Arrays.copyOfRange(input, 8, 24), iv = Arrays.copyOfRange(input, 24, 36);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), new GCMParameterSpec(128, iv)); return cipher.doFinal(input, 36, input.length - 36);
    }

    private static SecretKey deriveKey(String password, byte[] salt) throws Exception { PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 120000, 256); byte[] bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); return new javax.crypto.spec.SecretKeySpec(bytes, "AES"); }

    public static boolean isEncrypted(Context c, Uri file) throws Exception { try (InputStream in = c.getContentResolver().openInputStream(file)) { byte[] header = new byte[8]; int read = in.read(header); return read == 8 && Arrays.equals(header, MAGIC); } }

    public interface RestoreProgress { void update(String message, int current, int total); }

    // ============================================================
    // Archive I/O
    // ============================================================

    private static void writeArchive(Context c, Uri tree, String name, byte[] bytes) throws Exception {
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        Uri file = DocumentsContract.createDocument(c.getContentResolver(), parent, "application/octet-stream", name);
        if (file == null) throw new IOException("Cannot create backup file");
        try (OutputStream out = c.getContentResolver().openOutputStream(file)) { out.write(bytes); }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
        while ((n = input.read(buffer)) > 0) output.write(buffer, 0, n); return output.toByteArray();
    }

    // ============================================================
    // Retention management (preserved from original)
    // ============================================================

    private static void trim(Context c, Uri tree) throws Exception {
        int keep = prefs(c).getInt("keep", 5); Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)); LinkedHashMap<String, ArrayList<Uri>> sets = new LinkedHashMap<>(); ArrayList<Uri> legacy = new ArrayList<>();
        Cursor q = c.getContentResolver().query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
        if (q != null) { while (q.moveToNext()) { String name = q.getString(1); if (!name.startsWith("librecontacts_")) continue; Uri file = DocumentsContract.buildDocumentUriUsingTree(tree, q.getString(0)); if (!(name.endsWith(".lcb") || name.endsWith(".lcb.enc"))) { legacy.add(file); continue; } String base = name; for (String suffix : new String[]{".enc", ".lcb"}) if (base.endsWith(suffix)) base = base.substring(0, base.length() - suffix.length()); sets.computeIfAbsent(base, k -> new ArrayList<>()).add(file); } q.close(); }
        for (Uri file : legacy) DocumentsContract.deleteDocument(c.getContentResolver(), file); ArrayList<String> names = new ArrayList<>(sets.keySet()); names.sort(Collections.reverseOrder()); for (int i = keep; i < names.size(); i++) for (Uri file : sets.get(names.get(i))) DocumentsContract.deleteDocument(c.getContentResolver(), file);
    }

    // ============================================================
    // Excel export (legacy compatibility)
    // ============================================================

    private static String excel(AndroidContactsSnapshot snapshot) {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\"?><Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\" xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\"><Worksheet ss:Name=\"Contacts\"><Table>");
        b.append("<Row><Cell><Data ss:Type=\"String\">Name</Data></Cell><Cell><Data ss:Type=\"String\">Phone</Data></Cell><Cell><Data ss:Type=\"String\">Email</Data></Cell><Cell><Data ss:Type=\"String\">Address</Data></Cell><Cell><Data ss:Type=\"String\">Organization</Data></Cell><Cell><Data ss:Type=\"String\">Title</Data></Cell><Cell><Data ss:Type=\"String\">Nickname</Data></Cell><Cell><Data ss:Type=\"String\">Notes</Data></Cell><Cell><Data ss:Type=\"String\">Events</Data></Cell><Cell><Data ss:Type=\"String\">Websites</Data></Cell><Cell><Data ss:Type=\"String\">IM</Data></Cell><Cell><Data ss:Type=\"String\">Relations</Data></Cell></Row>");

        for (AndroidContactSnapshot contact : snapshot.contacts) {
            String name = contact.displayName != null ? contact.displayName : "";
            String phones = "", emails = "", addr = "", org = "", title = "", nick = "", notes = "", events = "", web = "", ims = "", rels = "";

            for (AndroidContactSnapshot.RawContactSnapshot rc : contact.rawContacts) {
                for (AndroidContactSnapshot.DataRowSnapshot row : rc.dataRows) {
                    if (row.mimeType == null) continue;
                    switch (row.mimeType) {
                        case "vnd.android.cursor.item/phone_v2":
                            if (row.data1 != null) phones = phones.isEmpty() ? row.data1 : phones + "; " + row.data1;
                            break;
                        case "vnd.android.cursor.item/email_v2":
                            if (row.data1 != null) emails = emails.isEmpty() ? row.data1 : emails + "; " + row.data1;
                            break;
                        case "vnd.android.cursor.item/postal-address_v2":
                        case "vnd.android.cursor.item/postal-address":
                            if (row.data6 != null && !row.data6.isEmpty())
                                addr = addr.isEmpty() ? row.data6 : addr + "; " + row.data6;
                            break;
                        case "vnd.android.cursor.item/organization":
                            if (row.data1 != null && org.isEmpty()) org = row.data1;
                            if (row.data4 != null && title.isEmpty()) title = row.data4;
                            break;
                        case "vnd.android.cursor.item/nickname":
                            if (row.data1 != null) nick = nick.isEmpty() ? row.data1 : nick + "; " + row.data1;
                            break;
                        case "vnd.android.cursor.item/note":
                            if (row.data1 != null) notes = notes.isEmpty() ? row.data1 : notes + "; " + row.data1;
                            break;
                        case "vnd.android.cursor.item/contact_event":
                            if (row.data1 != null) events = events.isEmpty() ? row.data1 : events + "; " + row.data1;
                            break;
                        case "vnd.android.cursor.item/website":
                            if (row.data1 != null) web = web.isEmpty() ? row.data1 : web + "; " + row.data1;
                            break;
                        case "vnd.android.cursor.item/im":
                            if (row.data1 != null) ims = ims.isEmpty() ? row.data1 : ims + "; " + row.data1;
                            break;
                        case "vnd.android.cursor.item/relation":
                            if (row.data1 != null) rels = rels.isEmpty() ? row.data1 : rels + "; " + row.data1;
                            break;
                    }
                }
            }

            b.append("<Row>");
            excelCell(b, name); excelCell(b, phones); excelCell(b, emails); excelCell(b, addr);
            excelCell(b, org); excelCell(b, title); excelCell(b, nick); excelCell(b, notes);
            excelCell(b, events); excelCell(b, web); excelCell(b, ims); excelCell(b, rels);
            b.append("</Row>");
        }
        return b.append("</Table></Worksheet></Workbook>").toString();
    }

    private static String xml(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;"); }
    private static void excelCell(StringBuilder b, String value) { b.append("<Cell><Data ss:Type=\"String\">").append(xml(value != null ? value : "")).append("</Data></Cell>"); }
}
