package com.ashkanrafiee.librecontactsbackup.export;

import java.util.Locale;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactsSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactSnapshot.DataRowSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactSnapshot.RawContactSnapshot;

/**
 * Exports the snapshot as a derived VCF (vCard 3.0) format for
 * interoperability, sharing, and external contact migration.
 *
 * This is NOT the canonical backup format. VCF cannot represent
 * every Android Contacts Provider field, but it covers the most
 * common contact data types.
 *
 * The canonical backup is android-contacts.json.
 */
public final class VCardExporter {

    private VCardExporter() {}

    public static String exportVcf(AndroidContactsSnapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        for (AndroidContactSnapshot contact : snapshot.contacts) {
            exportContact(sb, contact);
        }
        return sb.toString();
    }

    private static void exportContact(StringBuilder sb, AndroidContactSnapshot contact) {
        sb.append("BEGIN:VCARD\r\nVERSION:3.0\r\n");

        // A VCARD represents a single visible contact, so every RawContact's
        // rows are exported into one VCARD. Only the display name is
        // deduplicated (hasName below, first one wins) — every other field
        // is exported once per row, with no cross-RawContact deduplication.

        boolean hasName = false;
        for (RawContactSnapshot rc : contact.rawContacts) {
            for (DataRowSnapshot row : rc.dataRows) {
                String mime = row.mimeType;
                if (mime == null) continue;

                switch (mime) {
                    case "vnd.android.cursor.item/name":
                        if (!hasName) {
                            exportStructuredName(sb, row);
                            hasName = true;
                        }
                        break;
                    case "vnd.android.cursor.item/phone_v2":
                        exportPhone(sb, row);
                        break;
                    case "vnd.android.cursor.item/email_v2":
                        exportEmail(sb, row);
                        break;
                    case "vnd.android.cursor.item/postal-address_v2":
                    case "vnd.android.cursor.item/postal-address":
                        exportPostal(sb, row);
                        break;
                    case "vnd.android.cursor.item/organization":
                        exportOrganization(sb, row);
                        break;
                    case "vnd.android.cursor.item/nickname":
                        exportNickname(sb, row);
                        break;
                    case "vnd.android.cursor.item/note":
                        exportNote(sb, row);
                        break;
                    case "vnd.android.cursor.item/contact_event":
                        exportEvent(sb, row);
                        break;
                    case "vnd.android.cursor.item/website":
                        exportWebsite(sb, row);
                        break;
                    case "vnd.android.cursor.item/im":
                        exportIm(sb, row);
                        break;
                    case "vnd.android.cursor.item/relation":
                        exportRelation(sb, row);
                        break;
                    case "vnd.android.cursor.item/photo":
                        exportPhoto(sb, row);
                        break;
                    case "vnd.android.cursor.item/sip-address":
                        exportSip(sb, row);
                        break;
                    default:
                        exportRawAsExtension(sb, row);
                        break;
                }
            }
        }

        if (!hasName && contact.displayName != null && !contact.displayName.isEmpty()) {
            sb.append("FN:").append(escapeVcard(contact.displayName)).append("\r\n");
        }

        sb.append("END:VCARD\r\n");
    }

    private static void exportStructuredName(StringBuilder sb, DataRowSnapshot row) {
        // vCard N property (RFC 2426 §3.1.2): Family;Given;Additional(middle);
        // Prefix;Suffix — in that fixed order. Getting this wrong doesn't
        // just misplace an optional prefix/suffix: with prefix and suffix
        // both empty (the common case), a real vCard reader would parse
        // "N:;given;middle;family;" as family="" and prefix=family, losing
        // the actual family name into the honorific-prefix field entirely.
        String given = nvl(row.data2);
        String family = nvl(row.data3);
        String middle = nvl(row.data5);
        String prefix = nvl(row.data4);
        String suffix = nvl(row.data6);

        sb.append("N:");
        sb.append(escapeVcard(family)).append(";");
        sb.append(escapeVcard(given)).append(";");
        sb.append(escapeVcard(middle)).append(";");
        sb.append(escapeVcard(prefix)).append(";");
        sb.append(escapeVcard(suffix)).append("\r\n");

        // Also include FN if present
        if (row.data1 != null && !row.data1.isEmpty()) {
            sb.append("FN:").append(escapeVcard(row.data1)).append("\r\n");
        }

        // Phonetic names as X-properties
        if (row.data7 != null && !row.data7.isEmpty())
            sb.append("X-PHONETIC-GIVEN:").append(escapeVcard(row.data7)).append("\r\n");
        if (row.data8 != null && !row.data8.isEmpty())
            sb.append("X-PHONETIC-MIDDLE:").append(escapeVcard(row.data8)).append("\r\n");
        if (row.data9 != null && !row.data9.isEmpty())
            sb.append("X-PHONETIC-FAMILY:").append(escapeVcard(row.data9)).append("\r\n");
    }

    private static void exportPhone(StringBuilder sb, DataRowSnapshot row) {
        String value = row.data1;
        if (value == null || value.isEmpty()) return;
        sb.append("TEL").append(phoneTypeParam(row.data2, row.data3)).append(":");
        sb.append(escapeVcard(value)).append("\r\n");
    }

    private static void exportEmail(StringBuilder sb, DataRowSnapshot row) {
        String value = row.data1;
        if (value == null || value.isEmpty()) return;
        sb.append("EMAIL").append(commonTypeParam(row.data2, row.data3)).append(":");
        sb.append(escapeVcard(value)).append("\r\n");
    }

    private static void exportPostal(StringBuilder sb, DataRowSnapshot row) {
        // vCard ADR: PO;Ext;Street;City;Region;Zip;Country
        // Android postal data mapping (ContactsContract.CommonDataKinds.StructuredPostal):
        // data1 = formatted address, data2 = type, data3 = label
        // data4 = street, data5 = PO box, data6 = neighborhood
        // data7 = city, data8 = region, data9 = postcode, data10 = country
        sb.append("ADR").append(commonTypeParam(row.data2, row.data3)).append(":");
        sb.append(escapeVcard(nvl(row.data5))).append(";"); // PO box
        sb.append(escapeVcard(nvl(row.data6))).append(";"); // neighborhood (vCard "extended address")
        sb.append(escapeVcard(nvl(row.data4))).append(";"); // street
        sb.append(escapeVcard(nvl(row.data7))).append(";"); // city
        sb.append(escapeVcard(nvl(row.data8))).append(";"); // region
        sb.append(escapeVcard(nvl(row.data9))).append(";"); // postcode
        sb.append(escapeVcard(nvl(row.data10))).append("\r\n"); // country
    }

    private static void exportOrganization(StringBuilder sb, DataRowSnapshot row) {
        // data1 = company, data4 = title, data5 = department
        if (row.data1 != null && !row.data1.isEmpty()) {
            StringBuilder org = new StringBuilder(escapeVcard(row.data1));
            if (row.data5 != null && !row.data5.isEmpty()) {
                org.append(";").append(escapeVcard(row.data5));
            }
            sb.append("ORG:").append(org).append("\r\n");
        }
        if (row.data4 != null && !row.data4.isEmpty()) {
            sb.append("TITLE:").append(escapeVcard(row.data4)).append("\r\n");
        }
    }

    private static void exportNickname(StringBuilder sb, DataRowSnapshot row) {
        if (row.data1 == null || row.data1.isEmpty()) return;
        sb.append("NICKNAME:").append(escapeVcard(row.data1)).append("\r\n");
    }

    private static void exportNote(StringBuilder sb, DataRowSnapshot row) {
        if (row.data1 == null || row.data1.isEmpty()) return;
        sb.append("NOTE:").append(escapeVcard(row.data1)).append("\r\n");
    }

    private static void exportEvent(StringBuilder sb, DataRowSnapshot row) {
        if (row.data1 == null || row.data1.isEmpty()) return;
        String type = eventTypeLabel(row.data2, row.data3);
        if ("birthday".equals(type)) {
            sb.append("BDAY:").append(escapeVcard(row.data1)).append("\r\n");
        } else if ("anniversary".equals(type)) {
            sb.append("X-ANNIVERSARY:").append(escapeVcard(row.data1)).append("\r\n");
        } else {
            sb.append("X-EVENT").append(commonTypeParam(row.data2, row.data3)).append(":");
            sb.append(escapeVcard(row.data1)).append("\r\n");
        }
    }

    private static void exportWebsite(StringBuilder sb, DataRowSnapshot row) {
        if (row.data1 == null || row.data1.isEmpty()) return;
        sb.append("URL").append(websiteTypeParam(row.data2, row.data3)).append(":");
        sb.append(escapeVcard(row.data1)).append("\r\n");
    }

    private static void exportIm(StringBuilder sb, DataRowSnapshot row) {
        if (row.data1 == null || row.data1.isEmpty()) return;
        String protocol = imProtocolLabel(row.data5, row.data6);
        sb.append("IMPP").append(typeParam(protocol)).append(":");
        sb.append(escapeVcard(row.data1)).append("\r\n");
    }

    private static void exportRelation(StringBuilder sb, DataRowSnapshot row) {
        if (row.data1 == null || row.data1.isEmpty()) return;
        sb.append("X-RELATION").append(relationTypeParam(row.data2, row.data3)).append(":");
        sb.append(escapeVcard(row.data1)).append("\r\n");
    }

    private static void exportPhoto(StringBuilder sb, DataRowSnapshot row) {
        if (row.data15 == null || row.data15.length == 0) return;
        String type = "JPEG";
        if (row.data15.length >= 3) {
            if ((row.data15[0] & 0xFF) == 0x89 && (row.data15[1] & 0xFF) == 0x50 && (row.data15[2] & 0xFF) == 0x4E) {
                type = "PNG";
            }
        }
        sb.append("PHOTO;ENCODING=b;TYPE=");
        sb.append(type).append(":");
        sb.append(android.util.Base64.encodeToString(row.data15, android.util.Base64.NO_WRAP)).append("\r\n");
    }

    private static void exportSip(StringBuilder sb, DataRowSnapshot row) {
        if (row.data1 == null || row.data1.isEmpty()) return;
        sb.append("X-SIP").append(commonTypeParam(row.data2, row.data3)).append(":");
        sb.append(escapeVcard(row.data1)).append("\r\n");
    }

    /**
     * Export unknown MIME types as X-properties to preserve them in VCF.
     * This ensures unknown data is not silently discarded.
     */
    private static void exportRawAsExtension(StringBuilder sb, DataRowSnapshot row) {
        String safeMime = row.mimeType.replace("/", ".");
        sb.append("X-ANDROID-");
        sb.append(safeMime).append(":");
        // Serialize all data fields separated by semicolons
        StringBuilder val = new StringBuilder();
        for (int i = 1; i <= 14; i++) {
            String d = row.getData(i);
            if (d != null && !d.isEmpty()) {
                if (val.length() > 0) val.append(";");
                val.append(escapeVcard(d));
            }
        }
        sb.append(val).append("\r\n");
    }

    // Phone.TYPE_* (HOME=1, MOBILE=2, WORK=3, OTHER=7) — its own numbering,
    // distinct from the generic scheme most other kinds share below.
    private static String phoneTypeParam(String typeInt, String customLabel) {
        if (typeInt == null) return "";
        int t;
        try { t = Integer.parseInt(typeInt); } catch (Exception e) { return ";TYPE=other"; }
        switch (t) {
            case 1: return ";TYPE=HOME";
            case 2: return ";TYPE=CELL";
            case 3: return ";TYPE=WORK";
            case 7: return ";TYPE=OTHER";
            default:
                if (t == 0 && customLabel != null && !customLabel.isEmpty()) {
                    return ";TYPE=" + escapeVcard(customLabel.toUpperCase(Locale.ROOT));
                }
                return "";
        }
    }

    // The generic BaseTypes/CommonColumns scheme (HOME=1, WORK=2, OTHER=3)
    // shared by Email, StructuredPostal, Event (its non-birthday/anniversary
    // fallback), and SipAddress.
    private static String commonTypeParam(String typeInt, String customLabel) {
        if (typeInt == null) return "";
        int t;
        try { t = Integer.parseInt(typeInt); } catch (Exception e) { return ";TYPE=other"; }
        switch (t) {
            case 1: return ";TYPE=HOME";
            case 2: return ";TYPE=WORK";
            case 3: return ";TYPE=OTHER";
            default:
                if (t == 0 && customLabel != null && !customLabel.isEmpty()) {
                    return ";TYPE=" + escapeVcard(customLabel.toUpperCase(Locale.ROOT));
                }
                return "";
        }
    }

    // Website.TYPE_* (HOMEPAGE=1, BLOG=2, PROFILE=3, HOME=4, WORK=5, FTP=6,
    // OTHER=7) — a third, unrelated numbering.
    private static String websiteTypeParam(String typeInt, String customLabel) {
        if (typeInt == null) return "";
        int t;
        try { t = Integer.parseInt(typeInt); } catch (Exception e) { return ""; }
        switch (t) {
            case 1: return ";TYPE=HOMEPAGE";
            case 2: return ";TYPE=BLOG";
            case 3: return ";TYPE=PROFILE";
            case 4: return ";TYPE=HOME";
            case 5: return ";TYPE=WORK";
            case 6: return ";TYPE=FTP";
            case 7: return ";TYPE=OTHER";
            default:
                if (t == 0 && customLabel != null && !customLabel.isEmpty()) {
                    return ";TYPE=" + escapeVcard(customLabel.toUpperCase(Locale.ROOT));
                }
                return "";
        }
    }

    // Relation.TYPE_* identifies WHO the relation is (ASSISTANT=1,
    // BROTHER=2, CHILD=3, DOMESTIC_PARTNER=4, FATHER=5, FRIEND=6, MANAGER=7,
    // MOTHER=8, PARENT=9, PARTNER=10, REFERRED_BY=11, RELATIVE=12, SISTER=13,
    // SPOUSE=14) — not a HOME/WORK/OTHER classification at all, so it needs
    // its own semantic labels rather than reusing the generic TYPE param.
    private static String relationTypeParam(String typeInt, String customLabel) {
        if (typeInt == null) return "";
        int t;
        try { t = Integer.parseInt(typeInt); } catch (Exception e) { return ""; }
        switch (t) {
            case 1: return ";TYPE=ASSISTANT";
            case 2: return ";TYPE=BROTHER";
            case 3: return ";TYPE=CHILD";
            case 4: return ";TYPE=DOMESTIC_PARTNER";
            case 5: return ";TYPE=FATHER";
            case 6: return ";TYPE=FRIEND";
            case 7: return ";TYPE=MANAGER";
            case 8: return ";TYPE=MOTHER";
            case 9: return ";TYPE=PARENT";
            case 10: return ";TYPE=PARTNER";
            case 11: return ";TYPE=REFERRED_BY";
            case 12: return ";TYPE=RELATIVE";
            case 13: return ";TYPE=SISTER";
            case 14: return ";TYPE=SPOUSE";
            default:
                if (t == 0 && customLabel != null && !customLabel.isEmpty()) {
                    return ";TYPE=" + escapeVcard(customLabel.toUpperCase(Locale.ROOT));
                }
                return "";
        }
    }

    private static String typeParam(String value) {
        if (value == null || value.isEmpty()) return "";
        return ";TYPE=" + escapeVcard(value.toUpperCase(Locale.ROOT));
    }

    // Event.TYPE_* (ANNIVERSARY=1, OTHER=2, BIRTHDAY=3).
    private static String eventTypeLabel(String typeInt, String customLabel) {
        if (typeInt == null) return "other";
        int t;
        try { t = Integer.parseInt(typeInt); } catch (Exception e) { return "other"; }
        switch (t) {
            case 1: return "anniversary";
            case 3: return "birthday";
            default:
                if (customLabel != null && !customLabel.isEmpty()) return customLabel.toLowerCase(Locale.ROOT);
                return "other";
        }
    }

    // Im.PROTOCOL_* (AIM=0, MSN=1, YAHOO=2, SKYPE=3, QQ=4, GOOGLE_TALK=5,
    // ICQ=6, JABBER=7, NETMEETING=8).
    private static String imProtocolLabel(String protoInt, String customProto) {
        if (protoInt == null) return nvl(customProto);
        int t;
        try { t = Integer.parseInt(protoInt); } catch (Exception e) { return nvl(customProto); }
        switch (t) {
            case 0: return "aim"; case 1: return "msn"; case 2: return "yahoo";
            case 3: return "skype"; case 4: return "qq"; case 5: return "googletalk";
            case 6: return "icq"; case 7: return "jabber"; case 8: return "netmeeting";
            default: return customProto != null ? customProto : "";
        }
    }

    private static String escapeVcard(String s) {
        if (s == null) return "";
        // Order matters: backslash first so later-inserted backslashes
        // (from the \; \, \n escapes themselves) never get re-escaped.
        // \r isn't a real line break here (\r\n is only ever emitted as a
        // literal line terminator by this exporter, never as escaped
        // content) — a bare \r embedded in a field's own text would
        // otherwise pass through unescaped and could be read as a raw
        // line-ending byte by strict vCard parsers.
        return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
                .replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");
    }

    private static String nvl(String s) { return s != null ? s : ""; }
}
