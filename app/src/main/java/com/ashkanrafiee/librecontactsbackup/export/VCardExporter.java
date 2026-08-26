package com.ashkanrafiee.librecontactsbackup.export;

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

        // Export from all raw contacts, but deduplicate at the VCARD level
        // A VCARD represents a single visible contact, so we merge raw contacts
        // into one VCARD

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
        // vCard N property: prefix;given;middle;family;suffix
        String given = nvl(row.data2);
        String family = nvl(row.data3);
        String middle = nvl(row.data5);
        String prefix = nvl(row.data4);
        String suffix = nvl(row.data6);

        sb.append("N:");
        sb.append(escapeVcard(prefix)).append(";");
        sb.append(escapeVcard(given)).append(";");
        sb.append(escapeVcard(middle)).append(";");
        sb.append(escapeVcard(family)).append(";");
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
        sb.append("TEL").append(typeParam(row.data2, row.data3)).append(":");
        sb.append(escapeVcard(value)).append("\r\n");
    }

    private static void exportEmail(StringBuilder sb, DataRowSnapshot row) {
        String value = row.data1;
        if (value == null || value.isEmpty()) return;
        sb.append("EMAIL").append(typeParam(row.data2, row.data3)).append(":");
        sb.append(escapeVcard(value)).append("\r\n");
    }

    private static void exportPostal(StringBuilder sb, DataRowSnapshot row) {
        // vCard ADR: PO;Ext;Street;City;Region;Zip;Country
        // Android postal data mapping (ContactsContract.CommonDataKinds.StructuredPostal):
        // data1 = formatted address, data2 = type, data3 = label
        // data4 = street, data5 = PO box, data6 = neighborhood
        // data7 = city, data8 = region, data9 = postcode, data10 = country
        sb.append("ADR").append(typeParam(row.data2, row.data3)).append(":");
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
            sb.append("X-EVENT").append(typeParam(row.data2, row.data3)).append(":");
            sb.append(escapeVcard(row.data1)).append("\r\n");
        }
    }

    private static void exportWebsite(StringBuilder sb, DataRowSnapshot row) {
        if (row.data1 == null || row.data1.isEmpty()) return;
        sb.append("URL").append(typeParam(row.data2, row.data3)).append(":");
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
        sb.append("X-RELATION").append(typeParam(row.data2, row.data3)).append(":");
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
        sb.append("X-SIP").append(typeParam(row.data2, row.data3)).append(":");
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

    private static String typeParam(String typeInt, String customLabel) {
        if (typeInt == null) return "";
        int t;
        try { t = Integer.parseInt(typeInt); } catch (Exception e) { return ";TYPE=other"; }
        switch (t) {
            case 1: return ";TYPE=HOME";
            case 2: return ";TYPE=WORK";
            case 3: return ";TYPE=OTHER";
            case -1: return ";TYPE=MOBILE";
            default:
                if (t == 0 && customLabel != null && !customLabel.isEmpty()) {
                    return ";TYPE=" + escapeVcard(customLabel.toUpperCase());
                }
                return "";
        }
    }

    private static String typeParam(String value) {
        if (value == null || value.isEmpty()) return "";
        return ";TYPE=" + escapeVcard(value.toUpperCase());
    }

    private static String eventTypeLabel(String typeInt, String customLabel) {
        if (typeInt == null) return "other";
        int t;
        try { t = Integer.parseInt(typeInt); } catch (Exception e) { return "other"; }
        switch (t) {
            case 1: return "birthday";
            case 2: return "anniversary";
            default:
                if (customLabel != null && !customLabel.isEmpty()) return customLabel.toLowerCase();
                return "other";
        }
    }

    private static String imProtocolLabel(String protoInt, String customProto) {
        if (protoInt == null) return nvl(customProto);
        int t;
        try { t = Integer.parseInt(protoInt); } catch (Exception e) { return nvl(customProto); }
        switch (t) {
            case 0: return "aim"; case 1: return "msn"; case 2: return "yahoo";
            case 3: return "skype"; case 4: return "qq"; case 5: return "icq";
            case 6: return "jabber"; case 7: return "irc";
            default: return customProto != null ? customProto : "";
        }
    }

    private static String escapeVcard(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n");
    }

    private static String nvl(String s) { return s != null ? s : ""; }
}
