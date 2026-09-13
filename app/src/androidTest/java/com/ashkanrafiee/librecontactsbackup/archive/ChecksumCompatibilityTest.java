package com.ashkanrafiee.librecontactsbackup.archive;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactsSnapshot;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Guards the checksum back-compatibility fix: releases v2.0.0-v2.4.x rendered
 * manifest digests with the signed byte ({@code String.format("%02x", b)},
 * producing {@code ffffffXX} for bytes {@code >= 0x80}). Archives written by
 * those releases must still validate today. The canonical rendering is verified
 * against a known SHA-256 test vector so this isn't circular.
 */
@RunWith(AndroidJUnit4.class)
public class ChecksumCompatibilityTest {

    /** Well-known SHA-256("abc") = ba7816bf...; anchors the canonical renderer. */
    private static final String SHA256_ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    public void canonicalRendering_matchesKnownTestVector() {
        byte[] data = "abc".getBytes(StandardCharsets.US_ASCII);
        assertEquals(SHA256_ABC, BackupArchiveWriter.sha256(data));
    }

    @Test
    public void legacyAndCanonicalRenderings_differWhenDigestHasHighBytes() throws Exception {
        byte[] data = digestWithHighByte();
        assertNotEquals("fixture must exercise the signed-byte bug",
                BackupArchiveWriter.sha256(data), BackupArchiveWriter.sha256Legacy(data));
    }

    @Test
    public void acceptsSha256_acceptsBothRenderingsAndRejectsFakes() throws Exception {
        byte[] data = digestWithHighByte();
        String canonical = BackupArchiveWriter.sha256(data);
        String legacy = BackupArchiveWriter.sha256Legacy(data);

        assertTrue(BackupArchiveWriter.acceptsSha256(data, canonical));
        assertTrue(BackupArchiveWriter.acceptsSha256(data, legacy));
        assertTrue(BackupArchiveWriter.acceptsSha256(data, canonical.toUpperCase()));
        assertFalse(BackupArchiveWriter.acceptsSha256(data, zeros(canonical.length())));
        assertFalse(BackupArchiveWriter.acceptsSha256(data, null));

        byte[] other = "def".getBytes(StandardCharsets.US_ASCII);
        assertFalse(BackupArchiveWriter.acceptsSha256(data, BackupArchiveWriter.sha256(other)));
    }

    /** A v2.0.0-v2.4.x-style archive (legacy digests in the manifest) still reads
     *  back through the production reader and passes integrity verification. */
    @Test
    public void legacyRenderedManifest_archiveStillValidates() throws Exception {
        byte[] original = buildArchive();
        Map<String, byte[]> entries = unzip(original);
        // Rust the manifest's digests into the legacy rendering, exactly as the
        // buggy released writers did.
        JSONObject manifest = new JSONObject(new String(entries.get("manifest.json"), StandardCharsets.UTF_8));
        JSONObject files = manifest.getJSONObject("files");
        java.util.Iterator<String> keys = files.keys();
        while (keys.hasNext()) {
            String name = keys.next();
            byte[] content = entries.get(name);
            if (content == null) continue;
            files.getJSONObject(name).put("sha256", BackupArchiveWriter.sha256Legacy(content));
        }

        byte[] legacyArchive = rezip(entries, "manifest.json", manifest.toString(2).getBytes(StandardCharsets.UTF_8));
        BackupArchiveReader.ArchiveData data = BackupArchiveReader.readArchive(new ByteArrayInputStream(legacyArchive));
        assertTrue("archive written with legacy (v2.0.0-v2.4.x) digests must still validate", data.checksumValid);
        assertFalse(data.isLegacy);
    }

    @Test
    public void tamperedLegacyRendering_isStillRejected() throws Exception {
        byte[] original = buildArchive();
        Map<String, byte[]> entries = unzip(original);
        JSONObject manifest = new JSONObject(new String(entries.get("manifest.json"), StandardCharsets.UTF_8));
        JSONObject files = manifest.getJSONObject("files");
        java.util.Iterator<String> keys = files.keys();
        String target = null;
        while (keys.hasNext()) {
            String name = keys.next();
            if (entries.get(name) == null) continue;
            target = name;
            break;
        }
        assertTrue(target != null);
        files.getJSONObject(target).put("sha256", zeros(BackupArchiveWriter.sha256(new byte[]{1}).length()));

        byte[] tampered = rezip(entries, "manifest.json", manifest.toString(2).getBytes(StandardCharsets.UTF_8));
        BackupArchiveReader.ArchiveData data = BackupArchiveReader.readArchive(new ByteArrayInputStream(tampered));
        assertFalse("a checksum that stays wrong in the legacy form must still fail", data.checksumValid);
    }

    @Test
    public void readBoundConstants_areConsistent() {
        // Guards against accidental weakening of the decompression-bomb bound.
        // readAll() refuses a raw archive larger than this before parsing it;
        // actually feeding 500MB+ to prove the throw would risk OOMing the
        // emulator's test process, so pin the boundary value instead.
        assertEquals(500L * 1024 * 1024 + 8L * 1024 * 1024, BackupArchiveReader.MAX_RAW_SIZE);
    }

    private static String zeros(int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) sb.append('0');
        return sb.toString();
    }

    private static byte[] digestWithHighByte() throws Exception {
        // Find deterministic content whose SHA-256 digest contains a byte >= 0x80,
        // so the legacy rendering is provably different from the canonical one.
        for (int i = 0; i < 10_000; i++) {
            byte[] candidate = ("seed-" + i).getBytes(StandardCharsets.US_ASCII);
            if (!BackupArchiveWriter.sha256(candidate).equals(BackupArchiveWriter.sha256Legacy(candidate))) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not construct a digest with a high byte");
    }

    private static byte[] buildArchive() throws Exception {
        AndroidContactsSnapshot snapshot = new AndroidContactsSnapshot();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BackupArchiveWriter.writeArchive(context(), snapshot, out);
        return out.toByteArray();
    }

    private static Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private static Map<String, byte[]> unzip(byte[] archive) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] tmp = new byte[8192];
                int n;
                while ((n = zip.read(tmp)) > 0) buf.write(tmp, 0, n);
                entries.put(entry.getName(), buf.toByteArray());
                zip.closeEntry();
            }
        }
        return entries;
    }

    private static byte[] rezip(Map<String, byte[]> entries, String overriddenName, byte[] overriddenContent) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(overriddenName.equals(e.getKey()) ? overriddenContent : e.getValue());
                zip.closeEntry();
            }
            zip.finish();
        }
        return out.toByteArray();
    }
}