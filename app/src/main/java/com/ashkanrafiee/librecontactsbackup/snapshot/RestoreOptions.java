package com.ashkanrafiee.librecontactsbackup.snapshot;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The set of {@link RestoreCategory} values the user chose to materialize
 * into the target Contacts Provider for a given restore run.
 *
 * This never affects what is stored inside a .lcb archive — it only governs
 * what {@link com.ashkanrafiee.librecontactsbackup.archive.ContactsSnapshotRestorer}
 * writes to the provider on this particular restore. The same backup can be
 * restored again later with a different {@link RestoreOptions}.
 */
public final class RestoreOptions {

    private final EnumSet<RestoreCategory> selected;

    private RestoreOptions(EnumSet<RestoreCategory> selected) {
        this.selected = selected;
    }

    /** Every category selected. */
    public static RestoreOptions all() {
        return new RestoreOptions(EnumSet.allOf(RestoreCategory.class));
    }

    /** Only the categories flagged {@link RestoreCategory#recommended} — the restore-dialog default. */
    public static RestoreOptions recommended() {
        EnumSet<RestoreCategory> set = EnumSet.noneOf(RestoreCategory.class);
        for (RestoreCategory category : RestoreCategory.values()) {
            if (category.recommended) set.add(category);
        }
        return new RestoreOptions(set);
    }

    public static RestoreOptions of(RestoreCategory... categories) {
        EnumSet<RestoreCategory> set = EnumSet.noneOf(RestoreCategory.class);
        Collections.addAll(set, categories);
        return new RestoreOptions(set);
    }

    public static RestoreOptions of(Set<RestoreCategory> categories) {
        return new RestoreOptions(categories.isEmpty()
                ? EnumSet.noneOf(RestoreCategory.class) : EnumSet.copyOf(categories));
    }

    public boolean includes(RestoreCategory category) {
        return selected.contains(category);
    }

    public Set<RestoreCategory> selectedCategories() {
        return Collections.unmodifiableSet(selected);
    }
}
