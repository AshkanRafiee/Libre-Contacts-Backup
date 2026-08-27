package com.ashkanrafiee.librecontactsbackup.export;

import java.util.Locale;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactsSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactSnapshot.DataRowSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactSnapshot.RawContactSnapshot;

/**
 * Exports contacts as a derived CSV format for human readability.
 *
 * This is NOT the canonical backup format. CSV cannot represent
 * the full hierarchy or binary data.
 *
 * The canonical backup is android-contacts.json.
 */
public final class NormalizedCsvExporter {

    private static final String MIME_NAME = "vnd.android.cursor.item/name";
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

    private NormalizedCsvExporter() {}

    public static String exportCsv(AndroidContactsSnapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append("name,phone,email,address,organization,title,nickname,notes,events,websites,ims,relations\n");

        for (AndroidContactSnapshot contact : snapshot.contacts) {
            String name = "";
            StringBuilder phones = new StringBuilder();
            StringBuilder emails = new StringBuilder();
            StringBuilder addresses = new StringBuilder();
            String org = "";
            String title = "";
            StringBuilder nicknames = new StringBuilder();
            StringBuilder notes = new StringBuilder();
            StringBuilder events = new StringBuilder();
            StringBuilder websites = new StringBuilder();
            StringBuilder ims = new StringBuilder();
            StringBuilder relations = new StringBuilder();

            for (RawContactSnapshot rc : contact.rawContacts) {
                for (DataRowSnapshot row : rc.dataRows) {
                    String mime = row.mimeType;
                    if (mime == null) continue;

                    switch (mime) {
                        case MIME_NAME:
                            if (name.isEmpty() && row.data1 != null) name = row.data1;
                            break;
                        case MIME_PHONE:
                            if (row.data1 != null && !row.data1.isEmpty()) {
                                if (phones.length() > 0) phones.append("; ");
                                String type = phoneTypeLabel(row.data2, row.data3);
                                if (!type.isEmpty()) phones.append(type).append(":");
                                phones.append(row.data1);
                            }
                            break;
                        case MIME_EMAIL:
                            if (row.data1 != null && !row.data1.isEmpty()) {
                                if (emails.length() > 0) emails.append("; ");
                                String type = commonTypeLabel(row.data2, row.data3);
                                if (!type.isEmpty()) emails.append(type).append(":");
                                emails.append(row.data1);
                            }
                            break;
                        case MIME_POSTAL:
                        case MIME_POSTAL_LEGACY:
                            if (hasPostalData(row)) {
                                if (addresses.length() > 0) addresses.append("; ");
                                String type = commonTypeLabel(row.data2, row.data3);
                                if (!type.isEmpty()) addresses.append(type).append(":");
                                if (row.data4 != null && !row.data4.isEmpty()) addresses.append(row.data4).append(", ");
                                if (row.data6 != null && !row.data6.isEmpty()) addresses.append(row.data6).append(", ");
                                if (row.data5 != null && !row.data5.isEmpty()) addresses.append(row.data5).append(", ");
                                addresses.append(nvl(row.data7)).append(", ");
                                addresses.append(nvl(row.data8)).append(" ");
                                addresses.append(nvl(row.data9)).append(", ");
                                addresses.append(nvl(row.data10));
                            }
                            break;
                        case MIME_ORG:
                            if (org.isEmpty() && row.data1 != null) org = row.data1;
                            if (title.isEmpty() && row.data4 != null) title = row.data4;
                            break;
                        case MIME_NICK:
                            if (row.data1 != null && !row.data1.isEmpty()) {
                                if (nicknames.length() > 0) nicknames.append("; ");
                                nicknames.append(row.data1);
                            }
                            break;
                        case MIME_NOTE:
                            if (row.data1 != null && !row.data1.isEmpty()) {
                                if (notes.length() > 0) notes.append("; ");
                                notes.append(row.data1);
                            }
                            break;
                        case MIME_EVENT:
                            if (row.data1 != null && !row.data1.isEmpty()) {
                                if (events.length() > 0) events.append("; ");
                                String type = eventTypeLabel(row.data2, row.data3);
                                events.append(type).append(":").append(row.data1);
                            }
                            break;
                        case MIME_WEB:
                            if (row.data1 != null && !row.data1.isEmpty()) {
                                if (websites.length() > 0) websites.append("; ");
                                websites.append(row.data1);
                            }
                            break;
                        case MIME_IM:
                            if (row.data1 != null && !row.data1.isEmpty()) {
                                if (ims.length() > 0) ims.append("; ");
                                String proto = imProtocolLabel(row.data5, row.data6);
                                if (!proto.isEmpty()) ims.append(proto).append(":");
                                ims.append(row.data1);
                            }
                            break;
                        case MIME_REL:
                            if (row.data1 != null && !row.data1.isEmpty()) {
                                if (relations.length() > 0) relations.append("; ");
                                String type = relationTypeLabel(row.data2, row.data3);
                                if (!type.isEmpty()) relations.append(type).append(":");
                                relations.append(row.data1);
                            }
                            break;
                    }
                }
            }

            sb.append('"').append(csvEsc(name)).append("\",\"");
            sb.append(csvEsc(phones.toString())).append("\",\"");
            sb.append(csvEsc(emails.toString())).append("\",\"");
            sb.append(csvEsc(addresses.toString())).append("\",\"");
            sb.append(csvEsc(org)).append("\",\"");
            sb.append(csvEsc(title)).append("\",\"");
            sb.append(csvEsc(nicknames.toString())).append("\",\"");
            sb.append(csvEsc(notes.toString())).append("\",\"");
            sb.append(csvEsc(events.toString())).append("\",\"");
            sb.append(csvEsc(websites.toString())).append("\",\"");
            sb.append(csvEsc(ims.toString())).append("\",\"");
            sb.append(csvEsc(relations.toString())).append("\"\n");
        }
        return sb.toString();
    }

    private static boolean hasPostalData(DataRowSnapshot row) {
        return (row.data4 != null && !row.data4.isEmpty())
                || (row.data5 != null && !row.data5.isEmpty())
                || (row.data6 != null && !row.data6.isEmpty())
                || (row.data7 != null && !row.data7.isEmpty())
                || (row.data8 != null && !row.data8.isEmpty())
                || (row.data9 != null && !row.data9.isEmpty())
                || (row.data10 != null && !row.data10.isEmpty());
    }

    // Phone.TYPE_* numbering (HOME=1, MOBILE=2, WORK=3, OTHER=7) is its own
    // scheme, distinct from the generic BaseTypes/CommonColumns scheme most
    // other kinds (Email, StructuredPostal) share — see commonTypeLabel.
    private static String phoneTypeLabel(String typeInt, String customLabel) {
        if (typeInt == null) return "";
        int t;
        try { t = Integer.parseInt(typeInt); } catch (Exception e) { return ""; }
        switch (t) {
            case 1: return "home"; case 2: return "mobile"; case 3: return "work"; case 7: return "other";
            default: return (t == 0 && customLabel != null && !customLabel.isEmpty()) ? customLabel.toLowerCase(Locale.ROOT) : "";
        }
    }

    // The generic BaseTypes/CommonColumns scheme (HOME=1, WORK=2, OTHER=3)
    // used by Email and StructuredPostal — not the same numbering as Phone.
    private static String commonTypeLabel(String typeInt, String customLabel) {
        if (typeInt == null) return "";
        int t;
        try { t = Integer.parseInt(typeInt); } catch (Exception e) { return ""; }
        switch (t) {
            case 1: return "home"; case 2: return "work"; case 3: return "other";
            default: return (t == 0 && customLabel != null && !customLabel.isEmpty()) ? customLabel.toLowerCase(Locale.ROOT) : "";
        }
    }

    private static String eventTypeLabel(String typeInt, String customLabel) {
        if (typeInt == null) return "other";
        int t;
        try { t = Integer.parseInt(typeInt); } catch (Exception e) { return "other"; }
        switch (t) { case 1: return "anniversary"; case 3: return "birthday"; default: return "other"; }
    }

    // Im.PROTOCOL_* (AIM=0, MSN=1, YAHOO=2, SKYPE=3, QQ=4, GOOGLE_TALK=5,
    // ICQ=6, JABBER=7, NETMEETING=8).
    private static String imProtocolLabel(String protoInt, String customProto) {
        if (protoInt == null) return nvl(customProto);
        int t;
        try { t = Integer.parseInt(protoInt); } catch (Exception e) { return nvl(customProto); }
        switch (t) {
            case 0: return "aim"; case 1: return "msn"; case 2: return "yahoo";
            case 3: return "skype"; case 4: return "qq"; case 5: return "google talk";
            case 6: return "icq"; case 7: return "jabber"; case 8: return "netmeeting";
            default: return customProto != null ? customProto : "";
        }
    }

    // Relation.TYPE_* (ASSISTANT=1, BROTHER=2, CHILD=3, DOMESTIC_PARTNER=4,
    // FATHER=5, FRIEND=6, MANAGER=7, MOTHER=8, PARENT=9, PARTNER=10,
    // REFERRED_BY=11, RELATIVE=12, SISTER=13, SPOUSE=14).
    private static String relationTypeLabel(String typeInt, String customLabel) {
        if (typeInt == null) return "";
        int t;
        try { t = Integer.parseInt(typeInt); } catch (Exception e) { return ""; }
        switch (t) {
            case 1: return "assistant"; case 2: return "brother"; case 3: return "child";
            case 4: return "domestic partner"; case 5: return "father"; case 6: return "friend";
            case 7: return "manager"; case 8: return "mother"; case 9: return "parent";
            case 10: return "partner"; case 11: return "referred by"; case 12: return "relative";
            case 13: return "sister"; case 14: return "spouse";
            default: return (customLabel != null && !customLabel.isEmpty()) ? customLabel.toLowerCase(Locale.ROOT) : "";
        }
    }

    // A cell starting with =, +, -, @, or a tab is interpreted as a formula
    // by Excel/Google Sheets/LibreOffice when this CSV is opened — a
    // contact whose name or note is attacker-controlled (e.g. shared with
    // someone, or synced from an untrusted source) could otherwise run a
    // formula on whoever opens the export. Prefixing with a single quote
    // neutralizes it as a formula while keeping the visible text unchanged
    // in every spreadsheet app that opens this CSV.
    private static String csvEsc(String s) {
        if (s == null) return "";
        String escaped = s.replace("\"", "\"\"");
        if (!escaped.isEmpty() && "=+-@\t".indexOf(escaped.charAt(0)) >= 0) {
            escaped = "'" + escaped;
        }
        return escaped;
    }
    private static String nvl(String s) { return s != null ? s : ""; }
}
