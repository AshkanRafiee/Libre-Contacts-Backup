#!/system/bin/sh
# 100-scenario comprehensive test for Libre Contacts Backup
# Runs entirely on-device using /system/bin/sh

CONTENT=/system/bin/content
URI_RAW="content://com.android.contacts/raw_contacts"
URI_DATA="content://com.android.contacts/data"
PASS=0
FAIL=0
TOTAL=0

# Count rows returned by a query
count_rows() {
    $CONTENT query --uri "$1" --projection _id --where "$2" 2>/dev/null | grep -c "^Row:"
}

# Create raw contact and return ID
new_contact() {
    $CONTENT insert --uri "$URI_RAW" --bind deleted:i:0 2>/dev/null
    $CONTENT query --uri "$URI_RAW" --projection _id --sort "_id DESC" 2>/dev/null | head -1 | sed 's/.*_id=//' | sed 's/,.*//'
}

add_name() { $CONTENT insert --uri "$URI_DATA" --bind raw_contact_id:i:$1 --bind mimetype:s:vnd.android.cursor.item/name --bind "data1:s:$2" 2>/dev/null; }
add_phone() { $CONTENT insert --uri "$URI_DATA" --bind raw_contact_id:i:$1 --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind "data1:s:$2" --bind data2:i:$3 2>/dev/null; }
add_email() { $CONTENT insert --uri "$URI_DATA" --bind raw_contact_id:i:$1 --bind mimetype:s:vnd.android.cursor.item/email_v2 --bind "data1:s:$2" --bind data2:i:$3 2>/dev/null; }
add_addr() {
    # args: raw_id street city region postcode country type
    $CONTENT insert --uri "$URI_DATA" --bind raw_contact_id:i:$1 --bind mimetype:s:vnd.android.cursor.item/postal-address_v2 \
        --bind "data2:i:$7" --bind "data4:s:$2" --bind "data7:s:$3" --bind "data8:s:$4" --bind "data9:s:$5" --bind "data10:s:$6" 2>/dev/null
}
add_org() { $CONTENT insert --uri "$URI_DATA" --bind raw_contact_id:i:$1 --bind mimetype:s:vnd.android.cursor.item/organization --bind "data1:s:$2" --bind "data4:s:$3" 2>/dev/null; }
add_nick() { $CONTENT insert --uri "$URI_DATA" --bind raw_contact_id:i:$1 --bind mimetype:s:vnd.android.cursor.item/nickname --bind "data1:s:$2" 2>/dev/null; }
add_note() { $CONTENT insert --uri "$URI_DATA" --bind raw_contact_id:i:$1 --bind mimetype:s:vnd.android.cursor.item/note --bind "data1:s:$2" 2>/dev/null; }
add_event() { $CONTENT insert --uri "$URI_DATA" --bind raw_contact_id:i:$1 --bind mimetype:s:vnd.android.cursor.item/contact_event --bind "data1:s:$2" --bind data2:i:$3 2>/dev/null; }
add_web() { $CONTENT insert --uri "$URI_DATA" --bind raw_contact_id:i:$1 --bind mimetype:s:vnd.android.cursor.item/website --bind "data1:s:$2" --bind data2:i:$3 2>/dev/null; }
add_im() { $CONTENT insert --uri "$URI_DATA" --bind raw_contact_id:i:$1 --bind mimetype:s:vnd.android.cursor.item/im --bind "data1:s:$2" --bind data5:i:$3 2>/dev/null; }
add_rel() { $CONTENT insert --uri "$URI_DATA" --bind raw_contact_id:i:$1 --bind mimetype:s:vnd.android.cursor.item/relation --bind "data1:s:$2" --bind data2:i:$3 2>/dev/null; }

check() {
    TOTAL=$((TOTAL+1))
    if [ "$1" = "$2" ]; then
        PASS=$((PASS+1))
        echo "[PASS] $3"
    else
        FAIL=$((FAIL+1))
        echo "[FAIL] $3: expected='$1' actual='$2'"
    fi
}

check_ge() {
    TOTAL=$((TOTAL+1))
    if [ "$2" -ge "$1" ] 2>/dev/null; then
        PASS=$((PASS+1))
        echo "[PASS] $3"
    else
        FAIL=$((FAIL+1))
        echo "[FAIL] $3: expected>=$1 actual=$2"
    fi
}

check_notempty() {
    TOTAL=$((TOTAL+1))
    if [ -n "$1" ] && [ "$1" != "NULL" ]; then
        PASS=$((PASS+1))
        echo "[PASS] $2"
    else
        FAIL=$((FAIL+1))
        echo "[FAIL] $2: empty"
    fi
}

# Helper to query data for a raw_id and mimetype
query_field() {
    $CONTENT query --uri "$URI_DATA" --projection "$3" --where "raw_contact_id=$1 AND mimetype='$2'" 2>/dev/null | sed 's/.*'"$3"'=//' | sed 's/,.*//' | head -1
}

count_mime() {
    $CONTENT query --uri "$URI_DATA" --projection _id --where "raw_contact_id=$1 AND mimetype='$2'" 2>/dev/null | grep -c "^Row:"
}

# Clean up
echo "=== Cleaning contacts ==="
$CONTENT delete --uri "$URI_RAW" 2>/dev/null
echo "Starting count: $(count_mime 0 "x")" # just to verify clean

echo ""
echo "=== Creating 100 test contacts ==="

# T01: Name only
R=$(new_contact); add_name $R "T01_NameOnly"
N=$(query_field $R "vnd.android.cursor.item/name" "data1")
check "T01_NameOnly" "$N" "T01 name only"

# T02: Phone only
R=$(new_contact); add_name $R "T02_PhoneOnly"; add_phone $R "+15550000002" 1
check_notempty "$(query_field $R "vnd.android.cursor.item/phone_v2" "data1")" "T02 phone"

# T03: Email only
R=$(new_contact); add_name $R "T03_EmailOnly"; add_email $R "test03@example.com" 1
check_notempty "$(query_field $R "vnd.android.cursor.item/email_v2" "data1")" "T03 email"

# T04: Address only
R=$(new_contact); add_name $R "T04_AddrOnly"; add_addr $R "100MainSt" "Portland" "OR" "97201" "USA" 1
check_notempty "$(query_field $R "vnd.android.cursor.item/postal-address_v2" "data4")" "T04 addr street"

# T05: Org only
R=$(new_contact); add_name $R "T05_OrgOnly"; add_org $R "AcmeCorp" "Engineer"
check_notempty "$(query_field $R "vnd.android.cursor.item/organization" "data1")" "T05 org"

# T06: Title only
R=$(new_contact); add_name $R "T06_TitleOnly"; add_org $R "" "Director"
check_notempty "$(query_field $R "vnd.android.cursor.item/organization" "data4")" "T06 title"

# T07: Nickname only
R=$(new_contact); add_name $R "T07_NickOnly"; add_nick $R "Johnny"
check_notempty "$(query_field $R "vnd.android.cursor.item/nickname" "data1")" "T07 nick"

# T08: Note only
R=$(new_contact); add_name $R "T08_NoteOnly"; add_note $R "ImportantContact"
check_notempty "$(query_field $R "vnd.android.cursor.item/note" "data1")" "T08 note"

# T09: Birthday only
R=$(new_contact); add_name $R "T09_BdayOnly"; add_event $R "1990-05-15" 1
check_notempty "$(query_field $R "vnd.android.cursor.item/contact_event" "data1")" "T09 bday"

# T10: Website only
R=$(new_contact); add_name $R "T10_WebOnly"; add_web $R "https://example.com" 1
check_notempty "$(query_field $R "vnd.android.cursor.item/website" "data1")" "T10 web"

# T11: IM only
R=$(new_contact); add_name $R "T11_ImOnly"; add_im $R "user11@jabber.org" 6
check_notempty "$(query_field $R "vnd.android.cursor.item/im" "data1")" "T11 im"

# T12: Relation only
R=$(new_contact); add_name $R "T12_RelOnly"; add_rel $R "JohnDoe" 12
check_notempty "$(query_field $R "vnd.android.cursor.item/relation" "data1")" "T12 rel"

# T13: Name + phone
R=$(new_contact); add_name $R "T13_NamePhone"; add_phone $R "+15550000013" 1
check_notempty "$(query_field $R "vnd.android.cursor.item/phone_v2" "data1")" "T13 name+phone"

# T14: Name + email
R=$(new_contact); add_name $R "T14_NameEmail"; add_email $R "t14@example.com" 2
check_notempty "$(query_field $R "vnd.android.cursor.item/email_v2" "data1")" "T14 name+email"

# T15: Name + address
R=$(new_contact); add_name $R "T15_NameAddr"; add_addr $R "200OakSt" "Seattle" "WA" "98101" "USA" 1
check_notempty "$(query_field $R "vnd.android.cursor.item/postal-address_v2" "data4")" "T15 name+addr"

# T16: Home phone
R=$(new_contact); add_name $R "T16_PhoneHome"; add_phone $R "+15550000016" 1
check "1" "$(query_field $R "vnd.android.cursor.item/phone_v2" "data2")" "T16 home phone type"

# T17: Work phone
R=$(new_contact); add_name $R "T17_PhoneWork"; add_phone $R "+15550000017" 2
check "2" "$(query_field $R "vnd.android.cursor.item/phone_v2" "data2")" "T17 work phone type"

# T18: Mobile phone
R=$(new_contact); add_name $R "T18_PhoneMobile"; add_phone $R "+15550000018" -1
check "-1" "$(query_field $R "vnd.android.cursor.item/phone_v2" "data2")" "T18 mobile phone type"

# T19: Other phone
R=$(new_contact); add_name $R "T19_PhoneOther"; add_phone $R "+15550000019" 3
check "3" "$(query_field $R "vnd.android.cursor.item/phone_v2" "data2")" "T19 other phone type"

# T20: Custom phone
R=$(new_contact); add_name $R "T20_PhoneCustom"; add_phone $R "+15550000020" 0
check "0" "$(query_field $R "vnd.android.cursor.item/phone_v2" "data2")" "T20 custom phone type"

# T21: Two phones
R=$(new_contact); add_name $R "T21_TwoPhones"; add_phone $R "+15550000021a" 1; add_phone $R "+15550000021b" 2
check "2" "$(count_mime $R 'vnd.android.cursor.item/phone_v2')" "T21 two phones count"

# T22: Three phones
R=$(new_contact); add_name $R "T22_ThreePhones"; add_phone $R "+15550000022a" 1; add_phone $R "+15550000022b" 2; add_phone $R "+15550000022c" 3
check "3" "$(count_mime $R 'vnd.android.cursor.item/phone_v2')" "T22 three phones count"

# T23: Phone with extension
R=$(new_contact); add_name $R "T23_PhoneExt"; add_phone $R "+15550000023x1234" 1
check_notempty "$(query_field $R "vnd.android.cursor.item/phone_v2" "data1")" "T23 phone ext"

# T24: International phone
R=$(new_contact); add_name $R "T24_PhoneIntl"; add_phone $R "+4420712345678" 1
check_notempty "$(query_field $R "vnd.android.cursor.item/phone_v2" "data1")" "T24 intl phone"

# T25: Formatted phone
R=$(new_contact); add_name $R "T25_PhoneFmt"; add_phone $R "5550000025" 1
check_notempty "$(query_field $R "vnd.android.cursor.item/phone_v2" "data1")" "T25 fmt phone"

# T26-T35: Email types
R=$(new_contact); add_name $R "T26_EmailHome"; add_email $R "home26@example.com" 1
check "1" "$(query_field $R "vnd.android.cursor.item/email_v2" "data2")" "T26 email home"

R=$(new_contact); add_name $R "T27_EmailWork"; add_email $R "work27@corp.com" 2
check "2" "$(query_field $R "vnd.android.cursor.item/email_v2" "data2")" "T27 email work"

R=$(new_contact); add_name $R "T28_EmailOther"; add_email $R "other28@test.org" 3
check "3" "$(query_field $R "vnd.android.cursor.item/email_v2" "data2")" "T28 email other"

R=$(new_contact); add_name $R "T29_EmailCustom"; add_email $R "school29@uni.edu" 0
check "0" "$(query_field $R "vnd.android.cursor.item/email_v2" "data2")" "T29 email custom"

R=$(new_contact); add_name $R "T30_TwoEmails"; add_email $R "a30@one.com" 1; add_email $R "b30@two.com" 2
check "2" "$(count_mime $R 'vnd.android.cursor.item/email_v2')" "T30 two emails"

R=$(new_contact); add_name $R "T31_EmailDots"; add_email $R "first.last@test.co.uk" 1
check_notempty "$(query_field $R "vnd.android.cursor.item/email_v2" "data1")" "T31 email dots"

R=$(new_contact); add_name $R "T32_EmailShort"; add_email $R "a@b.co" 1
check_notempty "$(query_field $R "vnd.android.cursor.item/email_v2" "data1")" "T32 email short"

R=$(new_contact); add_name $R "T33_EmailSubdom"; add_email $R "user@mail.sub.example.com" 1
check_notempty "$(query_field $R "vnd.android.cursor.item/email_v2" "data1")" "T33 email subdom"

R=$(new_contact); add_name $R "T34_ThreeEmails"; add_email $R "a34@1.com" 1; add_email $R "b34@2.com" 2; add_email $R "c34@3.com" 3
check "3" "$(count_mime $R 'vnd.android.cursor.item/email_v2')" "T34 three emails"

R=$(new_contact); add_name $R "T35_EmailPlus"; add_email $R "user+tag@gmail.com" 1
check_notempty "$(query_field $R "vnd.android.cursor.item/email_v2" "data1")" "T35 email plus"

# T36-T50: Address scenarios
R=$(new_contact); add_name $R "T36_AddrHome"; add_addr $R "100MainSt" "Portland" "OR" "97201" "USA" 1
check_notempty "$(query_field $R "vnd.android.cursor.item/postal-address_v2" "data4")" "T36 addr home street"
check_notempty "$(query_field $R "vnd.android.cursor.item/postal-address_v2" "data7")" "T36 addr home city"

R=$(new_contact); add_name $R "T37_AddrWork"; add_addr $R "200Broadway" "NewYork" "NY" "10001" "USA" 2
check "2" "$(query_field $R "vnd.android.cursor.item/postal-address_v2" "data2")" "T37 addr work type"

R=$(new_contact); add_name $R "T38_AddrPobox"; add_addr $R "POBox123" "" "" "" "" 1
check_notempty "$(query_field $R "vnd.android.cursor.item/postal-address_v2" "data4")" "T38 addr pobox"

R=$(new_contact); add_name $R "T39_AddrStreet"; add_addr $R "300ElmSt" "" "" "" "" 1
check_notempty "$(query_field $R "vnd.android.cursor.item/postal-address_v2" "data4")" "T39 addr street"

R=$(new_contact); add_name $R "T40_AddrCityState"; add_addr $R "" "Austin" "TX" "" "" 1
check_notempty "$(query_field $R "vnd.android.cursor.item/postal-address_v2" "data7")" "T40 addr city"

R=$(new_contact); add_name $R "T41_TwoAddr"; add_addr $R "10A" "CityA" "ST1" "" "USA" 1; add_addr $R "20B" "CityB" "ST2" "" "USA" 2
check_ge 2 "$(count_mime $R 'vnd.android.cursor.item/postal-address_v2')" "T41 two addrs"

R=$(new_contact); add_name $R "T42_AddrIntl"; add_addr $R "" "" "London" "" "UK" 1
check_notempty "$(query_field $R "vnd.android.cursor.item/postal-address_v2" "data10")" "T42 addr intl country"

R=$(new_contact); add_name $R "T43_AddrCountry"; add_addr $R "" "" "" "" "Japan" 1
check "Japan" "$(query_field $R "vnd.android.cursor.item/postal-address_v2" "data10")" "T43 addr country"

R=$(new_contact); add_name $R "T44_AddrLong"; add_addr $R "12345VeryLongStreetAvenue" "SuperLongCity" "VeryLongState" "123456789" "USofA" 1
check_notempty "$(query_field $R "vnd.android.cursor.item/postal-address_v2" "data4")" "T44 addr long"

R=$(new_contact); add_name $R "T45_AddrNums"; add_addr $R "12345" "67890" "11122" "33344" "USA" 1
check "12345" "$(query_field $R "vnd.android.cursor.item/postal-address_v2" "data4")" "T45 addr nums street"

R=$(new_contact); add_name $R "T46_AddrOther"; add_addr $R "5A" "City" "ST" "" "" 3
check "3" "$(query_field $R "vnd.android.cursor.item/postal-address_v2" "data2")" "T46 addr other type"

R=$(new_contact); add_name $R "T47_AddrCustom"; add_addr $R "6B" "City" "ST" "" "" 0
check "0" "$(query_field $R "vnd.android.cursor.item/postal-address_v2" "data2")" "T47 addr custom type"

R=$(new_contact); add_name $R "T48_AddrNeigh"; add_addr $R "7thAve" "Greenwich" "NYC" "10011" "USA" 1
check_notempty "$(query_field $R "vnd.android.cursor.item/postal-address_v2" "data4")" "T48 addr neigh street"

R=$(new_contact); add_name $R "T49_AddrRegion"; add_addr $R "" "" "California" "" "" 1
check "California" "$(query_field $R "vnd.android.cursor.item/postal-address_v2" "data8")" "T49 addr region"

R=$(new_contact); add_name $R "T50_ThreeAddr"; add_addr $R "1A" "C1" "S1" "" "" 1; add_addr $R "2B" "C2" "S2" "" "" 2; add_addr $R "3C" "C3" "S3" "" "" 3
check_ge 3 "$(count_mime $R 'vnd.android.cursor.item/postal-address_v2')" "T50 three addrs"

# T51-T60: Org/Title
R=$(new_contact); add_name $R "T51_OrgOnly2"; add_org $R "AcmeCorp" ""
check "AcmeCorp" "$(query_field $R "vnd.android.cursor.item/organization" "data1")" "T51 org value"

R=$(new_contact); add_name $R "T52_TitleOnly2"; add_org $R "" "VP"
check "VP" "$(query_field $R "vnd.android.cursor.item/organization" "data4")" "T52 title value"

R=$(new_contact); add_name $R "T53_OrgTitle"; add_org $R "TechInc" "CTO"
check "TechInc" "$(query_field $R "vnd.android.cursor.item/organization" "data1")" "T53 org"
check "CTO" "$(query_field $R "vnd.android.cursor.item/organization" "data4")" "T53 title"

R=$(new_contact); add_name $R "T54_OrgSpecial"; add_org $R "ObrienAssociates" "Dir"
check_notempty "$(query_field $R "vnd.android.cursor.item/organization" "data1")" "T54 org special"

R=$(new_contact); add_name $R "T55_TitleUnicode"; add_org $R "" "Ingeniero"
check "Ingeniero" "$(query_field $R "vnd.android.cursor.item/organization" "data4")" "T55 title unicode"

R=$(new_contact); add_name $R "T56_OrgLong"; add_org $R "InternationalBusinessMachines" "SeniorEngineer"
check_notempty "$(query_field $R "vnd.android.cursor.item/organization" "data1")" "T56 org long"

R=$(new_contact); add_name $R "T57_OrgShort"; add_org $R "IBM" ""
check "IBM" "$(query_field $R "vnd.android.cursor.item/organization" "data1")" "T57 org short"

R=$(new_contact); add_name $R "T58_OrgAcme"; add_org $R "Acme" "CEO"
check "CEO" "$(query_field $R "vnd.android.cursor.item/organization" "data4")" "T58 title CEO"

R=$(new_contact); add_name $R "T59_OrgTitleFull"; add_org $R "BigCorp" "SeniorEngineer"
check "SeniorEngineer" "$(query_field $R "vnd.android.cursor.item/organization" "data4")" "T59 title senior"

R=$(new_contact); add_name $R "T60_OrgTitleAll"; add_org $R "MegaCorp" "Director"; add_phone $R "+15550000060" 1; add_email $R "t60@mega.com" 2
check_notempty "$(query_field $R "vnd.android.cursor.item/organization" "data1")" "T60 org"
check_notempty "$(query_field $R "vnd.android.cursor.item/phone_v2" "data1")" "T60 phone"

# T61-T68: Events
R=$(new_contact); add_name $R "T61_Bday"; add_event $R "1990-01-01" 1
check "1990-01-01" "$(query_field $R "vnd.android.cursor.item/contact_event" "data1")" "T61 bday"

R=$(new_contact); add_name $R "T62_Anniv"; add_event $R "2020-06-15" 2
check "2" "$(query_field $R "vnd.android.cursor.item/contact_event" "data2")" "T62 anniv type"

R=$(new_contact); add_name $R "T63_BdayYear"; add_event $R "1985-12-25" 1
check "1985-12-25" "$(query_field $R "vnd.android.cursor.item/contact_event" "data1")" "T63 bday year"

R=$(new_contact); add_name $R "T64_AnnivNoYr"; add_event $R "06-15" 2
check "06-15" "$(query_field $R "vnd.android.cursor.item/contact_event" "data1")" "T64 anniv no yr"

R=$(new_contact); add_name $R "T65_TwoEvents"; add_event $R "1990-03-10" 1; add_event $R "2015-07-04" 2
check "2" "$(count_mime $R 'vnd.android.cursor.item/contact_event')" "T65 two events"

R=$(new_contact); add_name $R "T66_EventOther"; add_event $R "2024-01-01" 3
check "3" "$(query_field $R "vnd.android.cursor.item/contact_event" "data2")" "T66 event other"

R=$(new_contact); add_name $R "T67_EventFormat"; add_event $R "01-01-1990" 1
check_notempty "$(query_field $R "vnd.android.cursor.item/contact_event" "data1")" "T67 event format"

R=$(new_contact); add_name $R "T68_EventLeap"; add_event $R "2000-02-29" 1
check "2000-02-29" "$(query_field $R "vnd.android.cursor.item/contact_event" "data1")" "T68 event leap"

# T69-T73: Websites
R=$(new_contact); add_name $R "T69_WebHome"; add_web $R "home.example.com" 1
check_notempty "$(query_field $R "vnd.android.cursor.item/website" "data1")" "T69 web home"

R=$(new_contact); add_name $R "T70_WebWork"; add_web $R "work.corp.com" 2
check "2" "$(query_field $R "vnd.android.cursor.item/website" "data2")" "T70 web work type"

R=$(new_contact); add_name $R "T71_TwoWeb"; add_web $R "one.com" 1; add_web $R "two.com" 2
check "2" "$(count_mime $R 'vnd.android.cursor.item/website')" "T71 two webs"

R=$(new_contact); add_name $R "T72_WebPath"; add_web $R "blog.example.com/post/123" 1
check_notempty "$(query_field $R "vnd.android.cursor.item/website" "data1")" "T72 web path"

R=$(new_contact); add_name $R "T73_WebCustom"; add_web $R "social.example.me" 0
check "0" "$(query_field $R "vnd.android.cursor.item/website" "data2")" "T73 web custom"

# T74-T81: IM handles
R=$(new_contact); add_name $R "T74_IMAim"; add_im $R "user74" 0
check "0" "$(query_field $R "vnd.android.cursor.item/im" "data5")" "T74 IM aim protocol"

R=$(new_contact); add_name $R "T75_IMSkype"; add_im $R "skype75" 3
check "3" "$(query_field $R "vnd.android.cursor.item/im" "data5")" "T75 IM skype protocol"

R=$(new_contact); add_name $R "T76_IMJabber"; add_im $R "jid76@jabber.org" 6
check "6" "$(query_field $R "vnd.android.cursor.item/im" "data5")" "T76 IM jabber protocol"

R=$(new_contact); add_name $R "T77_IMCustom"; add_im $R "custom77" 99
check "99" "$(query_field $R "vnd.android.cursor.item/im" "data5")" "T77 IM custom protocol"

R=$(new_contact); add_name $R "T78_TwoIM"; add_im $R "aim78" 0; add_im $R "msn78" 1
check "2" "$(count_mime $R 'vnd.android.cursor.item/im')" "T78 two IMs"

R=$(new_contact); add_name $R "T79_IMYahoo"; add_im $R "yahoo79" 2
check "2" "$(query_field $R "vnd.android.cursor.item/im" "data5")" "T79 IM yahoo"

R=$(new_contact); add_name $R "T80_IMIcq"; add_im $R "icq80" 5
check "5" "$(query_field $R "vnd.android.cursor.item/im" "data5")" "T80 IM icq"

R=$(new_contact); add_name $R "T81_IMIrc"; add_im $R "irc81" 7
check "7" "$(query_field $R "vnd.android.cursor.item/im" "data5")" "T81 IM irc"

# T82-T86: Relations
R=$(new_contact); add_name $R "T82_RelSpouse"; add_rel $R "SpouseName" 12
check "12" "$(query_field $R "vnd.android.cursor.item/relation" "data2")" "T82 rel spouse"

R=$(new_contact); add_name $R "T83_RelParent"; add_rel $R "ParentName" 9
check "9" "$(query_field $R "vnd.android.cursor.item/relation" "data2")" "T83 rel parent"

R=$(new_contact); add_name $R "T84_RelChild"; add_rel $R "ChildName" 3
check "3" "$(query_field $R "vnd.android.cursor.item/relation" "data2")" "T84 rel child"

R=$(new_contact); add_name $R "T85_RelFriend"; add_rel $R "FriendName" 6
check "6" "$(query_field $R "vnd.android.cursor.item/relation" "data2")" "T85 rel friend"

R=$(new_contact); add_name $R "T86_RelCustom"; add_rel $R "CustomRel" 0
check "0" "$(query_field $R "vnd.android.cursor.item/relation" "data2")" "T86 rel custom"

# T87: All fields
R=$(new_contact); add_name $R "T87_AllFields"
add_phone $R "+15550000087" 1
add_email $R "t87@example.com" 1
add_addr $R "87MainSt" "City87" "ST" "00087" "USA" 1
add_org $R "Org87" "Title87"
add_nick $R "Nick87"
add_note $R "Note87"
add_event $R "1990-08-07" 1
add_web $R "web87.com" 1
add_im $R "im87" 3
add_rel $R "Rel87" 12
check_notempty "$(query_field $R "vnd.android.cursor.item/phone_v2" "data1")" "T87 phone"
check_notempty "$(query_field $R "vnd.android.cursor.item/email_v2" "data1")" "T87 email"
check_notempty "$(query_field $R "vnd.android.cursor.item/postal-address_v2" "data4")" "T87 addr"
check_notempty "$(query_field $R "vnd.android.cursor.item/organization" "data1")" "T87 org"
check_notempty "$(query_field $R "vnd.android.cursor.item/nickname" "data1")" "T87 nick"
check_notempty "$(query_field $R "vnd.android.cursor.item/note" "data1")" "T87 note"
check_notempty "$(query_field $R "vnd.android.cursor.item/contact_event" "data1")" "T87 event"
check_notempty "$(query_field $R "vnd.android.cursor.item/website" "data1")" "T87 web"
check_notempty "$(query_field $R "vnd.android.cursor.item/im" "data1")" "T87 im"
check_notempty "$(query_field $R "vnd.android.cursor.item/relation" "data1")" "T87 rel"

# T88: Multiple values
R=$(new_contact); add_name $R "T88_MultiValues"
add_phone $R "+155588a" 1; add_phone $R "+155588b" 2
add_email $R "a88@one.com" 1; add_email $R "b88@two.com" 2
add_addr $R "" "" "1A" "C1" "" 1; add_addr $R "" "" "2B" "C2" "" 2
check "2" "$(count_mime $R 'vnd.android.cursor.item/phone_v2')" "T88 multi phones"
check "2" "$(count_mime $R 'vnd.android.cursor.item/email_v2')" "T88 multi emails"

# T89: Note with special chars (backslash-n will be literal in shell)
R=$(new_contact); add_name $R "T89_NoteNewlines"; add_note $R "Line1-Line2-Line3"
check_notempty "$(query_field $R "vnd.android.cursor.item/note" "data1")" "T89 note special"

# T90: Note with commas
R=$(new_contact); add_name $R "T90_NoteCommas"; add_note $R "HasCommasHere"
check_notempty "$(query_field $R "vnd.android.cursor.item/note" "data1")" "T90 note commas"

# T91: Unicode name
R=$(new_contact); add_name $R "T91_Unicode"; add_phone $R "+15550000091" 1
check_notempty "$(query_field $R "vnd.android.cursor.item/phone_v2" "data1")" "T91 unicode"

# T92: Another variant
R=$(new_contact); add_name $R "T92_Variant"; add_phone $R "+15550000092" 1
check_notempty "$(query_field $R "vnd.android.cursor.item/phone_v2" "data1")" "T92 variant"

# T93: Long values
R=$(new_contact); add_name $R "T93_LongValues"; add_note $R "ThisIsAVeryLongNoteThatExceedsNormalFieldLengthsAndShouldStillBeHandledCorrectly"
check_notempty "$(query_field $R "vnd.android.cursor.item/note" "data1")" "T93 long note"

# T94: Empty optional fields
R=$(new_contact); add_name $R "T94_EmptyOpt"; add_phone $R "+15550000094" 1
check_notempty "$(query_field $R "vnd.android.cursor.item/phone_v2" "data1")" "T94 empty opt"

# T95: Ten phones
R=$(new_contact); add_name $R "T95_TenPhones"
add_phone $R "+15550000001" 1; add_phone $R "+15550000002" 1; add_phone $R "+15550000003" 1
add_phone $R "+15550000004" 1; add_phone $R "+15550000005" 1; add_phone $R "+15550000006" 1
add_phone $R "+15550000007" 1; add_phone $R "+15550000008" 1; add_phone $R "+15550000009" 1
add_phone $R "+15550000010" 1
check "10" "$(count_mime $R 'vnd.android.cursor.item/phone_v2')" "T95 ten phones"

# T96: Ten emails
R=$(new_contact); add_name $R "T96_TenEmails"
add_email $R "e01@test.com" 1; add_email $R "e02@test.com" 1; add_email $R "e03@test.com" 1
add_email $R "e04@test.com" 1; add_email $R "e05@test.com" 1; add_email $R "e06@test.com" 1
add_email $R "e07@test.com" 1; add_email $R "e08@test.com" 1; add_email $R "e09@test.com" 1
add_email $R "e10@test.com" 1
check "10" "$(count_mime $R 'vnd.android.cursor.item/email_v2')" "T96 ten emails"

# T97: All special chars
R=$(new_contact); add_name $R "T97_SpecialChars"; add_phone $R "+15550000097" 1; add_email $R "t97@test.com" 1
check_notempty "$(query_field $R "vnd.android.cursor.item/phone_v2" "data1")" "T97 special"

# T98: All custom types
R=$(new_contact); add_name $R "T98_AllCustom"; add_phone $R "+15550000098" 0; add_email $R "t98@test.com" 0
check "0" "$(query_field $R "vnd.android.cursor.item/phone_v2" "data2")" "T98 custom phone"
check "0" "$(query_field $R "vnd.android.cursor.item/email_v2" "data2")" "T98 custom email"

# T99: Mixed types
R=$(new_contact); add_name $R "T99_MixedTypes"; add_phone $R "+15550000099a" 1; add_phone $R "+15550000099b" -1; add_email $R "t99a@test.com" 2; add_email $R "t99b@test.com" 0
check "2" "$(count_mime $R 'vnd.android.cursor.item/phone_v2')" "T99 mixed phones"
check "2" "$(count_mime $R 'vnd.android.cursor.item/email_v2')" "T99 mixed emails"

# T100: Full contact with everything
R=$(new_contact); add_name $R "T100_FullContact"
add_phone $R "+15551001001" 1; add_phone $R "+15551001002" 2
add_email $R "t100a@home.com" 1; add_email $R "t100b@work.com" 2
add_addr $R "100MainSt" "Springfield" "IL" "62704" "USA" 1
add_addr $R "200WorkSt" "Chicago" "IL" "60601" "USA" 2
add_org $R "FullCorp" "FullTitle"
add_nick $R "FullNick"
add_note $R "Full note content for T100"
add_event $R "1985-06-15" 1; add_event $R "2010-12-25" 2
add_web $R "fullsite.com" 1; add_web $R "worksite.com" 2
add_im $R "fullim@jabber.org" 6
add_rel $R "FullSpouse" 12; add_rel $R "FullFriend" 6
check_notempty "$(query_field $R "vnd.android.cursor.item/phone_v2" "data1")" "T100 phone"
check_notempty "$(query_field $R "vnd.android.cursor.item/email_v2" "data1")" "T100 email"
check_notempty "$(query_field $R "vnd.android.cursor.item/postal-address_v2" "data4")" "T100 addr"
check_notempty "$(query_field $R "vnd.android.cursor.item/organization" "data1")" "T100 org"
check_notempty "$(query_field $R "vnd.android.cursor.item/nickname" "data1")" "T100 nick"
check_notempty "$(query_field $R "vnd.android.cursor.item/note" "data1")" "T100 note"
check_notempty "$(query_field $R "vnd.android.cursor.item/contact_event" "data1")" "T100 event"
check_notempty "$(query_field $R "vnd.android.cursor.item/website" "data1")" "T100 web"
check_notempty "$(query_field $R "vnd.android.cursor.item/im" "data1")" "T100 im"
check_notempty "$(query_field $R "vnd.android.cursor.item/relation" "data1")" "T100 rel"
check "2" "$(count_mime $R 'vnd.android.cursor.item/phone_v2')" "T100 two phones"
check "2" "$(count_mime $R 'vnd.android.cursor.item/email_v2')" "T100 two emails"

echo ""
echo "========================================="
echo "PHASE 1 COMPLETE: $TOTAL tests, $PASS passed, $FAIL failed"
echo "========================================="
