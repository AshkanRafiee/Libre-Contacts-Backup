package com.ashkanrafiee.librecontactsbackup;

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
    private static final String MIME_NAME = "vnd.android.cursor.item/name";
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

    private static String typeLabel(String typeInt, String customLabel) {
        if (typeInt == null) return "";
        int t; try { t = Integer.parseInt(typeInt); } catch (Exception e) { return ""; }
        switch (t) { case 1: return "home"; case 2: return "work"; case 3: return "other"; case -1: return "mobile"; default: return (t == 0 && customLabel != null && !customLabel.isEmpty()) ? customLabel.toLowerCase(Locale.US) : ""; }
    }
    private static String eventTypeLabel(String typeInt) {
        if (typeInt == null) return ""; int t; try { t = Integer.parseInt(typeInt); } catch (Exception e) { return ""; }
        switch (t) { case 1: return "birthday"; case 2: return "anniversary"; default: return "other"; }
    }
    private static String imProtocolLabel(String protoInt) {
        if (protoInt == null) return ""; int t; try { t = Integer.parseInt(protoInt); } catch (Exception e) { return ""; }
        switch (t) { case 0: return "aim"; case 1: return "msn"; case 2: return "yahoo"; case 3: return "skype"; case 4: return "qq"; case 5: return "icq"; case 6: return "jabber"; case 7: return "irc"; default: return ""; }
    }
    private static String relationTypeLabel(String typeInt) {
        if (typeInt == null) return ""; int t; try { t = Integer.parseInt(typeInt); } catch (Exception e) { return ""; }
        switch (t) { case 1: return "assistant"; case 2: return "brother"; case 3: return "child"; case 4: return "partner"; case 5: return "father"; case 6: return "friend"; case 7: return "manager"; case 8: return "mother"; case 9: return "parent"; case 10: return "domestic partner"; case 11: return "sister"; case 12: return "spouse"; case 13: return "relative"; default: return ""; }
    }

    private static ArrayList<Person> read(Context c) {
        ArrayList<Person> out = new ArrayList<>();
        Cursor cur = c.getContentResolver().query(ContactsContract.Data.CONTENT_URI, new String[]{
            ContactsContract.Data.RAW_CONTACT_ID, ContactsContract.Data.DISPLAY_NAME, ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.DATA1, ContactsContract.Data.DATA2, ContactsContract.Data.DATA3, ContactsContract.Data.DATA4,
            ContactsContract.Data.DATA5, ContactsContract.Data.DATA6, ContactsContract.Data.DATA7, ContactsContract.Data.DATA8,
            ContactsContract.Data.DATA9, ContactsContract.Data.DATA10, ContactsContract.Data.DATA15
        }, null, null, ContactsContract.Data.DISPLAY_NAME + " ASC");
        LinkedHashMap<String, Person> map = new LinkedHashMap<>();
        if (cur != null) {
            int iId = cur.getColumnIndex(ContactsContract.Data.RAW_CONTACT_ID), iName = cur.getColumnIndex(ContactsContract.Data.DISPLAY_NAME), iMime = cur.getColumnIndex(ContactsContract.Data.MIMETYPE);
            int d1 = cur.getColumnIndex(ContactsContract.Data.DATA1), d2 = cur.getColumnIndex(ContactsContract.Data.DATA2), d3 = cur.getColumnIndex(ContactsContract.Data.DATA3), d4 = cur.getColumnIndex(ContactsContract.Data.DATA4);
            int d5 = cur.getColumnIndex(ContactsContract.Data.DATA5), d6 = cur.getColumnIndex(ContactsContract.Data.DATA6), d7 = cur.getColumnIndex(ContactsContract.Data.DATA7), d8 = cur.getColumnIndex(ContactsContract.Data.DATA8);
            int d9 = cur.getColumnIndex(ContactsContract.Data.DATA9), d10 = cur.getColumnIndex(ContactsContract.Data.DATA10);
            int d15 = cur.getColumnIndex(ContactsContract.Data.DATA15);
            int iPobox = cur.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.POBOX);
            int iStreet = cur.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.STREET);
            int iCity = cur.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.CITY);
            int iRegion = cur.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.REGION);
            int iPostcode = cur.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE);
            int iCountry = cur.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY);
            int iPostalType = cur.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.TYPE);
            int iPostalLabel = cur.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.LABEL);
            while (cur.moveToNext()) {
                String rawId = cur.getString(iId);
                Person p = map.get(rawId); if (p == null) { p = new Person(cur.getString(iName)); map.put(rawId, p); }
                String mime = cur.getString(iMime); if (mime == null) continue;
                String v1 = cur.getString(d1), v2 = cur.getString(d2), v3 = cur.getString(d3), v4 = cur.getString(d4);
                String v5 = cur.getString(d5), v6 = cur.getString(d6), v7 = cur.getString(d7), v8 = cur.getString(d8);
                String v9 = cur.getString(d9), v10 = cur.getString(d10);
                if (mime.equals(MIME_PHONE)) {
                    if (v1 != null && !v1.isEmpty()) p.phones.add(new Field(v1, typeLabel(v2, v3)));
                } else if (mime.equals(MIME_EMAIL)) {
                    if (v1 != null && !v1.isEmpty()) p.emails.add(new Field(v1, typeLabel(v2, v3)));
                } else if (mime.equals(MIME_POSTAL) || mime.equals(MIME_POSTAL_LEGACY)) {
                    String pobox = iPobox >= 0 ? cur.getString(iPobox) : null;
                    String street = iStreet >= 0 ? cur.getString(iStreet) : null;
                    String city = iCity >= 0 ? cur.getString(iCity) : null;
                    String region = iRegion >= 0 ? cur.getString(iRegion) : null;
                    String postcode = iPostcode >= 0 ? cur.getString(iPostcode) : null;
                    String country = iCountry >= 0 ? cur.getString(iCountry) : null;
                    String postalType = iPostalType >= 0 ? cur.getString(iPostalType) : null;
                    String postalLabel = iPostalLabel >= 0 ? cur.getString(iPostalLabel) : null;
                    if ((street == null || street.isEmpty()) && (city == null || city.isEmpty()) && (region == null || region.isEmpty()) && (postcode == null || postcode.isEmpty()) && (country == null || country.isEmpty()) && v1 != null && !v1.isEmpty()) {
                        street = v1;
                    }
                    p.addresses.add(new Address(pobox, "", street, city, region, postcode, country, typeLabel(postalType, postalLabel)));
                } else if (mime.equals(MIME_ORG)) {
                    if (v1 != null && !v1.isEmpty()) p.organization = v1;
                    if (v4 != null && !v4.isEmpty()) p.title = v4;
                } else if (mime.equals(MIME_NICK)) {
                    if (v1 != null && !v1.isEmpty()) p.nicknames.add(v1);
                } else if (mime.equals(MIME_NOTE)) {
                    if (v1 != null && !v1.isEmpty()) p.notes = v1;
                } else if (mime.equals(MIME_EVENT)) {
                    if (v1 != null && !v1.isEmpty()) p.events.add(new Field(v1, eventTypeLabel(v2)));
                } else if (mime.equals(MIME_IM)) {
                    if (v1 != null && !v1.isEmpty()) p.ims.add(new Field(v1, imProtocolLabel(v5)));
                } else if (mime.equals(MIME_WEB)) {
                    if (v1 != null && !v1.isEmpty()) p.websites.add(new Field(v1, typeLabel(v2, v3)));
                } else if (mime.equals(MIME_REL)) {
                    if (v1 != null && !v1.isEmpty()) p.relations.add(new Field(v1, relationTypeLabel(v2)));
                } else if (mime.equals(MIME_PHOTO)) {
                    byte[] photo = cur.getBlob(d15);
                    if (photo != null && photo.length > 0) { p.photo = photo; p.photoType = detectPhotoType(photo); }
                }
            }
            cur.close();
        }
        out.addAll(map.values()); return out;
    }

    private static String esc(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\n", "\\n").replace(",", "\\,"); }
    private static String unesc(String s) { if (s == null || s.isEmpty()) return s; StringBuilder b = new StringBuilder(); for (int i = 0; i < s.length(); i++) { char c = s.charAt(i); if (c == '\\' && i + 1 < s.length()) { char next = s.charAt(++i); if (next == 'n') b.append('\n'); else if (next == ',') b.append(','); else if (next == '\\') b.append('\\'); else { b.append('\\'); b.append(next); } } else b.append(c); } return b.toString(); }
    private static String jsonEsc(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"); }
    private static String typeParam(String type) { return type.isEmpty() ? "" : ";TYPE=" + type.toUpperCase(Locale.US); }
    private static String detectPhotoType(byte[] data) {
        if (data == null || data.length < 3) return "jpeg";
        if ((data[0] & 0xFF) == 0x89 && (data[1] & 0xFF) == 0x50 && (data[2] & 0xFF) == 0x4E) return "png";
        return "jpeg";
    }
    private static String vcard(ArrayList<Person> ps) {
        StringBuilder b = new StringBuilder();
        for (Person p : ps) {
            b.append("BEGIN:VCARD\nVERSION:3.0\nFN:").append(esc(p.name)).append('\n');
            for (Field f : p.phones) b.append("TEL").append(typeParam(f.type)).append(':').append(esc(f.value)).append('\n');
            for (Field f : p.emails) b.append("EMAIL").append(typeParam(f.type)).append(':').append(esc(f.value)).append('\n');
            for (Address a : p.addresses) b.append("ADR").append(typeParam(a.type)).append(':').append(esc(a.pobox)).append(';').append(esc(a.neighborhood)).append(';').append(esc(a.street)).append(';').append(esc(a.city)).append(';').append(esc(a.region)).append(';').append(esc(a.postcode)).append(';').append(esc(a.country)).append('\n');
            if (!p.organization.isEmpty()) b.append("ORG:").append(esc(p.organization)).append('\n');
            if (!p.title.isEmpty()) b.append("TITLE:").append(esc(p.title)).append('\n');
            for (String n : p.nicknames) b.append("NICKNAME:").append(esc(n)).append('\n');
            if (!p.notes.isEmpty()) b.append("NOTE:").append(esc(p.notes)).append('\n');
            for (Field f : p.events) { if (f.type.equals("anniversary")) b.append("X-ANNIVERSARY:"); else b.append("BDAY:"); b.append(esc(f.value)).append('\n'); }
            for (Field f : p.websites) b.append("URL").append(typeParam(f.type)).append(':').append(esc(f.value)).append('\n');
            for (Field f : p.ims) b.append("IMPP").append(typeParam(f.type)).append(':').append(esc(f.value)).append('\n');
            for (Field f : p.relations) b.append("X-RELATION").append(typeParam(f.type)).append(':').append(esc(f.value)).append('\n');
            if (p.photo != null && p.photo.length > 0) b.append("PHOTO;ENCODING=b;TYPE=").append(p.photoType.toUpperCase(Locale.US)).append(':').append(Base64.encodeToString(p.photo, Base64.NO_WRAP)).append('\n');
            b.append("END:VCARD\n");
        }
        return b.toString();
    }
    private static String jsonArrFields(ArrayList<Field> list) {
        StringBuilder b = new StringBuilder("[");
        for (int j = 0; j < list.size(); j++) { if (j > 0) b.append(','); b.append("{\"value\":\"").append(jsonEsc(list.get(j).value)).append("\",\"type\":\"").append(jsonEsc(list.get(j).type)).append("\"}"); }
        return b.append(']').toString();
    }
    private static String jsonArrStr(ArrayList<String> list) {
        StringBuilder b = new StringBuilder("[");
        for (int j = 0; j < list.size(); j++) { if (j > 0) b.append(','); b.append('"').append(jsonEsc(list.get(j))).append('"'); }
        return b.append(']').toString();
    }
    private static String jsonArrAddr(ArrayList<Address> list) {
        StringBuilder b = new StringBuilder("[");
        for (int j = 0; j < list.size(); j++) { if (j > 0) b.append(','); Address a = list.get(j);
            b.append("{\"pobox\":\"").append(jsonEsc(a.pobox)).append("\",\"neighborhood\":\"").append(jsonEsc(a.neighborhood)).append("\",\"street\":\"").append(jsonEsc(a.street)).append("\",\"city\":\"").append(jsonEsc(a.city)).append("\",\"region\":\"").append(jsonEsc(a.region)).append("\",\"postcode\":\"").append(jsonEsc(a.postcode)).append("\",\"country\":\"").append(jsonEsc(a.country)).append("\",\"type\":\"").append(jsonEsc(a.type)).append("\"}"); }
        return b.append(']').toString();
    }
    private static String json(ArrayList<Person> ps) {
        StringBuilder b = new StringBuilder("[\n");
        for (int i = 0; i < ps.size(); i++) {
            Person p = ps.get(i); b.append("  {\"name\":\"").append(jsonEsc(p.name)).append('"');
            b.append(",\"phones\":").append(jsonArrFields(p.phones));
            b.append(",\"emails\":").append(jsonArrFields(p.emails));
            b.append(",\"addresses\":").append(jsonArrAddr(p.addresses));
            b.append(",\"organization\":\"").append(jsonEsc(p.organization)).append("\"");
            b.append(",\"title\":\"").append(jsonEsc(p.title)).append("\"");
            b.append(",\"nicknames\":").append(jsonArrStr(p.nicknames));
            b.append(",\"notes\":\"").append(jsonEsc(p.notes)).append("\"");
            b.append(",\"events\":").append(jsonArrFields(p.events));
            b.append(",\"websites\":").append(jsonArrFields(p.websites));
            b.append(",\"ims\":").append(jsonArrFields(p.ims));
            b.append(",\"relations\":").append(jsonArrFields(p.relations));
            if (p.photo != null && p.photo.length > 0) { b.append(",\"photo\":\"").append(Base64.encodeToString(p.photo, Base64.NO_WRAP)).append("\",\"photoType\":\"").append(p.photoType).append('"'); }
            b.append("}").append(i + 1 < ps.size() ? "," : "").append('\n');
        }
        return b.append("]\n").toString();
    }
    private static String csvEsc(String s) { return s == null ? "" : s.replace("\"", "\"\""); }
    private static String joinFields(ArrayList<Field> list) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < list.size(); i++) { if (i > 0) b.append("; "); Field f = list.get(i); if (!f.type.isEmpty()) b.append(f.type).append(":"); b.append(f.value); }
        return b.toString();
    }
    private static String joinStr(ArrayList<String> list) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < list.size(); i++) { if (i > 0) b.append("; "); b.append(list.get(i)); }
        return b.toString();
    }
    private static String joinAddr(ArrayList<Address> list) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < list.size(); i++) { if (i > 0) b.append("; "); Address a = list.get(i); if (!a.type.isEmpty()) b.append(a.type).append(":"); if (!a.pobox.isEmpty()) b.append(a.pobox).append(", "); b.append(a.street).append(", "); if (!a.neighborhood.isEmpty()) b.append(a.neighborhood).append(", "); b.append(a.city).append(", ").append(a.region).append(" ").append(a.postcode).append(", ").append(a.country); }
        return b.toString();
    }
    private static String csv(ArrayList<Person> ps) {
        StringBuilder b = new StringBuilder("name,phone,email,address,organization,title,nickname,notes,events,websites,ims,relations\n");
        for (Person p : ps) b.append('"').append(csvEsc(p.name)).append("\",\"").append(csvEsc(joinFields(p.phones))).append("\",\"").append(csvEsc(joinFields(p.emails))).append("\",\"").append(csvEsc(joinAddr(p.addresses))).append("\",\"").append(csvEsc(p.organization)).append("\",\"").append(csvEsc(p.title)).append("\",\"").append(csvEsc(joinStr(p.nicknames))).append("\",\"").append(csvEsc(p.notes)).append("\",\"").append(csvEsc(joinFields(p.events))).append("\",\"").append(csvEsc(joinFields(p.websites))).append("\",\"").append(csvEsc(joinFields(p.ims))).append("\",\"").append(csvEsc(joinFields(p.relations))).append("\"\n");
        return b.toString();
    }
    public static void writeManualExport(Context c, Uri destination, String format) throws Exception {
        ArrayList<Person> people = read(c); String value; String mime;
        if (format.equals("vcf")) { value = vcard(people); mime = "text/x-vcard"; }
        else if (format.equals("xls")) { value = excel(people); mime = "application/vnd.ms-excel"; }
        else { value = csv(people); mime = "text/csv"; }
        try (OutputStream out = c.getContentResolver().openOutputStream(destination)) { if (out == null) throw new IOException("Cannot open export destination"); out.write(value.getBytes(StandardCharsets.UTF_8)); }
        MainActivity.notice(c, "Manual export complete", "Saved " + people.size() + " contacts");
    }
    private static String xml(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;"); }
    private static void excelCell(StringBuilder b, String value) { b.append("<Cell><Data ss:Type=\"String\">").append(xml(value)).append("</Data></Cell>"); }
    private static String excel(ArrayList<Person> people) {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\"?><Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\" xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\"><Worksheet ss:Name=\"Contacts\"><Table>");
        b.append("<Row><Cell><Data ss:Type=\"String\">Name</Data></Cell><Cell><Data ss:Type=\"String\">Phone</Data></Cell><Cell><Data ss:Type=\"String\">Email</Data></Cell><Cell><Data ss:Type=\"String\">Address</Data></Cell><Cell><Data ss:Type=\"String\">Organization</Data></Cell><Cell><Data ss:Type=\"String\">Title</Data></Cell><Cell><Data ss:Type=\"String\">Nickname</Data></Cell><Cell><Data ss:Type=\"String\">Notes</Data></Cell><Cell><Data ss:Type=\"String\">Events</Data></Cell><Cell><Data ss:Type=\"String\">Websites</Data></Cell><Cell><Data ss:Type=\"String\">IM</Data></Cell><Cell><Data ss:Type=\"String\">Relations</Data></Cell></Row>");
        for (Person p : people) { b.append("<Row>"); excelCell(b, p.name); excelCell(b, joinFields(p.phones)); excelCell(b, joinFields(p.emails)); excelCell(b, joinAddr(p.addresses)); excelCell(b, p.organization); excelCell(b, p.title); excelCell(b, joinStr(p.nicknames)); excelCell(b, p.notes); excelCell(b, joinFields(p.events)); excelCell(b, joinFields(p.websites)); excelCell(b, joinFields(p.ims)); excelCell(b, joinFields(p.relations)); b.append("</Row>"); }
        return b.append("</Table></Worksheet></Workbook>").toString();
    }

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

    private static String parseVcardType(String line, String prefix) {
        String upper = line.toUpperCase(Locale.US);
        if (!upper.startsWith(prefix)) return null;
        String rest = line.substring(prefix.length());
        int colon = rest.indexOf(':'); if (colon < 0) return null;
        String props = rest.substring(0, colon); String value = rest.substring(colon + 1);
        String[] parts = props.split(";"); String type = "";
        for (String part : parts) { String p = part.trim(); if (p.startsWith("TYPE=")) type = p.substring(5).toLowerCase(Locale.US); }
        if (type.equals("home") || type.equals("work") || type.equals("mobile") || type.equals("other")) return value + "\t" + type;
        return value + "\t" + type;
    }
    private static int typeToInt(String type) {
        if (type == null || type.isEmpty()) return 0;
        switch (type.toLowerCase(Locale.US)) { case "home": return 1; case "work": return 2; case "other": return 3; case "mobile": return -1; default: return 0; }
    }
    private static int eventtypeToInt(String type) {
        if (type == null || type.isEmpty()) return 0;
        switch (type.toLowerCase(Locale.US)) { case "birthday": return 1; case "anniversary": return 2; default: return 3; }
    }
    private static int imProtocolToInt(String proto) {
        if (proto == null || proto.isEmpty()) return 0;
        switch (proto.toLowerCase(Locale.US)) { case "aim": return 0; case "msn": return 1; case "yahoo": return 2; case "skype": return 3; case "qq": return 4; case "icq": return 5; case "jabber": return 6; case "irc": return 7; default: return 0; }
    }
    private static int relationTypeToInt(String type) {
        if (type == null || type.isEmpty()) return 0;
        switch (type.toLowerCase(Locale.US)) { case "assistant": return 1; case "brother": return 2; case "child": return 3; case "partner": return 4; case "father": return 5; case "friend": return 6; case "manager": return 7; case "mother": return 8; case "parent": return 9; case "domestic partner": return 10; case "sister": return 11; case "spouse": return 12; case "relative": return 13; default: return 0; }
    }

    private static String normalizeName(String n) { return n == null ? "" : n.trim().toLowerCase(Locale.US).replaceAll("\\s+", " "); }
    private static Set<String> existingPhones(String rawId, Context c) {
        Set<String> s = new HashSet<>(); Cursor cur = c.getContentResolver().query(ContactsContract.Data.CONTENT_URI, new String[]{ContactsContract.Data.DATA1}, ContactsContract.Data.RAW_CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=?", new String[]{rawId, MIME_PHONE}, null);
        if (cur != null) { while (cur.moveToNext()) { String v = cur.getString(0); if (v != null && !v.isEmpty()) s.add(v.replaceAll("\\s+", "")); } cur.close(); } return s;
    }
    private static Set<String> existingEmails(String rawId, Context c) {
        Set<String> s = new HashSet<>(); Cursor cur = c.getContentResolver().query(ContactsContract.Data.CONTENT_URI, new String[]{ContactsContract.Data.DATA1}, ContactsContract.Data.RAW_CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=?", new String[]{rawId, MIME_EMAIL}, null);
        if (cur != null) { while (cur.moveToNext()) { String v = cur.getString(0); if (v != null && !v.isEmpty()) s.add(v.toLowerCase(Locale.US)); } cur.close(); } return s;
    }
    private static boolean hasPhoneOrEmail(String rawId, ArrayList<String[]> phones, ArrayList<String[]> emails, Context c) {
        Set<String> ep = existingPhones(rawId, c); Set<String> ee = existingEmails(rawId, c);
        for (String[] ph : phones) { String norm = ph[0].replaceAll("\\s+", ""); if (ep.contains(norm)) return true; }
        for (String[] em : emails) { if (ee.contains(em[0].toLowerCase(Locale.US))) return true; }
        return false;
    }
    private static void addPhone(String rawId, String[] phone, Context c) {
        ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId).withValue(ContactsContract.Data.MIMETYPE, MIME_PHONE)
            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone[0]);
        int t = typeToInt(phone[1]); b.withValue(ContactsContract.CommonDataKinds.Phone.TYPE, t); if (t == 0 && phone[1] != null && !phone[1].isEmpty()) b.withValue(ContactsContract.CommonDataKinds.Phone.LABEL, phone[1]);
        try { c.getContentResolver().applyBatch(ContactsContract.AUTHORITY, new ArrayList<>(Collections.singletonList(b.build()))); } catch (Exception ignored) {}
    }
    private static void addEmail(String rawId, String[] email, Context c) {
        ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId).withValue(ContactsContract.Data.MIMETYPE, MIME_EMAIL)
            .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email[0]);
        int t = typeToInt(email[1]); b.withValue(ContactsContract.CommonDataKinds.Email.TYPE, t); if (t == 0 && email[1] != null && !email[1].isEmpty()) b.withValue(ContactsContract.CommonDataKinds.Email.LABEL, email[1]);
        try { c.getContentResolver().applyBatch(ContactsContract.AUTHORITY, new ArrayList<>(Collections.singletonList(b.build()))); } catch (Exception ignored) {}
    }
    private static void addAddress(String rawId, String[] addr, Context c) {
        if (addr[0].isEmpty() && addr[1].isEmpty() && addr[2].isEmpty() && addr[3].isEmpty() && addr[4].isEmpty() && addr[5].isEmpty() && addr[6].isEmpty()) return;
        ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId).withValue(ContactsContract.Data.MIMETYPE, MIME_POSTAL);
        if (!addr[0].isEmpty()) b.withValue(ContactsContract.CommonDataKinds.StructuredPostal.POBOX, addr[0]);
        if (!addr[1].isEmpty()) b.withValue(ContactsContract.CommonDataKinds.StructuredPostal.NEIGHBORHOOD, addr[1]);
        if (!addr[2].isEmpty()) b.withValue(ContactsContract.CommonDataKinds.StructuredPostal.STREET, addr[2]);
        if (!addr[3].isEmpty()) b.withValue(ContactsContract.CommonDataKinds.StructuredPostal.CITY, addr[3]);
        if (!addr[4].isEmpty()) b.withValue(ContactsContract.CommonDataKinds.StructuredPostal.REGION, addr[4]);
        if (!addr[5].isEmpty()) b.withValue(ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE, addr[5]);
        if (!addr[6].isEmpty()) b.withValue(ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY, addr[6]);
        int t = typeToInt(addr[7]); if (t != 0) b.withValue(ContactsContract.CommonDataKinds.StructuredPostal.TYPE, t); else if (!addr[7].isEmpty()) b.withValue(ContactsContract.CommonDataKinds.StructuredPostal.LABEL, addr[7]);
        try { c.getContentResolver().applyBatch(ContactsContract.AUTHORITY, new ArrayList<>(Collections.singletonList(b.build()))); } catch (Exception ignored) {}
    }
    private static String querySingle(String rawId, String mime, String column, Context c) {
        Cursor cur = c.getContentResolver().query(ContactsContract.Data.CONTENT_URI, new String[]{column}, ContactsContract.Data.RAW_CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=?", new String[]{rawId, mime}, null);
        String val = null; if (cur != null) { if (cur.moveToFirst()) val = cur.getString(0); cur.close(); } return val;
    }
    private static void setIfEmpty(String rawId, String mime, String column, String value, Context c) {
        if (value == null || value.isEmpty()) return; String existing = querySingle(rawId, mime, column, c);
        if (existing != null && !existing.isEmpty()) return;
        ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId).withValue(ContactsContract.Data.MIMETYPE, mime).withValue(column, value);
        try { c.getContentResolver().applyBatch(ContactsContract.AUTHORITY, new ArrayList<>(Collections.singletonList(b.build()))); } catch (Exception ignored) {}
    }
    private static void addEvent(String rawId, String[] ev, Context c) {
        ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId).withValue(ContactsContract.Data.MIMETYPE, MIME_EVENT)
            .withValue(ContactsContract.CommonDataKinds.Event.START_DATE, ev[0]);
        int t = eventtypeToInt(ev[1]); if (t != 0) b.withValue(ContactsContract.CommonDataKinds.Event.TYPE, t);
        try { c.getContentResolver().applyBatch(ContactsContract.AUTHORITY, new ArrayList<>(Collections.singletonList(b.build()))); } catch (Exception ignored) {}
    }
    private static void addWebsite(String rawId, String[] w, Context c) {
        ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId).withValue(ContactsContract.Data.MIMETYPE, MIME_WEB)
            .withValue(ContactsContract.CommonDataKinds.Website.URL, w[0]);
        int t = typeToInt(w[1]); if (t != 0) b.withValue(ContactsContract.CommonDataKinds.Website.TYPE, t); else if (w[1] != null && !w[1].isEmpty()) b.withValue(ContactsContract.CommonDataKinds.Website.LABEL, w[1]);
        try { c.getContentResolver().applyBatch(ContactsContract.AUTHORITY, new ArrayList<>(Collections.singletonList(b.build()))); } catch (Exception ignored) {}
    }
    private static void addIm(String rawId, String[] im, Context c) {
        ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId).withValue(ContactsContract.Data.MIMETYPE, MIME_IM)
            .withValue(ContactsContract.CommonDataKinds.Im.DATA, im[0]);
        int t = imProtocolToInt(im[1]); if (t >= 0) b.withValue(ContactsContract.CommonDataKinds.Im.PROTOCOL, t); else if (im[1] != null && !im[1].isEmpty()) b.withValue(ContactsContract.CommonDataKinds.Im.CUSTOM_PROTOCOL, im[1]);
        try { c.getContentResolver().applyBatch(ContactsContract.AUTHORITY, new ArrayList<>(Collections.singletonList(b.build()))); } catch (Exception ignored) {}
    }
    private static void addRelation(String rawId, String[] rel, Context c) {
        ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId).withValue(ContactsContract.Data.MIMETYPE, MIME_REL)
            .withValue(ContactsContract.CommonDataKinds.Relation.NAME, rel[0]);
        int t = relationTypeToInt(rel[1]); if (t != 0) b.withValue(ContactsContract.CommonDataKinds.Relation.TYPE, t); else if (rel[1] != null && !rel[1].isEmpty()) b.withValue(ContactsContract.CommonDataKinds.Relation.LABEL, rel[1]);
        try { c.getContentResolver().applyBatch(ContactsContract.AUTHORITY, new ArrayList<>(Collections.singletonList(b.build()))); } catch (Exception ignored) {}
    }
    private static String createRawContact(String name, Context c) throws Exception {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI).withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null).withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build());
        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).withValue(ContactsContract.Data.MIMETYPE, MIME_NAME).withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name).build());
        ContentProviderResult[] results = c.getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
        return results[0].uri.getLastPathSegment();
    }

    public static void restore(Context c, Uri file, String password, RestoreProgress progress) throws Exception {
        progress.update("Opening backup", 0, 0); byte[] data; try (InputStream in = c.getContentResolver().openInputStream(file)) { data = readAll(in); } progress.update("Decrypting backup", 0, 0); data = decryptArchive(data, password);
        ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data)); String vcard = null; ZipEntry entry; while ((entry = zip.getNextEntry()) != null) { if (entry.getName().equals("contacts.vcf")) vcard = new String(readAll(zip), StandardCharsets.UTF_8); zip.closeEntry(); } zip.close();
        if (vcard == null) throw new IOException("Choose a Libre Contacts Backup file");
        StringBuilder unfolded = new StringBuilder();
        for (String rawLine : vcard.split("\r?\n")) { String line = rawLine.replaceAll("\\s+$", ""); if (unfolded.length() > 0 && !line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t')) { unfolded.setLength(unfolded.length() - 1); unfolded.append(line.substring(1)); } else unfolded.append(line).append('\n'); }
        vcard = unfolded.toString();

        ArrayList<Person> existing = read(c);
        LinkedHashMap<String, ArrayList<String>> existingByNormName = new LinkedHashMap<>();
        Cursor rawCursor = c.getContentResolver().query(ContactsContract.Data.CONTENT_URI, new String[]{ContactsContract.Data.RAW_CONTACT_ID, ContactsContract.Data.DISPLAY_NAME}, ContactsContract.Data.MIMETYPE + "=?", new String[]{MIME_NAME}, ContactsContract.Data.DISPLAY_NAME + " ASC");
        LinkedHashMap<String, String> rawIdToDisplayName = new LinkedHashMap<>();
        if (rawCursor != null) { while (rawCursor.moveToNext()) { String rid = rawCursor.getString(0); String dn = rawCursor.getString(1); if (rid != null && dn != null) { rawIdToDisplayName.put(rid, dn); String key = normalizeName(dn); existingByNormName.computeIfAbsent(key, k -> new ArrayList<>()).add(rid); } } rawCursor.close(); }

        int added = 0, merged = 0;
        String[] cards = vcard.split("BEGIN:VCARD"); int total = 0; for (String card : cards) if (card.contains("FN:")) total++; progress.update("Restoring contacts", 0, total);
        int current = 0; for (String card : cards) {
            String name = null; ArrayList<String[]> phones = new ArrayList<>(); ArrayList<String[]> emails = new ArrayList<>();
            String org = null; String title = null; String notes = null; String nickname = null;
            ArrayList<String[]> events = new ArrayList<>(); ArrayList<String[]> websites = new ArrayList<>();
            ArrayList<String[]> ims = new ArrayList<>(); ArrayList<String[]> relations = new ArrayList<>();
            ArrayList<String[]> addresses = new ArrayList<>();
            byte[] photoBytes = null;
            for (String line : card.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("FN:")) name = unesc(trimmed.substring(3).replace("\\n", "\n"));
                else if (trimmed.toUpperCase(Locale.US).startsWith("TEL")) { String r = parseVcardType(trimmed, "TEL"); if (r != null) { String[] p = r.split("\t", 2); phones.add(new String[]{unesc(p[0]), p.length > 1 ? p[1] : ""}); } }
                else if (trimmed.toUpperCase(Locale.US).startsWith("EMAIL")) { String r = parseVcardType(trimmed, "EMAIL"); if (r != null) { String[] p = r.split("\t", 2); emails.add(new String[]{unesc(p[0]), p.length > 1 ? p[1] : ""}); } }
                else if (trimmed.toUpperCase(Locale.US).startsWith("ADR")) { String r = parseVcardType(trimmed, "ADR"); if (r != null) { String[] p = r.split("\t", 2); String aType = p.length > 1 ? p[1] : ""; String[] parts = unesc(p[0]).split(";", -1); if (parts.length >= 7) { addresses.add(new String[]{parts[0], parts[1], parts[2], parts[3], parts[4], parts[5], parts[6], aType}); } } }
                else if (trimmed.startsWith("ORG:")) org = unesc(trimmed.substring(4));
                else if (trimmed.startsWith("TITLE:")) title = unesc(trimmed.substring(6));
                else if (trimmed.startsWith("NICKNAME:")) nickname = unesc(trimmed.substring(9));
                else if (trimmed.startsWith("NOTE:")) notes = unesc(trimmed.substring(5));
                else if (trimmed.startsWith("BDAY:")) events.add(new String[]{trimmed.substring(5), "birthday"});
                else if (trimmed.startsWith("X-ANNIVERSARY:")) events.add(new String[]{trimmed.substring(14), "anniversary"});
                else if (trimmed.toUpperCase(Locale.US).startsWith("URL")) { String r = parseVcardType(trimmed, "URL"); if (r != null) { String[] p = r.split("\t", 2); websites.add(new String[]{p[0], p.length > 1 ? p[1] : ""}); } }
                else if (trimmed.toUpperCase(Locale.US).startsWith("IMPP")) { String r = parseVcardType(trimmed, "IMPP"); if (r != null) { String[] p = r.split("\t", 2); ims.add(new String[]{p[0], p.length > 1 ? p[1] : ""}); } }
                else if (trimmed.toUpperCase(Locale.US).startsWith("X-RELATION")) { String r = parseVcardType(trimmed, "X-RELATION"); if (r != null) { String[] p = r.split("\t", 2); relations.add(new String[]{p[0], p.length > 1 ? p[1] : ""}); } }
                else if (trimmed.toUpperCase(Locale.US).startsWith("PHOTO")) { String r = parseVcardType(trimmed, "PHOTO"); if (r != null) { String[] p = r.split("\t", 2); try { photoBytes = Base64.decode(p[0], Base64.NO_WRAP); } catch (Exception ignored) {} } }
            }
            if (name == null) continue;
            current++; progress.update("Restoring " + current + " of " + total, current, total);
            String nameKey = normalizeName(name);
            ArrayList<String> candidates = existingByNormName.get(nameKey);
            String matchRawId = null;
            if (candidates != null) {
                for (String rid : candidates) {
                    if (hasPhoneOrEmail(rid, phones, emails, c)) { matchRawId = rid; break; }
                }
                if (matchRawId == null && !candidates.isEmpty()) matchRawId = candidates.get(0);
            }
            if (matchRawId != null) {
                merged++;
                Set<String> ep = existingPhones(matchRawId, c);
                for (String[] ph : phones) { String norm = ph[0].replaceAll("\\s+", ""); if (!ep.contains(norm)) addPhone(matchRawId, ph, c); }
                Set<String> ee = existingEmails(matchRawId, c);
                for (String[] em : emails) { if (!ee.contains(em[0].toLowerCase(Locale.US))) addEmail(matchRawId, em, c); }
                for (String[] addr : addresses) addAddress(matchRawId, addr, c);
                setIfEmpty(matchRawId, MIME_ORG, ContactsContract.CommonDataKinds.Organization.COMPANY, org, c);
                setIfEmpty(matchRawId, MIME_ORG, ContactsContract.CommonDataKinds.Organization.TITLE, title, c);
                setIfEmpty(matchRawId, MIME_NICK, ContactsContract.CommonDataKinds.Nickname.NAME, nickname, c);
                setIfEmpty(matchRawId, MIME_NOTE, ContactsContract.CommonDataKinds.Note.NOTE, notes, c);
                for (String[] ev : events) addEvent(matchRawId, ev, c);
                for (String[] w : websites) addWebsite(matchRawId, w, c);
                for (String[] im : ims) addIm(matchRawId, im, c);
                for (String[] rel : relations) addRelation(matchRawId, rel, c);
                if (photoBytes != null && photoBytes.length > 0) { String existingPhoto = querySingle(matchRawId, MIME_PHOTO, ContactsContract.Data.DATA15, c); if (existingPhoto == null || existingPhoto.isEmpty()) { ContentProviderOperation b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValue(ContactsContract.Data.RAW_CONTACT_ID, matchRawId).withValue(ContactsContract.Data.MIMETYPE, MIME_PHOTO).withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, photoBytes).build(); try { c.getContentResolver().applyBatch(ContactsContract.AUTHORITY, new ArrayList<>(Collections.singletonList(b))); } catch (Exception ignored) {} } }
            } else {
                try {
                    ArrayList<ContentProviderOperation> ops = new ArrayList<>();
                    ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI).withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null).withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build());
                    ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).withValue(ContactsContract.Data.MIMETYPE, MIME_NAME).withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name).build());
                    for (String[] phone : phones) { ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).withValue(ContactsContract.Data.MIMETYPE, MIME_PHONE).withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone[0]); int t = typeToInt(phone[1]); b.withValue(ContactsContract.CommonDataKinds.Phone.TYPE, t); if (t == 0 && phone[1] != null && !phone[1].isEmpty()) b.withValue(ContactsContract.CommonDataKinds.Phone.LABEL, phone[1]); ops.add(b.build()); }
                    for (String[] email : emails) { ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).withValue(ContactsContract.Data.MIMETYPE, MIME_EMAIL).withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email[0]); int t = typeToInt(email[1]); b.withValue(ContactsContract.CommonDataKinds.Email.TYPE, t); if (t == 0 && email[1] != null && !email[1].isEmpty()) b.withValue(ContactsContract.CommonDataKinds.Email.LABEL, email[1]); ops.add(b.build()); }
                    for (String[] addr : addresses) { if (addr[0] != null && !addr[0].isEmpty() || addr[1] != null && !addr[1].isEmpty() || addr[2] != null && !addr[2].isEmpty() || addr[3] != null && !addr[3].isEmpty() || addr[4] != null && !addr[4].isEmpty() || addr[5] != null && !addr[5].isEmpty() || addr[6] != null && !addr[6].isEmpty()) { ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).withValue(ContactsContract.Data.MIMETYPE, MIME_POSTAL); if (addr[0] != null && !addr[0].isEmpty()) b.withValue(ContactsContract.CommonDataKinds.StructuredPostal.POBOX, addr[0]); if (addr[1] != null && !addr[1].isEmpty()) b.withValue(ContactsContract.CommonDataKinds.StructuredPostal.NEIGHBORHOOD, addr[1]); if (addr[2] != null && !addr[2].isEmpty()) b.withValue(ContactsContract.CommonDataKinds.StructuredPostal.STREET, addr[2]); if (addr[3] != null && !addr[3].isEmpty()) b.withValue(ContactsContract.CommonDataKinds.StructuredPostal.CITY, addr[3]); if (addr[4] != null && !addr[4].isEmpty()) b.withValue(ContactsContract.CommonDataKinds.StructuredPostal.REGION, addr[4]); if (addr[5] != null && !addr[5].isEmpty()) b.withValue(ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE, addr[5]); if (addr[6] != null && !addr[6].isEmpty()) b.withValue(ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY, addr[6]); int t = typeToInt(addr[7]); if (t != 0) b.withValue(ContactsContract.CommonDataKinds.StructuredPostal.TYPE, t); else if (addr[7] != null && !addr[7].isEmpty()) b.withValue(ContactsContract.CommonDataKinds.StructuredPostal.LABEL, addr[7]); ops.add(b.build()); } }
                    if (org != null && !org.isEmpty()) { ContentProviderOperation.Builder ob = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).withValue(ContactsContract.Data.MIMETYPE, MIME_ORG).withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, org); if (title != null && !title.isEmpty()) ob.withValue(ContactsContract.CommonDataKinds.Organization.TITLE, title); ops.add(ob.build()); } else if (title != null && !title.isEmpty()) ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).withValue(ContactsContract.Data.MIMETYPE, MIME_ORG).withValue(ContactsContract.CommonDataKinds.Organization.TITLE, title).build());
                    if (nickname != null && !nickname.isEmpty()) ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).withValue(ContactsContract.Data.MIMETYPE, MIME_NICK).withValue(ContactsContract.CommonDataKinds.Nickname.NAME, nickname).build());
                    if (notes != null && !notes.isEmpty()) ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).withValue(ContactsContract.Data.MIMETYPE, MIME_NOTE).withValue(ContactsContract.CommonDataKinds.Note.NOTE, notes).build());
                    for (String[] ev : events) { ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).withValue(ContactsContract.Data.MIMETYPE, MIME_EVENT).withValue(ContactsContract.CommonDataKinds.Event.START_DATE, ev[0]); int t = eventtypeToInt(ev[1]); if (t != 0) b.withValue(ContactsContract.CommonDataKinds.Event.TYPE, t); ops.add(b.build()); }
                    for (String[] w : websites) { ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).withValue(ContactsContract.Data.MIMETYPE, MIME_WEB).withValue(ContactsContract.CommonDataKinds.Website.URL, w[0]); int t = typeToInt(w[1]); if (t != 0) b.withValue(ContactsContract.CommonDataKinds.Website.TYPE, t); else if (w[1] != null && !w[1].isEmpty()) b.withValue(ContactsContract.CommonDataKinds.Website.LABEL, w[1]); ops.add(b.build()); }
                    for (String[] im : ims) { ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).withValue(ContactsContract.Data.MIMETYPE, MIME_IM).withValue(ContactsContract.CommonDataKinds.Im.DATA, im[0]); int t = imProtocolToInt(im[1]); if (t >= 0) b.withValue(ContactsContract.CommonDataKinds.Im.PROTOCOL, t); else if (im[1] != null && !im[1].isEmpty()) b.withValue(ContactsContract.CommonDataKinds.Im.CUSTOM_PROTOCOL, im[1]); ops.add(b.build()); }
                    for (String[] rel : relations) { ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).withValue(ContactsContract.Data.MIMETYPE, MIME_REL).withValue(ContactsContract.CommonDataKinds.Relation.NAME, rel[0]); int t = relationTypeToInt(rel[1]); if (t != 0) b.withValue(ContactsContract.CommonDataKinds.Relation.TYPE, t); else if (rel[1] != null && !rel[1].isEmpty()) b.withValue(ContactsContract.CommonDataKinds.Relation.LABEL, rel[1]); ops.add(b.build()); }
                    if (photoBytes != null && photoBytes.length > 0) ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).withValue(ContactsContract.Data.MIMETYPE, MIME_PHOTO).withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, photoBytes).build());
                    c.getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops); added++;
                } catch (Exception e) {
                    try { String rid = createRawContact(name, c); added++; for (String[] ph : phones) addPhone(rid, ph, c); for (String[] em : emails) addEmail(rid, em, c); for (String[] addr : addresses) addAddress(rid, addr, c); setIfEmpty(rid, MIME_ORG, ContactsContract.CommonDataKinds.Organization.COMPANY, org, c); setIfEmpty(rid, MIME_ORG, ContactsContract.CommonDataKinds.Organization.TITLE, title, c); setIfEmpty(rid, MIME_NICK, ContactsContract.CommonDataKinds.Nickname.NAME, nickname, c); setIfEmpty(rid, MIME_NOTE, ContactsContract.CommonDataKinds.Note.NOTE, notes, c); for (String[] ev : events) addEvent(rid, ev, c); for (String[] w : websites) addWebsite(rid, w, c); for (String[] im : ims) addIm(rid, im, c); for (String[] rel : relations) addRelation(rid, rel, c); } catch (Exception ignored) {}
                }
            }
        }
        prefs(c).edit().putLong("lastRestore", System.currentTimeMillis()).putInt("lastRestoreCount", added + merged).apply(); MainActivity.notice(c, "Restore complete", "Added " + added + ", merged " + merged + " contacts");
    }

    private static void trim(Context c, Uri tree) throws Exception {
        int keep = prefs(c).getInt("keep", 5); Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)); LinkedHashMap<String, ArrayList<Uri>> sets = new LinkedHashMap<>(); ArrayList<Uri> legacy = new ArrayList<>();
        Cursor q = c.getContentResolver().query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
        if (q != null) { while (q.moveToNext()) { String name = q.getString(1); if (!name.startsWith("librecontacts_")) continue; Uri file = DocumentsContract.buildDocumentUriUsingTree(tree, q.getString(0)); if (!(name.endsWith(".lcb") || name.endsWith(".lcb.enc"))) { legacy.add(file); continue; } String base = name; for (String suffix : new String[]{".enc", ".lcb"}) if (base.endsWith(suffix)) base = base.substring(0, base.length() - suffix.length()); sets.computeIfAbsent(base, k -> new ArrayList<>()).add(file); } q.close(); }
        for (Uri file : legacy) DocumentsContract.deleteDocument(c.getContentResolver(), file); ArrayList<String> names = new ArrayList<>(sets.keySet()); names.sort(Collections.reverseOrder()); for (int i = keep; i < names.size(); i++) for (Uri file : sets.get(names.get(i))) DocumentsContract.deleteDocument(c.getContentResolver(), file);
    }
    private static byte[] readAll(InputStream input) throws IOException { ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n; while ((n = input.read(buffer)) > 0) output.write(buffer, 0, n); return output.toByteArray(); }

    private static class Field { String value, type; Field(String v, String t) { value = v == null ? "" : v; type = t == null ? "" : t; } }
    private static class Address { String pobox, neighborhood, street, city, region, postcode, country, type; Address(String po, String nb, String s, String c, String r, String p, String co, String t) { pobox = po == null ? "" : po; neighborhood = nb == null ? "" : nb; street = s == null ? "" : s; city = c == null ? "" : c; region = r == null ? "" : r; postcode = p == null ? "" : p; country = co == null ? "" : co; type = t == null ? "" : t; } }
    private static class Person {
        String name; String organization = ""; String title = ""; String notes = ""; String photoType = "jpeg"; byte[] photo = null;
        ArrayList<Field> phones = new ArrayList<>(); ArrayList<Field> emails = new ArrayList<>();
        ArrayList<Address> addresses = new ArrayList<>(); ArrayList<String> nicknames = new ArrayList<>();
        ArrayList<Field> events = new ArrayList<>(); ArrayList<Field> websites = new ArrayList<>();
        ArrayList<Field> ims = new ArrayList<>(); ArrayList<Field> relations = new ArrayList<>();
        Person(String value) { name = value == null ? "Unnamed" : value; }
    }
}
