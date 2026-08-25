package com.ashkanrafiee.librecontactsbackup.export;

import android.util.Base64;

import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactsSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactSnapshot.DataRowSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactSnapshot.RawContactSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Imports a VCF (vCard 3.0) file into a lossless snapshot.
 *
 * Recognized vCard properties are converted into the appropriate
 * Android Data row MIME types and columns.
 *
 * Unknown vCard properties/extensions are preserved as X-ANDROID-*
 * extension properties to prevent silent data loss during round trips.
 *
 * This importer converts VCF into the snapshot representation,
 * not directly into Android contacts. The snapshot can then be
 * used for both canonical backup and restore.
 */
public final class VCardImporter {

    private VCardImporter() {}

    /**
     * Imports a VCF string into an AndroidContactsSnapshot.
     * Each VCARD becomes one RawContact.
     */
    public static AndroidContactsSnapshot importVcf(String vcfContent) {
        AndroidContactsSnapshot snapshot = new AndroidContactsSnapshot();

        // Unfold VCF lines (continuation lines start with space/tab)
        String unfolded = unfold(vcfContent);

        String[] cards = unfolded.split("BEGIN:VCARD");
        for (String card : cards) {
            if (card.trim().isEmpty()) continue;
            AndroidContactSnapshot contact = parseVcard(card);
            if (contact != null) {
                snapshot.addContact(contact);
            }
        }

        return snapshot;
    }

    private static AndroidContactSnapshot parseVcard(String cardBody) {
        AndroidContactSnapshot contact = new AndroidContactSnapshot();
        RawContactSnapshot rawContact = new RawContactSnapshot();
        contact.addRawContact(rawContact);

        String displayName = null;
        String lines[] = cardBody.split("\n");

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("END:VCARD") || trimmed.startsWith("VERSION:")) continue;

            String upper = trimmed.toUpperCase();
            String propName = extractPropertyName(trimmed);
            String[] propParams = extractPropertyParams(trimmed);
            String propValue = extractPropertyValue(trimmed);

            if (propName == null) continue;

            switch (propName) {
                case "FN":
                    displayName = unescapeVcard(propValue);
                    addNameRow(rawContact, displayName, null, null, null, null, null);
                    break;

                case "N": {
                    String[] parts = unescapeVcard(propValue).split(";", -1);
                    String prefix = parts.length > 0 ? parts[0] : "";
                    String given = parts.length > 1 ? parts[1] : "";
                    String middle = parts.length > 2 ? parts[2] : "";
                    String family = parts.length > 3 ? parts[3] : "";
                    String suffix = parts.length > 4 ? parts[4] : "";
                    String fn = given.isEmpty() && family.isEmpty() ? displayName : trim(given + " " + family);
                    addNameRow(rawContact, fn, given, family, prefix, suffix, middle);
                    break;
                }

                case "TEL": {
                    DataRowSnapshot row = new DataRowSnapshot("vnd.android.cursor.item/phone_v2");
                    row.data1 = unescapeVcard(propValue);
                    applyTypeParam(row, propParams);
                    if (row.data1 != null && !row.data1.isEmpty()) rawContact.addDataRow(row);
                    break;
                }

                case "EMAIL": {
                    DataRowSnapshot row = new DataRowSnapshot("vnd.android.cursor.item/email_v2");
                    row.data1 = unescapeVcard(propValue);
                    applyTypeParam(row, propParams);
                    if (row.data1 != null && !row.data1.isEmpty()) rawContact.addDataRow(row);
                    break;
                }

                case "ADR": {
                    DataRowSnapshot row = new DataRowSnapshot("vnd.android.cursor.item/postal-address_v2");
                    String[] parts = unescapeVcard(propValue).split(";", -1);
                    // vCard ADR: PO;Ext;Street;City;Region;Zip;Country
                    row.data4 = parts.length > 0 ? parts[0] : null; // PO box
                    row.data5 = parts.length > 1 ? parts[1] : null; // neighborhood
                    row.data6 = parts.length > 2 ? parts[2] : null; // street
                    row.data7 = parts.length > 3 ? parts[3] : null; // city
                    row.data8 = parts.length > 4 ? parts[4] : null; // region
                    row.data9 = parts.length > 5 ? parts[5] : null; // postcode
                    row.data10 = parts.length > 6 ? parts[6] : null; // country
                    applyTypeParam(row, propParams);
                    if (row.hasNonNullData()) rawContact.addDataRow(row);
                    break;
                }

                case "ORG": {
                    DataRowSnapshot row = new DataRowSnapshot("vnd.android.cursor.item/organization");
                    String[] parts = unescapeVcard(propValue).split(";", -1);
                    row.data1 = parts.length > 0 ? parts[0] : null; // company
                    row.data5 = parts.length > 1 ? parts[1] : null; // department
                    if (row.data1 != null && !row.data1.isEmpty()) rawContact.addDataRow(row);
                    break;
                }

                case "TITLE": {
                    DataRowSnapshot row = new DataRowSnapshot("vnd.android.cursor.item/organization");
                    row.data4 = unescapeVcard(propValue);
                    if (row.data4 != null && !row.data4.isEmpty()) rawContact.addDataRow(row);
                    break;
                }

                case "NICKNAME": {
                    DataRowSnapshot row = new DataRowSnapshot("vnd.android.cursor.item/nickname");
                    row.data1 = unescapeVcard(propValue);
                    if (row.data1 != null && !row.data1.isEmpty()) rawContact.addDataRow(row);
                    break;
                }

                case "X-PHONETIC-GIVEN": {
                    DataRowSnapshot row = findOrCreateNameRow(rawContact);
                    row.data7 = unescapeVcard(propValue);
                    break;
                }

                case "X-PHONETIC-MIDDLE": {
                    DataRowSnapshot row = findOrCreateNameRow(rawContact);
                    row.data8 = unescapeVcard(propValue);
                    break;
                }

                case "X-PHONETIC-FAMILY": {
                    DataRowSnapshot row = findOrCreateNameRow(rawContact);
                    row.data9 = unescapeVcard(propValue);
                    break;
                }

                case "NOTE": {
                    DataRowSnapshot row = new DataRowSnapshot("vnd.android.cursor.item/note");
                    row.data1 = unescapeVcard(propValue);
                    if (row.data1 != null && !row.data1.isEmpty()) rawContact.addDataRow(row);
                    break;
                }

                case "BDAY": {
                    DataRowSnapshot row = new DataRowSnapshot("vnd.android.cursor.item/contact_event");
                    row.data1 = unescapeVcard(propValue);
                    row.data2 = "1"; // birthday type
                    if (row.data1 != null && !row.data1.isEmpty()) rawContact.addDataRow(row);
                    break;
                }

                case "X-ANNIVERSARY": {
                    DataRowSnapshot row = new DataRowSnapshot("vnd.android.cursor.item/contact_event");
                    row.data1 = unescapeVcard(propValue);
                    row.data2 = "2"; // anniversary type
                    if (row.data1 != null && !row.data1.isEmpty()) rawContact.addDataRow(row);
                    break;
                }

                case "X-EVENT": {
                    DataRowSnapshot row = new DataRowSnapshot("vnd.android.cursor.item/contact_event");
                    row.data1 = unescapeVcard(propValue);
                    applyEventTypeParam(row, propParams);
                    if (row.data1 != null && !row.data1.isEmpty()) rawContact.addDataRow(row);
                    break;
                }

                case "URL": {
                    DataRowSnapshot row = new DataRowSnapshot("vnd.android.cursor.item/website");
                    row.data1 = unescapeVcard(propValue);
                    applyTypeParam(row, propParams);
                    if (row.data1 != null && !row.data1.isEmpty()) rawContact.addDataRow(row);
                    break;
                }

                case "IMPP": {
                    DataRowSnapshot row = newDataRowWithMime("vnd.android.cursor.item/im");
                    row.data1 = unescapeVcard(propValue);
                    applyImProtocolParam(row, propParams);
                    if (row.data1 != null && !row.data1.isEmpty()) rawContact.addDataRow(row);
                    break;
                }

                case "X-RELATION": {
                    DataRowSnapshot row = newDataRowWithMime("vnd.android.cursor.item/relation");
                    row.data1 = unescapeVcard(propValue);
                    applyRelationTypeParam(row, propParams);
                    if (row.data1 != null && !row.data1.isEmpty()) rawContact.addDataRow(row);
                    break;
                }

                case "PHOTO": {
                    String encoding = getParamValue(propParams, "ENCODING");
                    if (encoding != null && encoding.toUpperCase().contains("B")) {
                        DataRowSnapshot row = new DataRowSnapshot("vnd.android.cursor.item/photo");
                        try {
                            row.data15 = Base64.decode(propValue.trim(), Base64.NO_WRAP);
                            rawContact.addDataRow(row);
                        } catch (Exception ignored) {}
                    }
                    break;
                }

                case "X-SIP": {
                    DataRowSnapshot row = newDataRowWithMime("vnd.android.cursor.item/sip-address");
                    row.data1 = unescapeVcard(propValue);
                    applyTypeParam(row, propParams);
                    if (row.data1 != null && !row.data1.isEmpty()) rawContact.addDataRow(row);
                    break;
                }

                default: {
                    // Preserve unknown properties as X-ANDROID-* extensions
                    if (propName.startsWith("X-")) {
                        String mime = propName.substring(2).replace(".", "/");
                        DataRowSnapshot row = new DataRowSnapshot(mime);
                        row.data1 = unescapeVcard(propValue);
                        if (row.data1 != null && !row.data1.isEmpty()) rawContact.addDataRow(row);
                    }
                    break;
                }
            }
        }

        if (contact.rawContacts.isEmpty() || rawContact.dataRows.isEmpty()) return null;

        // Set display name from the contact snapshot level
        if (displayName != null) {
            contact.displayName = displayName;
        } else {
            // Try to get display name from the name row
            for (DataRowSnapshot row : rawContact.dataRows) {
                if ("vnd.android.cursor.item/name".equals(row.mimeType) && row.data1 != null) {
                    contact.displayName = row.data1;
                    break;
                }
            }
        }

        return contact;
    }

    private static void addNameRow(RawContactSnapshot rawContact, String displayName,
                                    String given, String family, String prefix, String suffix, String middle) {
        DataRowSnapshot row = new DataRowSnapshot("vnd.android.cursor.item/name");
        row.data1 = displayName;
        row.data2 = given;
        row.data3 = family;
        row.data4 = prefix;
        row.data5 = middle;
        row.data6 = suffix;
        rawContact.addDataRow(row);
    }

    private static DataRowSnapshot findOrCreateNameRow(RawContactSnapshot rawContact) {
        for (DataRowSnapshot row : rawContact.dataRows) {
            if ("vnd.android.cursor.item/name".equals(row.mimeType)) return row;
        }
        DataRowSnapshot row = new DataRowSnapshot("vnd.android.cursor.item/name");
        rawContact.addDataRow(row);
        return row;
    }

    private static DataRowSnapshot newDataRowWithMime(String mime) {
        return new DataRowSnapshot(mime);
    }

    private static void applyTypeParam(DataRowSnapshot row, String[] params) {
        for (String param : params) {
            String upper = param.toUpperCase();
            if (upper.startsWith("TYPE=")) {
                String type = upper.substring(5);
                switch (type) {
                    case "HOME": row.data2 = "1"; break;
                    case "WORK": row.data2 = "2"; break;
                    case "OTHER": row.data2 = "3"; break;
                    case "MOBILE": row.data2 = "-1"; break;
                    case "FAX": row.data2 = "4"; break;
                    case "PAGER": row.data2 = "5"; break;
                    default:
                        row.data2 = "0";
                        row.data3 = type;
                        break;
                }
                return;
            }
        }
    }

    private static void applyEventTypeParam(DataRowSnapshot row, String[] params) {
        for (String param : params) {
            String upper = param.toUpperCase();
            if (upper.startsWith("TYPE=")) {
                String type = upper.substring(5);
                switch (type) {
                    case "BIRTHDAY": row.data2 = "1"; break;
                    case "ANNIVERSARY": row.data2 = "2"; break;
                    case "OTHER": row.data2 = "3"; break;
                    default:
                        row.data2 = "0";
                        row.data3 = type;
                        break;
                }
                return;
            }
        }
    }

    private static void applyImProtocolParam(DataRowSnapshot row, String[] params) {
        for (String param : params) {
            String upper = param.toUpperCase();
            if (upper.startsWith("X-SERVICE-TYPE=")) {
                String proto = upper.substring(15);
                switch (proto) {
                    case "AIM": row.data5 = "0"; break;
                    case "MSN": row.data5 = "1"; break;
                    case "YAHOO": row.data5 = "2"; break;
                    case "SKYPE": row.data5 = "3"; break;
                    case "QQ": row.data5 = "4"; break;
                    case "ICQ": row.data5 = "5"; break;
                    case "JABBER": row.data5 = "6"; break;
                    case "IRC": row.data5 = "7"; break;
                    default:
                        row.data5 = "0";
                        row.data6 = proto;
                        break;
                }
                return;
            }
        }
    }

    private static void applyRelationTypeParam(DataRowSnapshot row, String[] params) {
        for (String param : params) {
            String upper = param.toUpperCase();
            if (upper.startsWith("TYPE=")) {
                String type = upper.substring(5);
                switch (type) {
                    case "ASSISTANT": row.data2 = "1"; break;
                    case "BROTHER": row.data2 = "2"; break;
                    case "CHILD": row.data2 = "3"; break;
                    case "PARTNER": row.data2 = "4"; break;
                    case "FATHER": row.data2 = "5"; break;
                    case "FRIEND": row.data2 = "6"; break;
                    case "MANAGER": row.data2 = "7"; break;
                    case "MOTHER": row.data2 = "8"; break;
                    case "PARENT": row.data2 = "9"; break;
                    case "DOMESTIC PARTNER": row.data2 = "10"; break;
                    case "SISTER": row.data2 = "11"; break;
                    case "SPOUSE": row.data2 = "12"; break;
                    case "RELATIVE": row.data2 = "13"; break;
                    default:
                        row.data2 = "0";
                        row.data3 = type;
                        break;
                }
                return;
            }
        }
    }

    private static String getParamValue(String[] params, String name) {
        for (String param : params) {
            if (param.toUpperCase().startsWith(name.toUpperCase() + "=")) {
                return param.substring(name.length() + 1);
            }
        }
        return null;
    }

    /**
     * Extracts the property name from a vCard line.
     * E.g., "TEL;TYPE=HOME:+1234" -> "TEL"
     */
    private static String extractPropertyName(String line) {
        int colon = line.indexOf(':');
        if (colon < 0) return null;
        String before = line.substring(0, colon);
        int semi = before.indexOf(';');
        if (semi < 0) return before.trim();
        return before.substring(0, semi).trim();
    }

    /**
     * Extracts parameters from a vCard line.
     * E.g., "TEL;TYPE=HOME;PREF:+1234" -> ["TYPE=HOME", "PREF"]
     */
    private static String[] extractPropertyParams(String line) {
        int colon = line.indexOf(':');
        if (colon < 0) return new String[0];
        String before = line.substring(0, colon);
        int semi = before.indexOf(';');
        if (semi < 0) return new String[0];
        String paramStr = before.substring(semi + 1);
        String[] parts = paramStr.split(";");
        ArrayList<String> result = new ArrayList<>();
        for (String p : parts) {
            if (!p.trim().isEmpty()) result.add(p.trim());
        }
        return result.toArray(new String[0]);
    }

    /**
     * Extracts the property value from a vCard line.
     * E.g., "TEL;TYPE=HOME:+1234" -> "+1234"
     */
    private static String extractPropertyValue(String line) {
        int colon = line.indexOf(':');
        if (colon < 0) return "";
        return line.substring(colon + 1);
    }

    /**
     * Unfolds VCF lines: lines starting with space/tab are continuations
     * of the previous line.
     */
    private static String unfold(String vcf) {
        StringBuilder sb = new StringBuilder();
        for (String rawLine : vcf.split("\r?\n")) {
            String line = rawLine.replaceAll("\\s+$", "");
            if (sb.length() > 0 && !line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t')) {
                sb.setLength(sb.length() - 1); // Remove trailing \n
                sb.append(line.substring(1));
            } else {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static String unescapeVcard(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case 'n': case 'N': sb.append('\n'); break;
                    case ',': sb.append(','); break;
                    case ';': sb.append(';'); break;
                    case '\\': sb.append('\\'); break;
                    default: sb.append('\\').append(next); break;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
