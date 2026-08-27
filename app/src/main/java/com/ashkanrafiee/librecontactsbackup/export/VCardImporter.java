package com.ashkanrafiee.librecontactsbackup.export;

import java.util.Locale;
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

        // Split only at an actual card-delimiter line, not at "BEGIN:VCARD"
        // wherever it happens to occur as a substring — a NOTE or other
        // free-text field could otherwise contain that exact text and
        // fracture parsing mid-property. unfold() has already normalized
        // line endings to '\n', so an actual delimiter is always preceded
        // by a real line boundary.
        String[] cards = unfolded.split("(?m)^BEGIN:VCARD\\s*$");
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
        // Fallback name (given + family from N) used only if FN is absent —
        // set aside rather than written straight to the name row's data1,
        // since FN and N can appear in either order and FN must always win
        // when both are present (see the post-loop reconciliation below).
        String nameFallbackFromN = null;
        String lines[] = cardBody.split("\n");

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("END:VCARD") || trimmed.startsWith("VERSION:")) continue;

            String propName = extractPropertyName(trimmed);
            String[] propParams = extractPropertyParams(trimmed);
            String propValue = extractPropertyValue(trimmed);

            if (propName == null) continue;

            switch (propName) {
                case "FN":
                    displayName = unescapeVcard(propValue);
                    findOrCreateNameRow(rawContact); // ensure a name row exists even if N is absent
                    break;

                case "N": {
                    // vCard N property (RFC 2426 §3.1.2): Family;Given;
                    // Additional(middle);Prefix;Suffix — in that fixed order.
                    String[] parts = splitVcardFields(propValue);
                    String family = parts.length > 0 ? parts[0] : "";
                    String given = parts.length > 1 ? parts[1] : "";
                    String middle = parts.length > 2 ? parts[2] : "";
                    String prefix = parts.length > 3 ? parts[3] : "";
                    String suffix = parts.length > 4 ? parts[4] : "";
                    DataRowSnapshot row = findOrCreateNameRow(rawContact);
                    row.data2 = given;
                    row.data3 = family;
                    row.data4 = prefix;
                    row.data5 = middle;
                    row.data6 = suffix;
                    if (!given.isEmpty() || !family.isEmpty()) {
                        nameFallbackFromN = trim(given + " " + family);
                    }
                    break;
                }

                case "TEL": {
                    DataRowSnapshot row = new DataRowSnapshot("vnd.android.cursor.item/phone_v2");
                    row.data1 = unescapeVcard(propValue);
                    applyPhoneTypeParam(row, propParams);
                    if (row.data1 != null && !row.data1.isEmpty()) rawContact.addDataRow(row);
                    break;
                }

                case "EMAIL": {
                    DataRowSnapshot row = new DataRowSnapshot("vnd.android.cursor.item/email_v2");
                    row.data1 = unescapeVcard(propValue);
                    applyCommonTypeParam(row, propParams);
                    if (row.data1 != null && !row.data1.isEmpty()) rawContact.addDataRow(row);
                    break;
                }

                case "ADR": {
                    DataRowSnapshot row = new DataRowSnapshot("vnd.android.cursor.item/postal-address_v2");
                    String[] parts = splitVcardFields(propValue);
                    // vCard ADR: PO;Ext;Street;City;Region;Zip;Country
                    // Android postal data mapping (ContactsContract.CommonDataKinds.StructuredPostal):
                    // data4 = street, data5 = PO box, data6 = neighborhood
                    row.data5 = parts.length > 0 ? parts[0] : null; // PO box
                    row.data6 = parts.length > 1 ? parts[1] : null; // neighborhood (vCard "extended address")
                    row.data4 = parts.length > 2 ? parts[2] : null; // street
                    row.data7 = parts.length > 3 ? parts[3] : null; // city
                    row.data8 = parts.length > 4 ? parts[4] : null; // region
                    row.data9 = parts.length > 5 ? parts[5] : null; // postcode
                    row.data10 = parts.length > 6 ? parts[6] : null; // country
                    applyCommonTypeParam(row, propParams);
                    if (row.hasNonNullData()) rawContact.addDataRow(row);
                    break;
                }

                case "ORG": {
                    String[] parts = splitVcardFields(propValue);
                    DataRowSnapshot row = findOrCreateOrganizationRow(rawContact);
                    row.data1 = parts.length > 0 ? parts[0] : null; // company
                    row.data5 = parts.length > 1 ? parts[1] : null; // department
                    break;
                }

                case "TITLE": {
                    DataRowSnapshot row = findOrCreateOrganizationRow(rawContact);
                    row.data4 = unescapeVcard(propValue);
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
                    applyWebsiteTypeParam(row, propParams);
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
                    if (encoding != null && encoding.toUpperCase(Locale.ROOT).contains("B")) {
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
                    applyCommonTypeParam(row, propParams);
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

        if (rawContact.dataRows.isEmpty()) return null;

        // FN and N may appear in either order; whichever supplies the name
        // row's data1 is decided here, once, after seeing both — FN always
        // wins over N's synthesized fallback when both are present.
        for (DataRowSnapshot row : rawContact.dataRows) {
            if ("vnd.android.cursor.item/name".equals(row.mimeType)) {
                row.data1 = displayName != null ? displayName : nameFallbackFromN;
                break;
            }
        }

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

    private static DataRowSnapshot findOrCreateNameRow(RawContactSnapshot rawContact) {
        for (DataRowSnapshot row : rawContact.dataRows) {
            if ("vnd.android.cursor.item/name".equals(row.mimeType)) return row;
        }
        DataRowSnapshot row = new DataRowSnapshot("vnd.android.cursor.item/name");
        rawContact.addDataRow(row);
        return row;
    }

    private static DataRowSnapshot findOrCreateOrganizationRow(RawContactSnapshot rawContact) {
        for (DataRowSnapshot row : rawContact.dataRows) {
            if ("vnd.android.cursor.item/organization".equals(row.mimeType)) return row;
        }
        DataRowSnapshot row = new DataRowSnapshot("vnd.android.cursor.item/organization");
        rawContact.addDataRow(row);
        return row;
    }

    private static DataRowSnapshot newDataRowWithMime(String mime) {
        return new DataRowSnapshot(mime);
    }

    // Phone.TYPE_* (HOME=1, MOBILE=2, WORK=3, OTHER=7) — its own numbering,
    // matching phoneTypeParam on the export side.
    private static void applyPhoneTypeParam(DataRowSnapshot row, String[] params) {
        for (String param : params) {
            String upper = param.toUpperCase(Locale.ROOT);
            if (upper.startsWith("TYPE=")) {
                String type = upper.substring(5);
                switch (type) {
                    case "HOME": row.data2 = "1"; break;
                    case "CELL": case "MOBILE": row.data2 = "2"; break;
                    case "WORK": row.data2 = "3"; break;
                    case "OTHER": row.data2 = "7"; break;
                    case "FAX": row.data2 = "4"; break;
                    case "PAGER": row.data2 = "6"; break;
                    default:
                        row.data2 = "0";
                        row.data3 = type;
                        break;
                }
                return;
            }
        }
    }

    // The generic BaseTypes/CommonColumns scheme (HOME=1, WORK=2, OTHER=3)
    // shared by Email, StructuredPostal, and SipAddress.
    private static void applyCommonTypeParam(DataRowSnapshot row, String[] params) {
        for (String param : params) {
            String upper = param.toUpperCase(Locale.ROOT);
            if (upper.startsWith("TYPE=")) {
                String type = upper.substring(5);
                switch (type) {
                    case "HOME": row.data2 = "1"; break;
                    case "WORK": row.data2 = "2"; break;
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

    // Website.TYPE_* (HOMEPAGE=1, BLOG=2, PROFILE=3, HOME=4, WORK=5, FTP=6,
    // OTHER=7) — a third, unrelated numbering.
    private static void applyWebsiteTypeParam(DataRowSnapshot row, String[] params) {
        for (String param : params) {
            String upper = param.toUpperCase(Locale.ROOT);
            if (upper.startsWith("TYPE=")) {
                String type = upper.substring(5);
                switch (type) {
                    case "HOMEPAGE": row.data2 = "1"; break;
                    case "BLOG": row.data2 = "2"; break;
                    case "PROFILE": row.data2 = "3"; break;
                    case "HOME": row.data2 = "4"; break;
                    case "WORK": row.data2 = "5"; break;
                    case "FTP": row.data2 = "6"; break;
                    case "OTHER": row.data2 = "7"; break;
                    default:
                        row.data2 = "0";
                        row.data3 = type;
                        break;
                }
                return;
            }
        }
    }

    // Event.TYPE_* (ANNIVERSARY=1, OTHER=2, BIRTHDAY=3).
    private static void applyEventTypeParam(DataRowSnapshot row, String[] params) {
        for (String param : params) {
            String upper = param.toUpperCase(Locale.ROOT);
            if (upper.startsWith("TYPE=")) {
                String type = upper.substring(5);
                switch (type) {
                    case "ANNIVERSARY": row.data2 = "1"; break;
                    case "OTHER": row.data2 = "2"; break;
                    case "BIRTHDAY": row.data2 = "3"; break;
                    default:
                        row.data2 = "0";
                        row.data3 = type;
                        break;
                }
                return;
            }
        }
    }

    // Im.PROTOCOL_* (AIM=0, MSN=1, YAHOO=2, SKYPE=3, QQ=4, GOOGLE_TALK=5,
    // ICQ=6, JABBER=7, NETMEETING=8) — matches imProtocolLabel's export-side
    // vocabulary (GOOGLETALK, ICQ, JABBER, NETMEETING; no "IRC", never a
    // real protocol constant here).
    private static void applyImProtocolParam(DataRowSnapshot row, String[] params) {
        for (String param : params) {
            String upper = param.toUpperCase(Locale.ROOT);
            if (upper.startsWith("X-SERVICE-TYPE=")) {
                String proto = upper.substring(15);
                switch (proto) {
                    case "AIM": row.data5 = "0"; break;
                    case "MSN": row.data5 = "1"; break;
                    case "YAHOO": row.data5 = "2"; break;
                    case "SKYPE": row.data5 = "3"; break;
                    case "QQ": row.data5 = "4"; break;
                    case "GOOGLETALK": row.data5 = "5"; break;
                    case "ICQ": row.data5 = "6"; break;
                    case "JABBER": row.data5 = "7"; break;
                    case "NETMEETING": row.data5 = "8"; break;
                    default:
                        row.data5 = "0";
                        row.data6 = proto;
                        break;
                }
                return;
            }
        }
    }

    // Relation.TYPE_* (ASSISTANT=1, BROTHER=2, CHILD=3, DOMESTIC_PARTNER=4,
    // FATHER=5, FRIEND=6, MANAGER=7, MOTHER=8, PARENT=9, PARTNER=10,
    // REFERRED_BY=11, RELATIVE=12, SISTER=13, SPOUSE=14) — matches
    // relationTypeParam's export-side vocabulary.
    private static void applyRelationTypeParam(DataRowSnapshot row, String[] params) {
        for (String param : params) {
            String upper = param.toUpperCase(Locale.ROOT);
            if (upper.startsWith("TYPE=")) {
                String type = upper.substring(5);
                switch (type) {
                    case "ASSISTANT": row.data2 = "1"; break;
                    case "BROTHER": row.data2 = "2"; break;
                    case "CHILD": row.data2 = "3"; break;
                    case "DOMESTIC_PARTNER": row.data2 = "4"; break;
                    case "FATHER": row.data2 = "5"; break;
                    case "FRIEND": row.data2 = "6"; break;
                    case "MANAGER": row.data2 = "7"; break;
                    case "MOTHER": row.data2 = "8"; break;
                    case "PARENT": row.data2 = "9"; break;
                    case "PARTNER": row.data2 = "10"; break;
                    case "REFERRED_BY": row.data2 = "11"; break;
                    case "RELATIVE": row.data2 = "12"; break;
                    case "SISTER": row.data2 = "13"; break;
                    case "SPOUSE": row.data2 = "14"; break;
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
            if (param.toUpperCase(Locale.ROOT).startsWith(name.toUpperCase(Locale.ROOT) + "=")) {
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

    /**
     * Splits a compound vCard value (N, ADR, ORG) on its real field
     * separators only — a semicolon preceded by a backslash is an escaped
     * literal, not a boundary. Splitting the already-unescaped string (the
     * previous approach) can't tell the two apart, since unescaping removes
     * the backslash before the split ever runs; splitting the raw value
     * first and unescaping each resulting field afterward preserves the
     * distinction.
     */
    private static String[] splitVcardFields(String rawValue) {
        if (rawValue == null) return new String[0];
        String[] parts = rawValue.split("(?<!\\\\);", -1);
        for (int i = 0; i < parts.length; i++) parts[i] = unescapeVcard(parts[i]);
        return parts;
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
