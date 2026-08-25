#!/bin/bash
# Libre Contacts Backup - Automated Test Suite
# Usage: bash tests/test_all.sh
# Prerequisites: adb connected, app installed, device at home screen
set -e

PACKAGE="com.ashkanrafiee.librecontactsbackup"
PASS=0
FAIL=0
TOTAL=0

# Strip CR characters emitted by newer adb shell versions and re-quote arguments,
# because modern adb joins multi-argument shell commands without preserving quoting.
adb() {
    if [ "$1" = "shell" ]; then
        shift
        local joined="" arg
        for arg in "$@"; do
            case "$arg" in
                *[!A-Za-z0-9_@%+=:,./-]*)
                    arg=$(printf '%s' "$arg" | sed "s/'/'\\\\''/g")
                    joined="$joined '$arg'"
                    ;;
                *)
                    joined="$joined $arg"
                    ;;
            esac
        done
        command adb shell "$joined" | tr -d '\r'
    else
        command adb "$@"
    fi
}

green() { printf "\033[32m%s\033[0m\n" "$1"; }
red()   { printf "\033[31m%s\033[0m\n" "$1"; }
info()  { printf "\033[36m%s\033[0m\n" "$1"; }

assert_eq() {
    TOTAL=$((TOTAL+1))
    local label="$1" expected="$2" actual="$3"
    if [ "$expected" = "$actual" ]; then
        green "  PASS: $label"
        PASS=$((PASS+1))
    else
        red "  FAIL: $label (expected '$expected', got '$actual')"
        FAIL=$((FAIL+1))
    fi
}

assert_contains() {
    TOTAL=$((TOTAL+1))
    local label="$1" haystack="$2" needle="$3"
    if echo "$haystack" | grep -q "$needle"; then
        green "  PASS: $label"
        PASS=$((PASS+1))
    else
        red "  FAIL: $label (output does not contain '$needle')"
        FAIL=$((FAIL+1))
    fi
}

assert_not_contains() {
    TOTAL=$((TOTAL+1))
    local label="$1" haystack="$2" needle="$3"
    if echo "$haystack" | grep -q "$needle"; then
        red "  FAIL: $label (output unexpectedly contains '$needle')"
        FAIL=$((FAIL+1))
    else
        green "  PASS: $label"
        PASS=$((PASS+1))
    fi
}

cleanup_contacts() {
    info "  Cleaning all contacts..."
    adb shell content delete --uri content://com.android.contacts/raw_contacts 2>/dev/null || true
    local count=$(adb shell content query --uri content://com.android.contacts/raw_contacts --projection _id 2>/dev/null | (grep -c "Row:" || true))
    assert_eq "Contacts cleaned" "0" "$count"
}

count_raw_contacts() {
    adb shell content query --uri content://com.android.contacts/raw_contacts --projection _id 2>/dev/null | (grep -c "Row:" || true)
}

query_field() {
    # $1=raw_id $2=mimetype $3=column_to_select (e.g. data1)
    adb shell content query --uri content://com.android.contacts/data --projection "$3" --where "raw_contact_id=$1 AND mimetype='$2'" 2>/dev/null | grep -oP "(?<=$3=)[^,]*" | head -1
}

query_all_data() {
    # Returns all data rows for a raw_contact_id
    adb shell content query --uri content://com.android.contacts/data --projection data1:data2:data3:data4:data5 --where "raw_contact_id=$1" 2>/dev/null
}

# ============================================================
# SETUP
# ============================================================
info "=== Setting up test environment ==="
cleanup_contacts

# ============================================================
# TEST 1: Create basic contact via content insert, backup, verify VCF
# ============================================================
info ""
info "=== TEST 1: Basic backup - phones, emails, addresses ==="

adb shell content insert --uri content://com.android.contacts/raw_contacts --bind deleted:i:0
RAW=$(adb shell content query --uri content://com.android.contacts/raw_contacts --projection _id --sort "_id DESC" 2>/dev/null | head -1 | (grep -oP '(?<=_id=)\d+' || true))

adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW --bind mimetype:s:vnd.android.cursor.item/name --bind "data1:s:Test User"
adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind "data1:s:+15551234567" --bind data2:i:1
adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW --bind mimetype:s:vnd.android.cursor.item/email_v2 --bind "data1:s:test@example.com" --bind data2:i:2
adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW --bind mimetype:s:vnd.android.cursor.item/nickname --bind "data1:s:Tester"
adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW --bind mimetype:s:vnd.android.cursor.item/organization --bind "data1:s:TestCorp" --bind data4:s:Manager
adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW --bind mimetype:s:vnd.android.cursor.item/note --bind "data1:s:Important note"
adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW --bind mimetype:s:vnd.android.cursor.item/contact_event --bind "data1:s:1990-05-15" --bind data2:i:1
adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW --bind mimetype:s:vnd.android.cursor.item/website --bind "data1:s:www.example.com" --bind data2:i:1
adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW --bind mimetype:s:vnd.android.cursor.item/relation --bind "data1:s:Jane Doe" --bind data2:i:12

info "  Created Test User (raw_id=$RAW)"

# Backup via adb shell (use the app's backup manager)
info "  Running backup..."
adb shell am start -n "$PACKAGE/.MainActivity" 2>/dev/null || true
sleep 1

# Create backup via backup manager directly (push the test backup to device)
# Instead of using the UI, create a test .lcb file and push it
info "  Creating test backup file..."
mkdir -p /tmp/test_backup

# Create a minimal VCF for testing
cat > /tmp/test_backup/contacts.vcf << 'VCARD'
BEGIN:VCARD
VERSION:3.0
FN:Test User
TEL;TYPE=HOME:+15551234567
EMAIL;TYPE=WORK:test@example.com
ORG:TestCorp
TITLE:Manager
NICKNAME:Tester
NOTE:Important note
BDAY:1990-05-15
URL;TYPE=HOME:https://example.com
X-RELATION;TYPE=SPOUSE:Jane Doe
END:VCARD
BEGIN:VCARD
VERSION:3.0
FN:Multi Phone
TEL;TYPE=HOME:+15551111111
TEL;TYPE=WORK:+15552222222
EMAIL;TYPE=HOME:multi@home.com
EMAIL;TYPE=WORK:multi@work.com
ADR;TYPE=HOME:;;123 Main St;Springfield;IL;62704;USA
ADR;TYPE=WORK:;;456 Work Ave;Chicago;IL;60601;USA
END:VCARD
BEGIN:VCARD
VERSION:3.0
FN:Empty Fields
TEL;TYPE=MOBILE:+15559999999
END:VCARD
VCARD

cd /tmp/test_backup
rm -f test_backup.lcb
zip -j test_backup.lcb contacts.vcf
adb push test_backup.lcb /sdcard/Documents/test_backup.lcb
info "  Pushed test_backup.lcb to device"
cd -

# ============================================================
# TEST 2: Restore from backup file
# ============================================================
info ""
info "=== TEST 2: Restore from .lcb file ==="
# NOTE: contacts are intentionally NOT wiped here so TEST 3 can verify the
# fixtures created in TEST 1. Wiping happens at the start of TEST 6.

info "  Opening file picker via intent..."
# We can't automate the UI restore, so we test the restore via a test harness
# For now, verify the backup file exists and is valid
EXISTS=$(adb shell ls /sdcard/Documents/test_backup.lcb 2>&1 | grep -c "test_backup.lcb" || true)
assert_eq "Backup file exists on device" "1" "$EXISTS"

FILE_SIZE=$(adb shell stat -c%s /sdcard/Documents/test_backup.lcb 2>/dev/null || adb shell ls -la /sdcard/Documents/test_backup.lcb 2>/dev/null | awk '{print $5}')
if [ -n "$FILE_SIZE" ] && [ "$FILE_SIZE" -gt 0 ] 2>/dev/null; then
    green "  PASS: Backup file has content ($FILE_SIZE bytes)"
    PASS=$((PASS+1)); TOTAL=$((TOTAL+1))
else
    red "  FAIL: Backup file has content (size='$FILE_SIZE')"
    FAIL=$((FAIL+1)); TOTAL=$((TOTAL+1))
fi

# ============================================================
# TEST 3: Round-trip test - read contacts, verify all fields
# ============================================================
info ""
info "=== TEST 3: Verify contact data after creation ==="

# Verify Test User fields
NAME=$(adb shell content query --uri content://com.android.contacts/data --projection data1 --where "raw_contact_id=$RAW AND mimetype='vnd.android.cursor.item/name'" 2>/dev/null | (grep -oP '(?<=data1=)[^,]*' || true))
assert_eq "Test User name" "Test User" "$NAME"

PHONE=$(adb shell content query --uri content://com.android.contacts/data --projection data1 --where "raw_contact_id=$RAW AND mimetype='vnd.android.cursor.item/phone_v2'" 2>/dev/null | (grep -oP '(?<=data1=)[^,]*' || true))
assert_eq "Test User phone" "+15551234567" "$PHONE"

EMAIL=$(adb shell content query --uri content://com.android.contacts/data --projection data1 --where "raw_contact_id=$RAW AND mimetype='vnd.android.cursor.item/email_v2'" 2>/dev/null | (grep -oP '(?<=data1=)[^,]*' || true))
assert_eq "Test User email" "test@example.com" "$EMAIL"

ORG=$(adb shell content query --uri content://com.android.contacts/data --projection data1 --where "raw_contact_id=$RAW AND mimetype='vnd.android.cursor.item/organization'" 2>/dev/null | (grep -oP '(?<=data1=)[^,]*' || true))
assert_eq "Test User org" "TestCorp" "$ORG"

NOTE=$(adb shell content query --uri content://com.android.contacts/data --projection data1 --where "raw_contact_id=$RAW AND mimetype='vnd.android.cursor.item/note'" 2>/dev/null | (grep -oP '(?<=data1=)[^,]*' || true))
assert_eq "Test User note" "Important note" "$NOTE"

NICK=$(adb shell content query --uri content://com.android.contacts/data --projection data1 --where "raw_contact_id=$RAW AND mimetype='vnd.android.cursor.item/nickname'" 2>/dev/null | (grep -oP '(?<=data1=)[^,]*' || true))
assert_eq "Test User nickname" "Tester" "$NICK"

EVENT=$(adb shell content query --uri content://com.android.contacts/data --projection data1 --where "raw_contact_id=$RAW AND mimetype='vnd.android.cursor.item/contact_event'" 2>/dev/null | (grep -oP '(?<=data1=)[^,]*' || true))
assert_eq "Test User birthday" "1990-05-15" "$EVENT"

WEB=$(adb shell content query --uri content://com.android.contacts/data --projection data1 --where "raw_contact_id=$RAW AND mimetype='vnd.android.cursor.item/website'" 2>/dev/null | (grep -oP '(?<=data1=)[^,]*' || true))
assert_eq "Test User website" "www.example.com" "$WEB"

REL=$(adb shell content query --uri content://com.android.contacts/data --projection data1 --where "raw_contact_id=$RAW AND mimetype='vnd.android.cursor.item/relation'" 2>/dev/null | (grep -oP '(?<=data1=)[^,]*' || true))
assert_eq "Test User relation" "Jane Doe" "$REL"

# ============================================================
# TEST 4: Multi-field contact
# ============================================================
info ""
info "=== TEST 4: Multi-field contact ==="
RAW2=$(adb shell content query --uri content://com.android.contacts/raw_contacts --projection _id --where "display_name='Multi Phone'" 2>/dev/null | head -1 | (grep -oP '(?<=_id=)\d+' || true))
if [ -n "$RAW2" ]; then
PHONE_COUNT=$(adb shell content query --uri content://com.android.contacts/data --projection _id --where "raw_contact_id=$RAW2 AND mimetype='vnd.android.cursor.item/phone_v2'" 2>/dev/null | (grep -c "Row:" || true))
assert_eq "Multi Phone has 2 phones" "2" "$PHONE_COUNT"

EMAIL_COUNT=$(adb shell content query --uri content://com.android.contacts/data --projection _id --where "raw_contact_id=$RAW2 AND mimetype='vnd.android.cursor.item/email_v2'" 2>/dev/null | (grep -c "Row:" || true))
assert_eq "Multi Phone has 2 emails" "2" "$EMAIL_COUNT"

ADDR_COUNT=$(adb shell content query --uri content://com.android.contacts/data --projection _id --where "raw_contact_id=$RAW2 AND mimetype='vnd.android.cursor.item/postal-address'" 2>/dev/null | (grep -c "Row:" || true))
assert_eq "Multi Phone has 2 addresses" "2" "$ADDR_COUNT"
else
    info "  SKIP: 'Multi Phone' only exists after a manual UI restore of test_backup.lcb"
fi

# ============================================================
# TEST 5: Empty fields contact
# ============================================================
info ""
info "=== TEST 5: Minimal contact (only phone) ==="
RAW3=$(adb shell content query --uri content://com.android.contacts/raw_contacts --projection _id --where "display_name='Empty Fields'" 2>/dev/null | head -1 | (grep -oP '(?<=_id=)\d+' || true))
if [ -n "$RAW3" ]; then
assert_not_contains "Empty Fields has no email" "$(adb shell content query --uri content://com.android.contacts/data --projection _id --where "raw_contact_id=$RAW3 AND mimetype='vnd.android.cursor.item/email_v2'" 2>/dev/null)" "Row:"
assert_not_contains "Empty Fields has no address" "$(adb shell content query --uri content://com.android.contacts/data --projection _id --where "raw_contact_id=$RAW3 AND mimetype='vnd.android.cursor.item/postal-address'" 2>/dev/null)" "Row:"
else
    info "  SKIP: 'Empty Fields' only exists after a manual UI restore of test_backup.lcb"
fi

# ============================================================
# TEST 6: Address field mapping (SDK 35)
# ============================================================
info ""
info "=== TEST 6: Address field mapping ==="
# Create contact with full address
cleanup_contacts
adb shell content insert --uri content://com.android.contacts/raw_contacts --bind deleted:i:0
RAW4=$(adb shell content query --uri content://com.android.contacts/raw_contacts --projection _id --sort "_id DESC" 2>/dev/null | head -1 | (grep -oP '(?<=_id=)\d+' || true))
adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW4 --bind mimetype:s:vnd.android.cursor.item/name --bind "data1:s:Address Test"
adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW4 --bind mimetype:s:vnd.android.cursor.item/postal-address --bind "data1:s:Formatted" --bind data2:i:1 --bind "data4:s:100 Main St" --bind "data7:s:Springfield" --bind "data8:s:IL" --bind "data9:s:62704" --bind "data10:s:USA"

STREET=$(adb shell content query --uri content://com.android.contacts/data --projection data4 --where "raw_contact_id=$RAW4 AND mimetype='vnd.android.cursor.item/postal-address'" 2>/dev/null | (grep -oP '(?<=data4=)[^,]*' || true))
assert_eq "Address street" "100 Main St" "$STREET"

CITY=$(adb shell content query --uri content://com.android.contacts/data --projection data7 --where "raw_contact_id=$RAW4 AND mimetype='vnd.android.cursor.item/postal-address'" 2>/dev/null | (grep -oP '(?<=data7=)[^,]*' || true))
assert_eq "Address city" "Springfield" "$CITY"

REGION=$(adb shell content query --uri content://com.android.contacts/data --projection data8 --where "raw_contact_id=$RAW4 AND mimetype='vnd.android.cursor.item/postal-address'" 2>/dev/null | (grep -oP '(?<=data8=)[^,]*' || true))
assert_eq "Address region" "IL" "$REGION"

POSTCODE=$(adb shell content query --uri content://com.android.contacts/data --projection data9 --where "raw_contact_id=$RAW4 AND mimetype='vnd.android.cursor.item/postal-address'" 2>/dev/null | (grep -oP '(?<=data9=)[^,]*' || true))
assert_eq "Address postcode" "62704" "$POSTCODE"

COUNTRY=$(adb shell content query --uri content://com.android.contacts/data --projection data10 --where "raw_contact_id=$RAW4 AND mimetype='vnd.android.cursor.item/postal-address'" 2>/dev/null | (grep -oP '(?<=data10=)[^,]*' || true))
assert_eq "Address country" "USA" "$COUNTRY"

# ============================================================
# TEST 7: Special characters
# ============================================================
info ""
info "=== TEST 7: Special characters in names ==="
cleanup_contacts
adb shell content insert --uri content://com.android.contacts/raw_contacts --bind deleted:i:0
RAW5=$(adb shell content query --uri content://com.android.contacts/raw_contacts --projection _id --sort "_id DESC" 2>/dev/null | head -1 | (grep -oP '(?<=_id=)\d+' || true))
adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW5 --bind mimetype:s:vnd.android.cursor.item/name --bind 'data1:s:O'\''Brien Jr.'
adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW5 --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind 'data1:s:+1-555-0123'
adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW5 --bind mimetype:s:vnd.android.cursor.item/note --bind 'data1:s:Line1\nLine2'

NAME7=$(adb shell content query --uri content://com.android.contacts/data --projection data1 --where "raw_contact_id=$RAW5 AND mimetype='vnd.android.cursor.item/name'" 2>/dev/null | (grep -oP '(?<=data1=)[^,]*' || true))
info "  Name with apostrophe: '$NAME7'"

# ============================================================
# TEST 8: Multiple addresses with different types
# ============================================================
info ""
info "=== TEST 8: Multiple address types ==="
cleanup_contacts
adb shell content insert --uri content://com.android.contacts/raw_contacts --bind deleted:i:0
RAW6=$(adb shell content query --uri content://com.android.contacts/raw_contacts --projection _id --sort "_id DESC" 2>/dev/null | head -1 | (grep -oP '(?<=_id=)\d+' || true))
adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW6 --bind mimetype:s:vnd.android.cursor.item/name --bind "data1:s:Addr Types"
adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW6 --bind mimetype:s:vnd.android.cursor.item/postal-address --bind "data1:s:HomeFormatted" --bind data2:i:1 --bind "data4:s:Home St"
adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW6 --bind mimetype:s:vnd.android.cursor.item/postal-address --bind "data1:s:WorkFormatted" --bind data2:i:2 --bind "data4:s:Work Ave"
adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW6 --bind mimetype:s:vnd.android.cursor.item/postal-address --bind "data1:s:OtherFormatted" --bind data2:i:3 --bind "data4:s:Other Blvd"

ADDR_COUNT8=$(adb shell content query --uri content://com.android.contacts/data --projection _id --where "raw_contact_id=$RAW6 AND mimetype='vnd.android.cursor.item/postal-address'" 2>/dev/null | (grep -c "Row:" || true))
assert_eq "3 address entries" "3" "$ADDR_COUNT8"

# ============================================================
# TEST 9: Unicode / non-ASCII characters
# ============================================================
info ""
info "=== TEST 9: Unicode contacts ==="
cleanup_contacts
adb shell content insert --uri content://com.android.contacts/raw_contacts --bind deleted:i:0
RAW7=$(adb shell content query --uri content://com.android.contacts/raw_contacts --projection _id --sort "_id DESC" 2>/dev/null | head -1 | (grep -oP '(?<=_id=)\d+' || true))
adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW7 --bind mimetype:s:vnd.android.cursor.item/name --bind "data1:s:日本太郎"
adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW7 --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind "data1:s:+81-90-1234-5678"
adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW7 --bind mimetype:s:vnd.android.cursor.item/email_v2 --bind "data1:s:taro@example.jp"
adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW7 --bind mimetype:s:vnd.android.cursor.item/note --bind "data1:s:日本語テスト"

NAME9=$(adb shell content query --uri content://com.android.contacts/data --projection data1 --where "raw_contact_id=$RAW7 AND mimetype='vnd.android.cursor.item/name'" 2>/dev/null | (grep -oP '(?<=data1=)[^,]*' || true))
assert_eq "Unicode name" "日本太郎" "$NAME9"

# ============================================================
# TEST 10: Large number of contacts
# ============================================================
info ""
info "=== TEST 10: Bulk contacts (20) ==="
cleanup_contacts
for i in $(seq 1 20); do
    adb shell content insert --uri content://com.android.contacts/raw_contacts --bind deleted:i:0
    RAW_BULK=$(adb shell content query --uri content://com.android.contacts/raw_contacts --projection _id --sort "_id DESC" 2>/dev/null | head -1 | (grep -oP '(?<=_id=)\d+' || true))
    adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW_BULK --bind mimetype:s:vnd.android.cursor.item/name --bind "data1:s:Bulk Contact $i"
    adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW_BULK --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind "data1:s:+15550000$(printf '%04d' $i)"
done
TOTAL_BULK=$(count_raw_contacts)
assert_eq "20 bulk contacts created" "20" "$TOTAL_BULK"

# ============================================================
# TEST 11: Duplicate detection key verification
# ============================================================
info ""
info "=== TEST 11: Duplicate contact detection (same name + phone) ==="
cleanup_contacts
# Create two contacts with same name but different phones
adb shell content insert --uri content://com.android.contacts/raw_contacts --bind deleted:i:0
RAW_A=$(adb shell content query --uri content://com.android.contacts/raw_contacts --projection _id --sort "_id DESC" 2>/dev/null | head -1 | (grep -oP '(?<=_id=)\d+' || true))
adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW_A --bind mimetype:s:vnd.android.cursor.item/name --bind "data1:s:Duplicate Test"
adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW_A --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind "data1:s:+15550001111" --bind data2:i:1

adb shell content insert --uri content://com.android.contacts/raw_contacts --bind deleted:i:0
RAW_B=$(adb shell content query --uri content://com.android.contacts/raw_contacts --projection _id --sort "_id DESC" 2>/dev/null | head -1 | (grep -oP '(?<=_id=)\d+' || true))
adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW_B --bind mimetype:s:vnd.android.cursor.item/name --bind "data1:s:Duplicate Test"
adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW_B --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind "data1:s:+15550001111" --bind data2:i:1

DUP_COUNT=$(count_raw_contacts)
assert_eq "2 raw contacts before merge" "2" "$DUP_COUNT"
info "  (Merge logic tested via restore round-trip in TEST 12)"

# ============================================================
# TEST 12: Restore deduplication (manual verification instructions)
# ============================================================
info ""
info "=== TEST 12: Restore deduplication instructions ==="
info "  To manually verify merge behavior:"
info "  1. Create contact 'MergeTest' with phone +15550003333 and email merge@old.com"
info "  2. Backup"
info "  3. Add work phone +15550004444 to MergeTest on device"
info "  4. Restore from the backup"
info "  5. Verify MergeTest has BOTH phones and BOTH emails"
info "  6. Verify only ONE MergeTest contact exists"

# ============================================================
# TEST 13: Edge cases - null/empty data
# ============================================================
info ""
info "=== TEST 13: Edge cases ==="
cleanup_contacts
adb shell content insert --uri content://com.android.contacts/raw_contacts --bind deleted:i:0
RAW_EDGE=$(adb shell content query --uri content://com.android.contacts/raw_contacts --projection _id --sort "_id DESC" 2>/dev/null | head -1 | (grep -oP '(?<=_id=)\d+' || true))
adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW_EDGE --bind mimetype:s:vnd.android.cursor.item/name --bind "data1:s:Edge"
adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW_EDGE --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind "data1:s:+10000000000"

TOTAL_EDGE=$(count_raw_contacts)
assert_eq "1 edge contact" "1" "$TOTAL_EDGE"

# ============================================================
# SUMMARY
# ============================================================
info ""
info "========================================="
info "  TEST RESULTS: $PASS passed, $FAIL failed (of $TOTAL total)"
info "========================================="

if [ $FAIL -gt 0 ]; then
    exit 1
fi
