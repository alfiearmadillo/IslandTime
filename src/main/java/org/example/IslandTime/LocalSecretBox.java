package org.example.islandtime;

import com.sun.jna.platform.win32.Crypt32Util;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Encrypts small secrets (saved login tokens) so they are not sitting in the config folder as readable text.
 *
 * <p>On Windows the data is sealed with DPAPI, so it can only be opened by the same Windows user on the same
 * machine, with no key for this mod to look after. Elsewhere it is AES-256-GCM with a random key kept in a
 * separate file that only the current user can read. That is weaker (anything running as you can read both
 * files) but is the best a mod can do without an OS keychain, and it still keeps the tokens out of backups,
 * screenshots of the folder and casual file browsing.
 *
 * <p>The first byte says which scheme sealed the data, so a file is always opened the way it was written.
 */
final class LocalSecretBox {

    private static final byte SCHEME_DPAPI = 1;
    private static final byte SCHEME_AES = 2;
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final Path KEY_FILE = Paths.get("config/IslandTime/accounts.key");

    private LocalSecretBox() {
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    static byte[] seal(byte[] plain) throws IOException {
        try {
            if (isWindows()) {
                return prefixed(SCHEME_DPAPI, Crypt32Util.cryptProtectData(plain));
            }
            byte[] iv = new byte[GCM_IV_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(loadOrCreateKey(), "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain);
            byte[] out = new byte[1 + iv.length + encrypted.length];
            out[0] = SCHEME_AES;
            System.arraycopy(iv, 0, out, 1, iv.length);
            System.arraycopy(encrypted, 0, out, 1 + iv.length, encrypted.length);
            return out;
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IOException("Could not encrypt: " + e.getClass().getSimpleName(), e);
        }
    }

    static byte[] open(byte[] sealed) throws IOException {
        if (sealed.length < 2) throw new IOException("Saved data is empty");
        try {
            switch (sealed[0]) {
                case SCHEME_DPAPI:
                    if (!isWindows()) throw new IOException("Saved on Windows; it can only be opened there");
                    return Crypt32Util.cryptUnprotectData(Arrays.copyOfRange(sealed, 1, sealed.length));
                case SCHEME_AES: {
                    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(loadKey(), "AES"),
                            new GCMParameterSpec(GCM_TAG_BITS, sealed, 1, GCM_IV_BYTES));
                    return cipher.doFinal(sealed, 1 + GCM_IV_BYTES, sealed.length - 1 - GCM_IV_BYTES);
                }
                default:
                    throw new IOException("Unknown format");
            }
        } catch (GeneralSecurityException | RuntimeException e) {
            // The message is dropped on purpose: it must never carry any of the data
            throw new IOException("Could not decrypt (" + e.getClass().getSimpleName() + "); was this file made "
                    + "by another user or on another computer?");
        }
    }

    private static byte[] prefixed(byte scheme, byte[] data) {
        byte[] out = new byte[1 + data.length];
        out[0] = scheme;
        System.arraycopy(data, 0, out, 1, data.length);
        return out;
    }

    private static byte[] loadKey() throws IOException {
        byte[] key = Files.readAllBytes(KEY_FILE);
        if (key.length != 32) throw new IOException("Key file is damaged");
        return key;
    }

    private static byte[] loadOrCreateKey() throws IOException {
        if (Files.exists(KEY_FILE)) return loadKey();

        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        Files.createDirectories(KEY_FILE.getParent());
        Path tmp = KEY_FILE.resolveSibling("accounts.key.tmp");
        Files.write(tmp, key);
        try {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Not a POSIX file system
        }
        Files.move(tmp, KEY_FILE, StandardCopyOption.REPLACE_EXISTING);
        return key;
    }
}
