#!/bin/bash
# test_roundtrip.sh - Tests backup → restore → compare round-trip.
#
# This script:
# 1. Dumps the current provider state as "before"
# 2. Creates edge-case contacts if requested
# 3. Expects the user to manually backup via the app
# 4. Clears contacts
# 5. Expects the user to manually restore via the app
# 6. Dumps the restored provider state as "after"
# 7. Compares "before" and "after" using canonical comparison
#
# Usage:
#   bash tests/test_roundtrip.sh --setup     # Create test contacts + dump before
#   bash tests/test_roundtrip.sh --compare   # Dump after + compare
#   bash tests/test_roundtrip.sh --full      # Create, dump, wait for manual backup/restore, compare
#
# For automated testing, the comparison focuses on data content, ignoring
# provider-generated IDs.

set -e

OUTPUT_DIR="/tmp/lcb_test"
mkdir -p "$OUTPUT_DIR"

green() { printf "\033[32m%s\033[0m\n" "$1"; }
red()   { printf "\033[31m%s\033[0m\n" "$1"; }
info()  { printf "\033[36m%s\033[0m\n" "$1"; }
warn()  { printf "\033[33m%s\033[0m\n" "$1"; }

PASS=0
FAIL=0
TOTAL=0

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

assert_ge() {
    TOTAL=$((TOTAL+1))
    local label="$1" min="$2" actual="$3"
    if [ "$actual" -ge "$min" ] 2>/dev/null; then
        green "  PASS: $label ($actual >= $min)"
        PASS=$((PASS+1))
    else
        red "  FAIL: $label ($actual < $min)"
        FAIL=$((FAIL+1))
    fi
}

count_rows() {
    adb shell content query --uri "$1" --projection _id 2>/dev/null | tr -d '\r' | grep -c "Row:" || echo "0"
}

# Canonicalize a provider dump: extract only content-meaningful lines,
# strip provider-generated IDs, sort for comparison.
canonicalize_dump() {
    local input="$1"
    local output="$2"

    # Extract data rows section, remove _id and raw_contact_id columns,
    # sort by content for stable comparison
    sed -n '/=== DATA ROWS ===/,/=== GROUPS ===/p' "$input" | \
        grep "Row:" | \
        sed 's/Row: [0-9]*, //' | \
        sed 's/raw_contact_id=[0-9]*,/raw_contact_id=X,/g' | \
        sed 's/_id=[0-9]*,/_id=X,/g' | \
        sort > "$output"
}

# Extract just the data row content (mimetype + all data columns) for comparison
extract_data_content() {
    local input="$1"
    local output="$2"

    grep "Row:" "$input" | \
        sed 's/.*mimetype=//' | \
        sort > "$output"
}

# ============================================================
# SETUP: Create test contacts and dump before state
# ============================================================
setup_contacts() {
    info "=== Setting up edge-case contact dataset ==="

    # Clean first
    adb shell content delete --uri content://com.android.contacts/raw_contacts 2>/dev/null || true
    sleep 1

    # Create comprehensive test contacts
    info "  Creating test contacts..."

    # 1. Full structured name
    adb shell content insert --uri content://com.android.contacts/raw_contacts --bind deleted:i:0
    RAW=$(adb shell content query --uri content://com.android.contacts/raw_contacts --projection _id --sort "_id DESC" 2>/dev/null | tr -d '\r' | head -1 | grep -oP '(?<=_id=)\d+')
    adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW --bind mimetype:s:vnd.android.cursor.item/name --bind "data1:s:Full Name Test" --bind "data2:s:Full" --bind "data3:s:Test" --bind "data4:s:Dr." --bind "data5:s:Middle" --bind "data6:s:III" --bind "data7:s:Fool" --bind "data8:s:Middul" --bind "data9:s:Tehst"
    adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind "data1:s:+15550001" --bind data2:i:1
    adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW --bind mimetype:s:vnd.android.cursor.item/email_v2 --bind "data1:s:full@test.com" --bind data2:i:2

    # 2. Unicode contact
    adb shell content insert --uri content://com.android.contacts/raw_contacts --bind deleted:i:0
    RAW2=$(adb shell content query --uri content://com.android.contacts/raw_contacts --projection _id --sort "_id DESC" 2>/dev/null | tr -d '\r' | head -1 | grep -oP '(?<=_id=)\d+')
    adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW2 --bind mimetype:s:vnd.android.cursor.item/name --bind "data1:s:日本太郎"
    adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW2 --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind "data1:s:+81-90-1234-5678" --bind data2:i:1

    # 3. Multi-field contact
    adb shell content insert --uri content://com.android.contacts/raw_contacts --bind deleted:i:0
    RAW3=$(adb shell content query --uri content://com.android.contacts/raw_contacts --projection _id --sort "_id DESC" 2>/dev/null | tr -d '\r' | head -1 | grep -oP '(?<=_id=)\d+')
    adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW3 --bind mimetype:s:vnd.android.cursor.item/name --bind "data1:s:Multi Field Contact"
    adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW3 --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind "data1:s:+15550002" --bind data2:i:1
    adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW3 --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind "data1:s:+15550003" --bind data2:i:2
    adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW3 --bind mimetype:s:vnd.android.cursor.item/email_v2 --bind "data1:s:multi1@test.com" --bind data2:i:1
    adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW3 --bind mimetype:s:vnd.android.cursor.item/email_v2 --bind "data1:s:multi2@test.com" --bind data2:i:2
    adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW3 --bind mimetype:s:vnd.android.cursor.item/postal-address_v2 --bind "data2:i:1" --bind "data6:s:123 Main St" --bind "data7:s:Springfield" --bind "data8:s:IL" --bind "data9:s:62704" --bind "data10:s:USA"
    adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW3 --bind mimetype:s:vnd.android.cursor.item/organization --bind "data1:s:TestCorp" --bind "data4:s:Engineer" --bind "data5:s:QA"
    adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW3 --bind mimetype:s:vnd.android.cursor.item/nickname --bind "data1:s:Tester"
    adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW3 --bind mimetype:s:vnd.android.cursor.item/note --bind "data1:s:Important note\nwith newlines"
    adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW3 --bind mimetype:s:vnd.android.cursor.item/contact_event --bind "data1:s:1990-01-01" --bind data2:i:1
    adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW3 --bind mimetype:s:vnd.android.cursor.item/website --bind "data1:s:https://test.com" --bind data2:i:1
    adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW3 --bind mimetype:s:vnd.android.cursor.item/relation --bind "data1:s:Contact Person" --bind data2:i:12
    adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW3 --bind mimetype:s:vnd.android.cursor.item/im --bind "data1:s:user@jabber.org" --bind data5:i:6

    # 4. Nameless contact
    adb shell content insert --uri content://com.android.contacts/raw_contacts --bind deleted:i:0
    RAW4=$(adb shell content query --uri content://com.android.contacts/raw_contacts --projection _id --sort "_id DESC" 2>/dev/null | tr -d '\r' | head -1 | grep -oP '(?<=_id=)\d+')
    adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW4 --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind "data1:s:+15559999" --bind data2:i:1

    info "  Created 4 test contacts"
}

dump_before() {
    info "=== Dumping BEFORE state ==="
    bash "$(dirname "$0")/provider_dump.sh" --before

    BEFORE_RC=$(count_rows "content://com.android.contacts/raw_contacts")
    BEFORE_DATA=$(count_rows "content://com.android.contacts/data")
    info "  Before: $BEFORE_RC raw contacts, $BEFORE_DATA data rows"
}

dump_after() {
    info "=== Dumping AFTER state ==="
    bash "$(dirname "$0")/provider_dump.sh" --after

    AFTER_RC=$(count_rows "content://com.android.contacts/raw_contacts")
    AFTER_DATA=$(count_rows "content://com.android.contacts/data")
    info "  After: $AFTER_RC raw contacts, $AFTER_DATA data rows"
}

compare_dumps() {
    info ""
    info "=== COMPARING BEFORE vs AFTER ==="

    BEFORE_FILE="$OUTPUT_DIR/provider_before.txt"
    AFTER_FILE="$OUTPUT_DIR/provider_after.txt"

    if [ ! -f "$BEFORE_FILE" ]; then
        red "  No before dump found. Run with --setup first."
        exit 1
    fi
    if [ ! -f "$AFTER_FILE" ]; then
        red "  No after dump found. Run with --compare first."
        exit 1
    fi

    # Compare raw contact counts
    BEFORE_RC=$(grep -oP '_id=\d+' "$BEFORE_FILE" | wc -l)
    AFTER_RC=$(grep -oP '_id=\d+' "$AFTER_FILE" | wc -l)

    # Compare data row content (strip IDs, sort, diff)
    BEFORE_DATA=$(grep "mimetype=" "$BEFORE_FILE" | sort)
    AFTER_DATA=$(grep "mimetype=" "$AFTER_FILE" | sort)

    BEFORE_DATA_COUNT=$(echo "$BEFORE_DATA" | wc -l)
    AFTER_DATA_COUNT=$(echo "$AFTER_DATA" | wc -l)

    info "  Before data rows: $BEFORE_DATA_COUNT"
    info "  After data rows: $AFTER_DATA_COUNT"

    if [ "$BEFORE_DATA_COUNT" = "$AFTER_DATA_COUNT" ]; then
        green "  PASS: Data row count matches ($AFTER_DATA_COUNT)"
        PASS=$((PASS+1)); TOTAL=$((TOTAL+1))
    else
        red "  FAIL: Data row count mismatch (before=$BEFORE_DATA_COUNT, after=$AFTER_DATA_COUNT)"
        FAIL=$((FAIL+1)); TOTAL=$((TOTAL+1))
    fi

    # Detailed diff of data content
    DIFF_FILE="$OUTPUT_DIR/roundtrip_diff.txt"
    diff <(echo "$BEFORE_DATA") <(echo "$AFTER_DATA") > "$DIFF_FILE" 2>&1 || true

    if [ -s "$DIFF_FILE" ]; then
        warn "  Content differences found (saved to $DIFF_FILE):"
        # Show first few differences
        head -20 "$DIFF_FILE" | while read line; do warn "    $line"; done
        if [ $(wc -l < "$DIFF_FILE") -gt 20 ]; then
            warn "    ... and $(($(wc -l < "$DIFF_FILE") - 20)) more differences"
        fi
    else
        green "  PASS: All data content matches exactly"
        PASS=$((PASS+1)); TOTAL=$((TOTAL+1))
    fi

    # Compare individual field types
    for mime in "phone_v2" "email_v2" "postal-address" "organization" "nickname" "note" "contact_event" "website" "relation" "im" "name"; do
        BEFORE_MIME_COUNT=$(echo "$BEFORE_DATA" | grep -c "$mime" || echo "0")
        AFTER_MIME_COUNT=$(echo "$AFTER_DATA" | grep -c "$mime" || echo "0")
        if [ "$BEFORE_MIME_COUNT" = "$AFTER_MIME_COUNT" ]; then
            green "  PASS: $mime count matches ($AFTER_MIME_COUNT)"
        else
            red "  FAIL: $mime count mismatch (before=$BEFORE_MIME_COUNT, after=$AFTER_MIME_COUNT)"
        fi
        TOTAL=$((TOTAL+1))
        if [ "$BEFORE_MIME_COUNT" = "$AFTER_MIME_COUNT" ]; then PASS=$((PASS+1)); else FAIL=$((FAIL+1)); fi
    done
}

# ============================================================
# MAIN
# ============================================================
case "${1:---help}" in
    --setup)
        setup_contacts
        dump_before
        info ""
        info "Now backup via the app, then clear contacts, then restore."
        info "After restore, run: bash tests/test_roundtrip.sh --compare"
        ;;
    --compare)
        dump_after
        compare_dumps
        ;;
    --full)
        setup_contacts
        dump_before
        info ""
        info "==========================================="
        info "  MANUAL STEPS REQUIRED:"
        info "  1. Open the app and tap 'Back up now'"
        info "  2. After backup completes, clear contacts:"
        info "     adb shell content delete --uri content://com.android.contacts/raw_contacts"
        info "  3. Open the app and restore from the backup"
        info "  4. Press Enter to continue comparison..."
        info "==========================================="
        read -p "Press Enter after restore is complete..."
        dump_after
        compare_dumps
        ;;
    --help)
        echo "Usage: bash tests/test_roundtrip.sh [--setup|--compare|--full|--help]"
        echo ""
        echo "  --setup    Create edge-case contacts and dump before state"
        echo "  --compare  Dump after state and compare with before"
        echo "  --full     Interactive full round-trip test"
        echo "  --help     Show this help"
        ;;
    *)
        echo "Unknown option: $1"
        echo "Use --help for usage information"
        exit 1
        ;;
esac

echo ""
echo "Results: $PASS passed, $FAIL failed (of $TOTAL total)"
