package com.librecontacts.backup;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.DocumentsContract;
import android.util.Base64;
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
import javax.crypto.SecretKeyFactory;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

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

    public static String runBackup(Context c, boolean notify) {
        try {
            if (folder(c).isEmpty()) return "Choose a folder first";
            Uri tree = Uri.parse(folder(c)); ArrayList<Person> people = read(c);
            String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
            boolean encrypted = prefs(c).getBoolean("encrypted", false);
            String password = encrypted ? loadEncryptionPassword(c) : null;
            if (encrypted && (password == null || password.isEmpty())) return "Set an encryption password first";
            byte[] archive = archive(people); if (encrypted) archive = encryptArchive(archive, password);
            writeArchive(c, tree, "librecontacts_" + stamp + (encrypted ? ".lcb.enc" : ".lcb"), archive);
            trim(c, tree); prefs(c).edit().putLong("last", System.currentTimeMillis()).apply();
            if (notify) MainActivity.notice(c, "Backup complete", "Saved " + people.size() + " contacts");
            return "Saved " + people.size() + " contacts";
        } catch (Exception e) { if (notify) MainActivity.notice(c, "Backup failed", e.getMessage()); return "Backup failed: " + e.getMessage(); }
    }

    private static ArrayList<Person> read(Context c) {
        ArrayList<Person> out = new ArrayList<>();
        Cursor cur = c.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.CONTACT_ID}, null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");
        LinkedHashMap<String, Person> map = new LinkedHashMap<>();
        if (cur != null) { while (cur.moveToNext()) { String id = cur.getString(2); Person p = map.get(id); if (p == null) { p = new Person(cur.getString(0)); map.put(id, p); } p.phones.add(cur.getString(1)); } cur.close(); }
        out.addAll(map.values()); return out;
    }
    private static String esc(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\n", "\\n").replace(",", "\\,"); }
    private static String vcard(ArrayList<Person> ps) { StringBuilder b = new StringBuilder(); for (Person p : ps) { b.append("BEGIN:VCARD\nVERSION:3.0\nFN:").append(esc(p.name)).append('\n'); for (String n : p.phones) b.append("TEL:").append(esc(n)).append('\n'); b.append("END:VCARD\n"); } return b.toString(); }
    private static String json(ArrayList<Person> ps) { StringBuilder b = new StringBuilder("[\n"); for (int i = 0; i < ps.size(); i++) { Person p = ps.get(i); b.append("  {\"name\":\"").append(p.name.replace("\\", "\\\\").replace("\"", "\\\"")).append("\",\"phones\":["); for (int j = 0; j < p.phones.size(); j++) { if (j > 0) b.append(','); b.append('"').append(p.phones.get(j).replace("\"", "\\\"")).append('"'); } b.append("]}").append(i + 1 < ps.size() ? "," : "").append('\n'); } return b.append("]\n").toString(); }
    private static String csv(ArrayList<Person> ps) { StringBuilder b = new StringBuilder("name,phone\n"); for (Person p : ps) for (String n : p.phones) b.append('"').append(p.name.replace("\"", "\"\"")).append("\",\"").append(n.replace("\"", "\"\"")).append("\"\n"); return b.toString(); }
    public static void writeManualExport(Context c, Uri destination, String format) throws Exception {
        ArrayList<Person> people = read(c); String value; String mime;
        if (format.equals("vcf")) { value = vcard(people); mime = "text/x-vcard"; }
        else if (format.equals("xls")) { value = excel(people); mime = "application/vnd.ms-excel"; }
        else { value = csv(people); mime = "text/csv"; }
        try (OutputStream out = c.getContentResolver().openOutputStream(destination)) { if (out == null) throw new IOException("Cannot open export destination"); out.write(value.getBytes(StandardCharsets.UTF_8)); }
        MainActivity.notice(c, "Manual export complete", "Saved " + people.size() + " contacts");
    }
    private static String xml(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;"); }
    private static String excel(ArrayList<Person> people) { StringBuilder b = new StringBuilder("<?xml version=\"1.0\"?><Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\" xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\"><Worksheet ss:Name=\"Contacts\"><Table><Row><Cell><Data ss:Type=\"String\">Name</Data></Cell><Cell><Data ss:Type=\"String\">Phone</Data></Cell></Row>"); for (Person p : people) for (String phone : p.phones) b.append("<Row><Cell><Data ss:Type=\"String\">").append(xml(p.name)).append("</Data></Cell><Cell><Data ss:Type=\"String\">").append(xml(phone)).append("</Data></Cell></Row>"); return b.append("</Table></Worksheet></Workbook>").toString(); }

    private static byte[] archive(ArrayList<Person> people) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(output);
        addEntry(zip, "contacts.vcf", vcard(people)); addEntry(zip, "contacts.json", json(people)); addEntry(zip, "contacts.csv", csv(people)); zip.finish(); zip.close(); return output.toByteArray();
    }
    private static void addEntry(ZipOutputStream zip, String name, String value) throws IOException { zip.putNextEntry(new ZipEntry(name)); zip.write(value.getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); }
    private static void writeArchive(Context c, Uri tree, String name, byte[] bytes) throws Exception {
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        Uri file = DocumentsContract.createDocument(c.getContentResolver(), parent, "application/octet-stream", name);
        if (file == null) throw new IOException("Cannot create backup file");
        try (OutputStream out = c.getContentResolver().openOutputStream(file)) { out.write(bytes); }
    }

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
    public static void restore(Context c, Uri file, String password, RestoreProgress progress) throws Exception {
        progress.update("Opening backup", 0, 0); byte[] data; try (InputStream in = c.getContentResolver().openInputStream(file)) { data = readAll(in); } progress.update("Decrypting backup", 0, 0); data = decryptArchive(data, password);
        ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data)); String vcard = null; ZipEntry entry; while ((entry = zip.getNextEntry()) != null) { if (entry.getName().equals("contacts.vcf")) vcard = new String(readAll(zip), StandardCharsets.UTF_8); zip.closeEntry(); } zip.close();
        if (vcard == null) throw new IOException("Choose a Libre Contacts Backup file"); int added = 0;
        String[] cards = vcard.split("BEGIN:VCARD"); int total = 0; for (String card : cards) if (card.contains("FN:")) total++; progress.update("Restoring contacts", 0, total);
        int current = 0; for (String card : cards) { String name = null; ArrayList<String> phones = new ArrayList<>(); for (String line : card.split("\n")) { if (line.startsWith("FN:")) name = line.substring(3).replace("\\n", "\n"); if (line.startsWith("TEL:")) phones.add(line.substring(4)); } if (name != null) { current++; progress.update("Restoring " + current + " of " + total, current, total); ArrayList<ContentProviderOperation> ops = new ArrayList<>(); ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI).withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null).withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build()); ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE).withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name).build()); for (String phone : phones) ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE).withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone).build()); try { c.getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops); added++; } catch (Exception ignored) {} } }
        prefs(c).edit().putLong("lastRestore", System.currentTimeMillis()).putInt("lastRestoreCount", added).apply(); MainActivity.notice(c, "Restore complete", "Added " + added + " contacts");
    }

    private static void trim(Context c, Uri tree) throws Exception {
        int keep = prefs(c).getInt("keep", 5); Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)); LinkedHashMap<String, ArrayList<Uri>> sets = new LinkedHashMap<>(); ArrayList<Uri> legacy = new ArrayList<>();
        Cursor q = c.getContentResolver().query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
        if (q != null) { while (q.moveToNext()) { String name = q.getString(1); if (!name.startsWith("librecontacts_")) continue; Uri file = DocumentsContract.buildDocumentUriUsingTree(tree, q.getString(0)); if (!(name.endsWith(".lcb") || name.endsWith(".lcb.enc"))) { legacy.add(file); continue; } String base = name; for (String suffix : new String[]{".enc", ".lcb"}) if (base.endsWith(suffix)) base = base.substring(0, base.length() - suffix.length()); sets.computeIfAbsent(base, k -> new ArrayList<>()).add(file); } q.close(); }
        for (Uri file : legacy) DocumentsContract.deleteDocument(c.getContentResolver(), file); ArrayList<String> names = new ArrayList<>(sets.keySet()); names.sort(Collections.reverseOrder()); for (int i = keep; i < names.size(); i++) for (Uri file : sets.get(names.get(i))) DocumentsContract.deleteDocument(c.getContentResolver(), file);
    }
    private static byte[] readAll(InputStream input) throws IOException { ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n; while ((n = input.read(buffer)) > 0) output.write(buffer, 0, n); return output.toByteArray(); }
    private static class Person { String name; ArrayList<String> phones = new ArrayList<>(); Person(String value) { name = value == null ? "Unnamed" : value; } }
}
