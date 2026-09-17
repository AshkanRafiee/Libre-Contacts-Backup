package com.ashkanrafiee.librecontactsbackup.snapshot;

/**
 * Choice of which SIM card a restore writes SIM contacts to.
 *
 * {@link #ORIGINAL_CARDS} pins each entry to the card it was captured from
 * (a multi-SIM phone always keeps every entry on its own card). A concrete
 * subscription id redirects every SIM entry to that single card — the way to
 * move backed-up contacts onto a newly acquired SIM.
 */
public final class SimTarget {

    /** Restore each SIM entry to the card it was backed up from. */
    public static final int ORIGINAL_CARDS = -1;

    private SimTarget() {}
}