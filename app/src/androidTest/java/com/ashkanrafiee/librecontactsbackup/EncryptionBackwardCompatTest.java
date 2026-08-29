package com.ashkanrafiee.librecontactsbackup;

import android.content.Context;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.ashkanrafiee.librecontactsbackup.archive.BackupArchiveReader;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactsSnapshot;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.security.SecureRandom;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Exercises BackupManager.openArchive against real .lcb.enc files written in every
 * format the app has ever produced, through the app's actual production code path
 * (no UI): a genuinely legacy pre-2.2.0 archive (v1 magic, 120k PBKDF2 iterations),
 * a v2.2.0-style archive (v1 magic, but already at 600k iterations since that
 * release raised the count without adding a header), and the current v2.3.0+
 * self-describing format (v2 magic, iteration count stored in the header). Also
 * confirms a wrong password is still rejected for every format, and that a v2
 * archive with a tampered header is rejected by GCM's AAD authentication rather
 * than silently accepted.
 */
@RunWith(AndroidJUnit4.class)
public class EncryptionBackwardCompatTest {

    private static final byte[] MAGIC_V1 = "LIBRECB1".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final String PASSWORD = "correct horse battery staple";

    private Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    /** A real, valid .lcb archive (built via the app's own writer) to encrypt under each format. */
    private byte[] realPlaintextArchive() throws Exception {
        AndroidContactsSnapshot snapshot = new AndroidContactsSnapshot();
        ByteArrayOutputStream zipOutput = new ByteArrayOutputStream();
        com.ashkanrafiee.librecontactsbackup.archive.BackupArchiveWriter.writeArchive(context(), snapshot, zipOutput);
        return zipOutput.toByteArray();
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] encryptV1(byte[] plaintext, String password, int iterations) throws Exception {
        byte[] salt = new byte[16], iv = new byte[12];
        SecureRandom random = new SecureRandom();
        random.nextBytes(salt);
        random.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(pbkdf2(password, salt, iterations), "AES"), new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(plaintext);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(MAGIC_V1);
        out.write(salt);
        out.write(iv);
        out.write(encrypted);
        return out.toByteArray();
    }

    private Uri writeToTempFile(byte[] bytes, String name) throws Exception {
        File file = new File(context().getCacheDir(), name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
        return Uri.fromFile(file);
    }

    @Test
    public void legacyPre220Archive_120kIterations_restoresSuccessfully() throws Exception {
        byte[] plaintext = realPlaintextArchive();
        byte[] archive = encryptV1(plaintext, PASSWORD, 120_000);
        Uri uri = writeToTempFile(archive, "legacy_120k.lcb.enc");

        assertTrue("isEncrypted() should recognize a v1-magic archive", BackupManager.isEncrypted(context(), uri));

        BackupArchiveReader.ArchiveData data = BackupManager.openArchive(context(), uri, PASSWORD, (m, c, t) -> {});
        assertTrue("checksum should validate after successful decryption", data.checksumValid);
    }

    @Test
    public void v220StyleArchive_600kIterationsNoHeader_restoresOnFirstTry() throws Exception {
        byte[] plaintext = realPlaintextArchive();
        // Same v1 magic/layout as a real pre-2.3.0 archive, but already at the
        // current iteration count -- exactly what v2.2.0 actually wrote.
        byte[] archive = encryptV1(plaintext, PASSWORD, 600_000);
        Uri uri = writeToTempFile(archive, "v220_600k.lcb.enc");

        BackupArchiveReader.ArchiveData data = BackupManager.openArchive(context(), uri, PASSWORD, (m, c, t) -> {});
        assertTrue(data.checksumValid);
    }

    @Test
    public void currentV2Archive_selfDescribing_restoresSuccessfully() throws Exception {
        // encryptV2Reference mirrors BackupManager's private encryptArchive byte
        // layout exactly (see its own header comment); openArchive/decryptArchive
        // below is the real production method under test.
        byte[] archive = encryptV2Reference(realPlaintextArchive(), PASSWORD, 600_000);
        Uri uri = writeToTempFile(archive, "v230_selfdescribing.lcb.enc");
        assertTrue(BackupManager.isEncrypted(context(), uri));
        BackupArchiveReader.ArchiveData data = BackupManager.openArchive(context(), uri, PASSWORD, (m, c, t) -> {});
        assertTrue(data.checksumValid);
    }

    @Test
    public void wrongPassword_rejectedForLegacyArchive() throws Exception {
        byte[] archive = encryptV1(realPlaintextArchive(), PASSWORD, 120_000);
        Uri uri = writeToTempFile(archive, "legacy_wrongpass.lcb.enc");
        try {
            BackupManager.openArchive(context(), uri, "totally wrong password", (m, c, t) -> {});
            fail("expected decryption failure for wrong password");
        } catch (AEADBadTagException expected) {
            // correct: authentication failure, not a silent garbage decode
        }
    }

    @Test
    public void wrongPassword_rejectedForV2Archive() throws Exception {
        byte[] archive = encryptV2Reference(realPlaintextArchive(), PASSWORD, 600_000);
        Uri uri = writeToTempFile(archive, "v2_wrongpass.lcb.enc");
        try {
            BackupManager.openArchive(context(), uri, "totally wrong password", (m, c, t) -> {});
            fail("expected decryption failure for wrong password");
        } catch (AEADBadTagException expected) {
            // correct
        }
    }

    @Test
    public void tamperedV2Header_rejectedByAadAuthentication() throws Exception {
        byte[] archive = encryptV2Reference(realPlaintextArchive(), PASSWORD, 600_000);
        // Flip the claimed iteration count down to 1 without re-encrypting -- if
        // this were accepted, it would be a serious downgrade-attack surface.
        byte[] tampered = archive.clone();
        int magicLen = "LIBRECB2".getBytes(java.nio.charset.StandardCharsets.US_ASCII).length;
        tampered[magicLen] = 0;
        tampered[magicLen + 1] = 0;
        tampered[magicLen + 2] = 0;
        tampered[magicLen + 3] = 1;
        Uri uri = writeToTempFile(tampered, "v2_tampered.lcb.enc");
        try {
            BackupManager.openArchive(context(), uri, PASSWORD, (m, c, t) -> {});
            fail("expected AAD authentication failure for a tampered header");
        } catch (AEADBadTagException expected) {
            // correct: header tampering is caught, not silently honored
        }
    }

    @Test
    public void unencryptedArchive_isNotFlaggedAsEncrypted() throws Exception {
        byte[] plaintext = realPlaintextArchive();
        Uri uri = writeToTempFile(plaintext, "plain.lcb");
        assertFalse(BackupManager.isEncrypted(context(), uri));
        BackupArchiveReader.ArchiveData data = BackupManager.openArchive(context(), uri, null, (m, c, t) -> {});
        assertTrue(data.checksumValid);
    }

    // Mirrors BackupManager's own v2 write format exactly (MAGIC_V2 + iterations +
    // salt + iv + AAD-bound ciphertext) so these tests don't depend on a runtime
    // hook into the private encryptArchive method.
    private static byte[] encryptV2Reference(byte[] plaintext, String password, int iterations) throws Exception {
        byte[] magic = "LIBRECB2".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] salt = new byte[16], iv = new byte[12];
        SecureRandom random = new SecureRandom();
        random.nextBytes(salt);
        random.nextBytes(iv);
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(magic);
        header.write(new byte[]{(byte) (iterations >>> 24), (byte) (iterations >>> 16), (byte) (iterations >>> 8), (byte) iterations});
        header.write(salt);
        header.write(iv);
        byte[] headerBytes = header.toByteArray();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(pbkdf2(password, salt, iterations), "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD(headerBytes);
        byte[] encrypted = cipher.doFinal(plaintext);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(headerBytes);
        out.write(encrypted);
        return out.toByteArray();
    }
}
