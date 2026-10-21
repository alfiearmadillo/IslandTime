package org.example.islandtime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;

/**
 * Turns bytes into a short single line of text that can be pasted anywhere, and back.
 *
 * <p>The bytes are deflated (with a fixed preset dictionary of words that are common in what is shared,
 * so even a short message compresses well) when that makes them smaller, a 15 bit check is added so a
 * code that was cut short or changed is noticed instead of read as something else, and the result is
 * written 14 bits to a character using {@value #ALPHABET_SIZE} of the common CJK ideographs. That is
 * more than twice as many bytes per character as Base64, uses no whitespace or symbols that chat apps
 * mangle, and one character still counts as one character everywhere (Discord's limit included).
 *
 * <p>Codes made by earlier versions ({@code ITGH1:} and Base64 of gzipped JSON) can still be read by
 * {@link #decodeLegacy}. Decoding is bounded, so a pasted code can never inflate into something huge.
 */
final class ShareCodec {

    /** The first character of the alphabet; the next {@link #ALPHABET_SIZE} code points follow it. */
    static final char ALPHABET_START = '\u4e00';
    static final int BITS_PER_CHAR = 14;
    static final int ALPHABET_SIZE = 1 << BITS_PER_CHAR;

    /** The longest code accepted, in characters. */
    static final int MAX_CODE_CHARS = 200_000;
    /** The most a code may expand to once decoded, in bytes. */
    static final int MAX_PAYLOAD_BYTES = 1024 * 1024;

    /** The longest legacy code accepted, in characters. */
    static final int MAX_LEGACY_CODE_CHARS = 3_000_000;
    /** The most JSON a legacy code may expand to, in bytes. */
    static final int MAX_LEGACY_JSON_BYTES = 4 * 1024 * 1024;

    private static final int CHECK_BITS = 15;
    private static final int CHECK_MASK = (1 << CHECK_BITS) - 1;

    private ShareCodec() {}

    // -------------------------------------------------------------------------
    // Current codes
    // -------------------------------------------------------------------------

    /** Whether the text is made of nothing but characters of the alphabet (so it can be a current code). */
    static boolean isCode(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            if (!isAlphabetChar(text.charAt(i))) return false;
        }
        return true;
    }

    static String encode(byte[] payload, byte[] dictionary) {
        byte[] deflated = deflate(payload, dictionary);
        boolean compressed = deflated.length < payload.length;
        byte[] body = compressed ? deflated : payload;

        int check = check(compressed, body);
        byte[] bytes = Arrays.copyOf(body, body.length + 2);
        int tail = (check << 1) | (compressed ? 1 : 0);
        bytes[body.length] = (byte) (tail >>> 8);
        bytes[body.length + 1] = (byte) tail;
        return toText(bytes);
    }

    /**
     * Gets the bytes back out of a code made by {@link #encode}. Tolerates what pasting adds around it
     * (spaces, line breaks, quotes).
     *
     * @throws IllegalArgumentException with a message fit to show the user when the text isn't a valid code
     */
    static byte[] decode(String code, byte[] dictionary) {
        String text = stripPasteNoise(code);
        if (text.isEmpty()) throw new IllegalArgumentException("There is nothing to import.");
        if (text.length() > MAX_CODE_CHARS) throw new IllegalArgumentException("That code is too big.");
        if (!isCode(text)) throw new IllegalArgumentException("That is not an IslandTime greenhouse code.");

        byte[] bytes = fromText(text);
        if (bytes.length < 2) throw damaged();

        int body = bytes.length - 2;
        int tail = ((bytes[body] & 0xFF) << 8) | (bytes[body + 1] & 0xFF);
        boolean compressed = (tail & 1) != 0;
        byte[] data = Arrays.copyOf(bytes, body);
        if ((tail >>> 1) != check(compressed, data)) throw damaged();
        return compressed ? inflate(data, dictionary) : data;
    }

    private static int check(boolean compressed, byte[] body) {
        CRC32 crc = new CRC32();
        crc.update(compressed ? 1 : 0);
        crc.update(body, 0, body.length);
        return (int) (crc.getValue() & CHECK_MASK);
    }

    private static byte[] deflate(byte[] payload, byte[] dictionary) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        try {
            deflater.setDictionary(dictionary);
            deflater.setInput(payload);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            while (!deflater.finished()) {
                int n = deflater.deflate(buffer);
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte[] inflate(byte[] data, byte[] dictionary) {
        Inflater inflater = new Inflater(true);
        try {
            inflater.setDictionary(dictionary);
            inflater.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw damaged();
                if (out.size() + n > MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("That code is too big.");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            throw damaged();
        } finally {
            inflater.end();
        }
    }

    /**
     * Writes bytes 14 bits to a character. A single 1 bit follows the data, then zeros up to a whole
     * character, so the length of the data is exact however it falls against the character boundaries.
     */
    private static String toText(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 8 / BITS_PER_CHAR + 2);
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= BITS_PER_CHAR) {
                bits -= BITS_PER_CHAR;
                sb.append((char) (ALPHABET_START + ((buffer >>> bits) & (ALPHABET_SIZE - 1))));
            }
            buffer &= (1 << bits) - 1;
        }
        // The marker bit, then zero padding
        buffer = (buffer << 1) | 1;
        bits++;
        buffer <<= BITS_PER_CHAR - bits;
        sb.append((char) (ALPHABET_START + (buffer & (ALPHABET_SIZE - 1))));
        return sb.toString();
    }

    /** The inverse of {@link #toText}. */
    private static byte[] fromText(String text) {
        int totalBits = text.length() * BITS_PER_CHAR;
        byte[] raw = new byte[(totalBits + 7) / 8];
        int bitPos = 0;
        for (int i = 0; i < text.length(); i++) {
            int value = text.charAt(i) - ALPHABET_START;
            for (int bit = BITS_PER_CHAR - 1; bit >= 0; bit--) {
                if (((value >>> bit) & 1) != 0) raw[bitPos >>> 3] |= (byte) (0x80 >>> (bitPos & 7));
                bitPos++;
            }
        }

        // Cut the padding: the last 1 bit is the marker, what is before it is the data
        int last = totalBits - 1;
        while (last >= 0 && (raw[last >>> 3] & (0x80 >>> (last & 7))) == 0) last--;
        if (last < 0 || last % 8 != 0 || totalBits - last > BITS_PER_CHAR) throw damaged();
        return Arrays.copyOf(raw, last / 8);
    }

    private static boolean isAlphabetChar(char c) {
        return c >= ALPHABET_START && c < ALPHABET_START + ALPHABET_SIZE;
    }

    // -------------------------------------------------------------------------
    // Legacy codes
    // -------------------------------------------------------------------------

    /**
     * Gets the JSON out of a code made by an earlier version: a prefix, then the gzipped JSON as Base64.
     *
     * @throws IllegalArgumentException with a message fit to show the user when the text isn't a valid code
     */
    static String decodeLegacy(String prefix, String code) {
        String text = stripPasteNoise(code);
        if (!text.startsWith(prefix)) throw new IllegalArgumentException("That is not an IslandTime greenhouse code.");
        if (text.length() > MAX_LEGACY_CODE_CHARS) throw new IllegalArgumentException("That code is too big.");

        byte[] compressed;
        try {
            compressed = Base64.getDecoder().decode(text.substring(prefix.length()));
        } catch (IllegalArgumentException e) {
            throw damaged();
        }

        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = gzip.read(buffer)) != -1) {
                if (out.size() + read > MAX_LEGACY_JSON_BYTES) {
                    throw new IllegalArgumentException("That code is too big.");
                }
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw damaged();
        }
    }

    // -------------------------------------------------------------------------
    // Shared
    // -------------------------------------------------------------------------

    static IllegalArgumentException damaged() {
        return new IllegalArgumentException("The code is damaged, part of it is missing or was changed.");
    }

    /** Removes whitespace (and zero width spaces), and quotes or backticks wrapped around the whole code. */
    static String stripPasteNoise(String code) {
        if (code == null) return "";
        StringBuilder sb = new StringBuilder(code.length());
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (Character.isWhitespace(c) || (c >= '\u200b' && c <= '\u200d') || c == '\ufeff') continue;
            sb.append(c);
        }
        int start = 0;
        int end = sb.length();
        while (start < end && isWrapper(sb.charAt(start))) start++;
        while (end > start && isWrapper(sb.charAt(end - 1))) end--;
        return sb.substring(start, end);
    }

    private static boolean isWrapper(char c) {
        return c == '"' || c == '\'' || c == '`' || c == '\u201c' || c == '\u201d' || c == '\u2018' || c == '\u2019';
    }
}
