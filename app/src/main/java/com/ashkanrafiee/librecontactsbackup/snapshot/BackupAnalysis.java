package com.ashkanrafiee.librecontactsbackup.snapshot;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * A pre-restore summary of what a decoded backup snapshot contains, shown to
 * the user before they choose what to restore (spec section 8).
 *
 * {@link #categoryCounts} gives a concrete "how much data is in this
 * category" figure per {@link RestoreCategory}, shown next to each checkbox
 * in the restore-selection dialog so the choice isn't abstract.
 */
public final class BackupAnalysis {

    public int contactCount;
    public int rawContactCount;
    public int dataRowCount;

    final EnumMap<RestoreCategory, Integer> categoryCounts = new EnumMap<>(RestoreCategory.class);

    public int countFor(RestoreCategory category) {
        Integer count = categoryCounts.get(category);
        return count != null ? count : 0;
    }

    public Map<RestoreCategory, Integer> getCategoryCounts() {
        return Collections.unmodifiableMap(categoryCounts);
    }
}
