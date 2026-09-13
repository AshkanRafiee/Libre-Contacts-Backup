package com.ashkanrafiee.librecontactsbackup.retention;

/**
 * Configuration describing which backup sets are kept and for how long.
 *
 * Two modes are supported:
 *  - SIMPLE keeps at most {@code simpleKeep} of the most recent sets (the
 *    historical behavior; a value above {@link #KEEP_ALL} keeps everything).
 *  - PERIODIC ("smart" retention) keeps the newest set of each of the most
 *    recent days, weeks, and months, with {@code dailyKeep}, {@code weeklyKeep}
 *    and {@code monthlyKeep} limiting how far back each tier reaches. A count
 *    of zero disables that tier.
 */
public final class RetentionPolicy {
    public enum Mode { SIMPLE, PERIODIC }

    /** Sentinel used to express "keep everything" in SIMPLE mode (legacy value). */
    public static final int KEEP_ALL = 9999;
    public static final int DEFAULT_KEEP = 5;
    public static final int DEFAULT_DAILY = 7;
    public static final int DEFAULT_WEEKLY = 4;
    public static final int DEFAULT_MONTHLY = 3;

    public final Mode mode;
    public final int simpleKeep;
    public final int dailyKeep;
    public final int weeklyKeep;
    public final int monthlyKeep;

    public RetentionPolicy(Mode mode, int simpleKeep, int dailyKeep, int weeklyKeep, int monthlyKeep) {
        this.mode = mode;
        this.simpleKeep = Math.max(1, simpleKeep);
        this.dailyKeep = Math.max(0, dailyKeep);
        this.weeklyKeep = Math.max(0, weeklyKeep);
        this.monthlyKeep = Math.max(0, monthlyKeep);
    }

    public boolean keepAll() { return mode == Mode.SIMPLE && simpleKeep > 100; }

    public static RetentionPolicy defaults() {
        return new RetentionPolicy(Mode.SIMPLE, DEFAULT_KEEP, DEFAULT_DAILY, DEFAULT_WEEKLY, DEFAULT_MONTHLY);
    }
}