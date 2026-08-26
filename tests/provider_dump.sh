#!/bin/bash
# provider_dump.sh - Dumps a canonical representation of all contacts from the
# Android Contacts Provider for comparison purposes.
#
# Usage:
#   bash tests/provider_dump.sh [--before|--after]
#
# Dumps contacts, raw contacts, and data rows in a format suitable for
# automated comparison. This is the authoritative representation, NOT the UI.
#
# The output is sorted by stable content keys so that provider-generated
# IDs can be ignored during comparison.

set -e

MODE="${1:---before}"
OUTPUT_DIR="/tmp/lcb_test"
mkdir -p "$OUTPUT_DIR"

DUMP_FILE="$OUTPUT_DIR/provider_${MODE#--}.txt"

echo "=== Provider Dump ($MODE) ==="
echo "Dumping to $DUMP_FILE"

# Strip CR characters from adb shell output
adb_cmd() {
    adb shell "$@" 2>/dev/null | tr -d '\r'
}

{
    echo "=== CONTACTS ==="
    adb_cmd content query \
        --uri content://com.android.contacts/contacts \
        --projection "_id:display_name:photo_uri:starred:times_contacted:custom_ringtone:send_to_voicemail:has_phone_number" \
        --sort "display_name ASC" 2>/dev/null || true

    echo ""
    echo "=== RAW CONTACTS ==="
    adb_cmd content query \
        --uri content://com.android.contacts/raw_contacts \
        --projection "_id:contact_id:account_name:account_type:data_set:source_id:starred:times_contacted:custom_ringtone:send_to_voicemail:deleted" \
        --sort "contact_id ASC" 2>/dev/null || true

    echo ""
    echo "=== DATA ROWS ==="
    adb_cmd content query \
        --uri content://com.android.contacts/data \
        --projection "raw_contact_id:mimetype:data1:data2:data3:data4:data5:data6:data7:data8:data9:data10:data11:data12:data13:data14:is_primary:is_super_primary:data_version" \
        --sort "raw_contact_id ASC" 2>/dev/null || true

    echo ""
    echo "=== GROUPS ==="
    adb_cmd content query \
        --uri content://com.android.contacts/groups \
        --projection "_id:title:source_id:account_name:account_type:deleted" \
        --sort "title ASC" 2>/dev/null || true

    echo ""
    echo "=== RAW CONTACTS COUNT ==="
    adb_cmd content query \
        --uri content://com.android.contacts/raw_contacts \
        --projection "_id" 2>/dev/null | grep -c "Row:" || echo "0"

    echo ""
    echo "=== DATA ROWS COUNT ==="
    adb_cmd content query \
        --uri content://com.android.contacts/data \
        --projection "_id" 2>/dev/null | grep -c "Row:" || echo "0"

} > "$DUMP_FILE" 2>&1

echo "Dump complete: $DUMP_FILE"
echo "Raw contacts: $(grep -c "Row:.*_id=" "$OUTPUT_DIR/provider_${MODE#--}.txt" 2>/dev/null || echo 0)"
