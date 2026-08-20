#!/system/bin/sh
# Device-side test helper script for Libre Contacts Backup testing
# Usage: adb push tests/device_helper.sh /data/local/tmp/ && adb sh /data/local/tmp/device_helper.sh <command> [args...]

CONTENT=/system/bin/content
URI_RAW="content://com.android.contacts/raw_contacts"
URI_DATA="content://com.android.contacts/data"

case "$1" in
    clean)
        $CONTENT delete --uri "$URI_RAW" 2>/dev/null
        echo "0"
        ;;

    count)
        $CONTENT query --uri "$URI_RAW" --projection _id 2>/dev/null | sed '/^$/d' | wc -l
        ;;

    insert-raw)
        $CONTENT insert --uri "$URI_RAW" --bind account_type:n: 2>/dev/null
        $CONTENT query --uri "$URI_RAW" --projection _id --sort "_id DESC" 2>/dev/null | head -1 | sed 's/.*_id=//' | sed 's/,.*//'
        ;;

    insert-name)
        $CONTENT insert --uri "$URI_DATA" --bind raw_contact_id:i:$2 --bind mimetype:s:vnd.android.cursor.item/name --bind "data1:s:$3" 2>/dev/null
        echo "ok"
        ;;

    insert-phone)
        $CONTENT insert --uri "$URI_DATA" --bind raw_contact_id:i:$2 --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind "data1:s:$3" --bind data2:i:$4 2>/dev/null
        echo "ok"
        ;;

    insert-email)
        $CONTENT insert --uri "$URI_DATA" --bind raw_contact_id:i:$2 --bind mimetype:s:vnd.android.cursor.item/email_v2 --bind "data1:s:$3" --bind data2:i:$4 2>/dev/null
        echo "ok"
        ;;

    insert-org)
        $CONTENT insert --uri "$URI_DATA" --bind raw_contact_id:i:$2 --bind mimetype:s:vnd.android.cursor.item/organization --bind "data1:s:$3" --bind "data4:s:$4" 2>/dev/null
        echo "ok"
        ;;

    insert-note)
        $CONTENT insert --uri "$URI_DATA" --bind raw_contact_id:i:$2 --bind mimetype:s:vnd.android.cursor.item/note --bind "data1:s:$3" 2>/dev/null
        echo "ok"
        ;;

    insert-nick)
        $CONTENT insert --uri "$URI_DATA" --bind raw_contact_id:i:$2 --bind mimetype:s:vnd.android.cursor.item/nickname --bind "data1:s:$3" 2>/dev/null
        echo "ok"
        ;;

    insert-event)
        $CONTENT insert --uri "$URI_DATA" --bind raw_contact_id:i:$2 --bind mimetype:s:vnd.android.cursor.item/contact_event --bind "data1:s:$3" --bind data2:i:$4 2>/dev/null
        echo "ok"
        ;;

    insert-web)
        $CONTENT insert --uri "$URI_DATA" --bind raw_contact_id:i:$2 --bind mimetype:s:vnd.android.cursor.item/website --bind "data1:s:$3" --bind data2:i:$4 2>/dev/null
        echo "ok"
        ;;

    insert-rel)
        $CONTENT insert --uri "$URI_DATA" --bind raw_contact_id:i:$2 --bind mimetype:s:vnd.android.cursor.item/relation --bind "data1:s:$3" --bind data2:i:$4 2>/dev/null
        echo "ok"
        ;;

    insert-im)
        $CONTENT insert --uri "$URI_DATA" --bind raw_contact_id:i:$2 --bind mimetype:s:vnd.android.cursor.item/im --bind "data1:s:$3" --bind data5:i:$4 2>/dev/null
        echo "ok"
        ;;

    insert-addr)
        $CONTENT insert --uri "$URI_DATA" --bind raw_contact_id:i:$2 --bind mimetype:s:vnd.android.cursor.item/postal-address --bind "data1:s:$3 $4" --bind data2:i:$7 --bind "data4:s:$3" --bind "data7:s:$4" --bind "data8:s:$5" --bind "data9:s:$6" --bind "data10:s:$7" 2>/dev/null
        echo "ok"
        ;;

    query-name)
        $CONTENT query --uri "$URI_DATA" --projection data1 --where "raw_contact_id=$2 AND mimetype='vnd.android.cursor.item/name'" 2>/dev/null | sed 's/.*data1=//' | sed 's/,.*//'
        ;;

    query-phones)
        $CONTENT query --uri "$URI_DATA" --projection data1 --where "raw_contact_id=$2 AND mimetype='vnd.android.cursor.item/phone_v2'" 2>/dev/null | sed 's/.*data1=//' | sed 's/,.*//' | tr '\n' '|'
        ;;

    query-emails)
        $CONTENT query --uri "$URI_DATA" --projection data1 --where "raw_contact_id=$2 AND mimetype='vnd.android.cursor.item/email_v2'" 2>/dev/null | sed 's/.*data1=//' | sed 's/,.*//' | tr '\n' '|'
        ;;

    query-addr-street)
        $CONTENT query --uri "$URI_DATA" --projection data4 --where "raw_contact_id=$2 AND mimetype='vnd.android.cursor.item/postal-address'" 2>/dev/null | sed 's/.*data4=//' | sed 's/,.*//' | tr '\n' '|'
        ;;

    query-count)
        $CONTENT query --uri "$URI_DATA" --projection _id --where "raw_contact_id=$2 AND mimetype='$3'" 2>/dev/null | sed '/^$/d' | wc -l
        ;;

    query-all-data)
        $CONTENT query --uri "$URI_DATA" --projection data1:data2:data4 --where "raw_contact_id=$2" 2>/dev/null
        ;;

    list-names)
        $CONTENT query --uri "$URI_DATA" --projection raw_contact_id:data1 --where "mimetype='vnd.android.cursor.item/name'" --sort "data1 ASC" 2>/dev/null | sed 's/.*raw_contact_id=//' | sed 's/,.*data1=/\t/'
        ;;

    *)
        echo "Usage: device_helper.sh <command> [args]"
        ;;
esac
