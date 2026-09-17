package com.ashkanrafiee.librecontactsbackup.snapshot;

/**
 * Where the user wants SIM card contacts from a backup to go on a given
 * restore. Chosen per-restore in the restore-selection dialog, since the
 * right answer depends on the target device and the SIM currently in it.
 */
public enum SimRestoreDestination {
    /** Restore as ordinary contacts in the device address book. */
    DEVICE,
    /** Write back to the SIM card phonebook. */
    SIM_CARD,
    /** Write to the SIM card AND add as device contacts. */
    BOTH
}