package com.ashkanrafiee.librecontactsbackup.retention;

import java.time.LocalDateTime;

/**
 * A stored backup set, identified by the base of its filename(s) and the
 * wall-clock time encoded in that name. The timestamp is what retention
 * decisions are based on.
 */
public final class StoredBackup {
    public final String id;
    public final LocalDateTime at;

    public StoredBackup(String id, LocalDateTime at) {
        this.id = id;
        this.at = at;
    }
}