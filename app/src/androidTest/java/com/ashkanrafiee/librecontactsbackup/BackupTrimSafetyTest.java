package com.ashkanrafiee.librecontactsbackup;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Guard-rail tests for {@link BackupManager}'s archive-name classification,
 * which drives what retention trimming may or may not delete. The whole point
 * is that names nobody asked us to own — plain user files, folders, exports,
 * or anything merely prefixed "librecontacts_" — are never deletable by trim.
 */
@RunWith(AndroidJUnit4.class)
public class BackupTrimSafetyTest {

    @Test public void recognizesEncryptedAndPlainArchives() {
        assertTrue(BackupManager.isOwnArchiveName("librecontacts_2026-09-13_10-00-00.lcb"));
        assertTrue(BackupManager.isOwnArchiveName("librecontacts_2026-09-13_10-00-00.lcb.enc"));
    }

    @Test public void ignoresMissingPrefix() {
        assertFalse(BackupManager.isOwnArchiveName("librecontacts-backup_2026-09-13_10-00-00.lcb"));
        assertFalse(BackupManager.isOwnArchiveName("my_librecontacts_2026-09-13_10-00-00.lcb"));
        assertFalse(BackupManager.isOwnArchiveName("2026-09-13_10-00-00.lcb"));
        assertFalse(BackupManager.isOwnArchiveName(null));
    }

    @Test public void ignoresForeignSuffixes() {
        assertFalse(BackupManager.isOwnArchiveName("librecontacts_2026-09-13_10-00-00.vcf"));
        assertFalse(BackupManager.isOwnArchiveName("librecontacts_2026-09-13_10-00-00.txt"));
        assertFalse(BackupManager.isOwnArchiveName("librecontacts_2026-09-13_10-00-00.enc"));
    }

    @Test public void ignoresAnyFileOrDirectoryMerelySharingThePrefix() {
        assertFalse(BackupManager.isOwnArchiveName("librecontacts_notes.txt"));
        assertFalse(BackupManager.isOwnArchiveName("librecontacts_folder"));
        assertFalse(BackupManager.isOwnArchiveName("librecontacts_2026-09-13_lcb"));
        assertFalse(BackupManager.isOwnArchiveName("librecontacts_lcb"));
    }

    @Test public void onlyTimestampedBasesSurviveTheKeepSet() {
        assertTrue(BackupManager.isTimestamped("librecontacts_2026-09-13_10-00-00"));
        assertTrue(BackupManager.isTimestamped("librecontacts_2026-01-01_00-00-00"));
        assertFalse(BackupManager.isTimestamped("librecontacts_notadate"));
        assertFalse(BackupManager.isTimestamped("librecontacts_"));
        assertFalse(BackupManager.isTimestamped(null));
    }
}