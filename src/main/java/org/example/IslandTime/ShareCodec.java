package org.example.islandtime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Turns a JSON text into a single line of text that can be pasted anywhere (a version prefix,
 * then the GZIP-compressed JSON as Base64) and back. Knows nothing about what the JSON holds.
 * Decoding is bounded, so a pasted code can never inflate into something huge.
 */
final class ShareCodec {

    /** The longest code accepted, in characters. */
    static final int MAX_CODE_CHARS = 3_000_000;
    /** The most JSON a code may expand to, in bytes. */
    static final int MAX_JSON_BYTES = 4 * 1024 * 1024;

    private ShareCodec() {}

    static String encode(String prefix, String json) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return prefix + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    /**
     * Gets the JSON back out of a code made by {@link #encode}. Tolerates what pasting adds around
     * it (spaces, line breaks, quotes).
     *
     * @throws IllegalArgumentException with a message fit to show the user when the text isn't a
     *                                  valid code
     */
    static String decode(String prefix, String code) {
        if (code == null) throw new IllegalArgumentException("There is nothing to import.");
        String text = stripPasteNoise(code);
        if (text.isEmpty()) throw new IllegalArgumentException("There is nothing to import.");
        if (!text.startsWith(prefix)) {
            throw new IllegalArgumentException("That is not an IslandTime greenhouse code (it starts with "
                    + prefix + ").");
        }
        if (text.length() > MAX_CODE_CHARS) throw new IllegalArgumentException("That code is too big.");

        byte[] compressed;
        try {
            compressed = Base64.getDecoder().decode(text.substring(prefix.length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The code is damaged, part of it is missing or was changed.");
        }

        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = gzip.read(buffer)) != -1) {
                if (out.size() + read > MAX_JSON_BYTES) {
                    throw new IllegalArgumentException("That code is too big.");
                }
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("The code is damaged, part of it is missing or was changed.");
        }
    }

    /** Removes whitespace, and quotes or backticks wrapped around the whole code. */
    private static String stripPasteNoise(String code) {
        StringBuilder sb = new StringBuilder(code.length());
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (!Character.isWhitespace(c)) sb.append(c);
        }
        int start = 0;
        int end = sb.length();
        while (start < end && isWrapper(sb.charAt(start))) start++;
        while (end > start && isWrapper(sb.charAt(end - 1))) end--;
        return sb.substring(start, end);
    }

    private static boolean isWrapper(char c) {
        return c == '"' || c == '\'' || c == '`';
    }
}
