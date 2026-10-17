package org.example.islandtime;

import java.util.ArrayList;
import java.util.List;

/**
 * Decompression half of Pieroxy's LZ-String ("compressToEncodedURIComponent" flavour), which is how
 * skymutations.eu packs a greenhouse layout into the {@code layout=} parameter of its links.
 *
 * <p>Decoding is bounded: input and output sizes are capped so a malicious or corrupt link pasted
 * from the clipboard cannot make the client allocate an unreasonable amount of memory.
 */
final class LZString {

    /** The URI safe alphabet of LZ-String: value 0-63 is the index of the character. */
    private static final String URI_SAFE_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+-$";

    /** Longest accepted encoded input. */
    static final int MAX_INPUT_CHARS = 50_000;

    /** Longest decoded output that is accepted. */
    static final int MAX_OUTPUT_CHARS = 1_000_000;

    private LZString() {}

    /**
     * Decompresses a string produced by LZ-String's {@code compressToEncodedURIComponent}.
     *
     * @return the original text, or null if the input is not valid LZ-String data
     */
    static String decompressFromEncodedURIComponent(String input) {
        if (input == null || input.isEmpty() || input.length() > MAX_INPUT_CHARS) return null;
        // A "+" that went through URL decoding arrives as a space
        String encoded = input.replace(' ', '+');

        int[] values = new int[encoded.length()];
        for (int i = 0; i < values.length; i++) {
            int value = URI_SAFE_ALPHABET.indexOf(encoded.charAt(i));
            if (value < 0) return null;
            values[i] = value;
        }
        return decompress(values);
    }

    /** Reads a stream of 6 bit values most significant bit first. */
    private static final class BitReader {
        private final int[] values;
        private int index = 0;
        private int position = 32;
        private int current;

        BitReader(int[] values) {
            this.values = values;
            this.current = values.length > 0 ? values[0] : 0;
            this.index = 1;
        }

        /** Returns true once more bits have been requested than the input holds. */
        boolean exhausted() {
            return index > values.length;
        }

        int readBits(int count) {
            int bits = 0;
            for (int power = 1, max = 1 << count; power != max; power <<= 1) {
                int bit = current & position;
                position >>= 1;
                if (position == 0) {
                    position = 32;
                    current = index < values.length ? values[index] : 0;
                    index++;
                }
                if (bit > 0) bits |= power;
            }
            return bits;
        }
    }

    private static String decompress(int[] values) {
        BitReader reader = new BitReader(values);
        List<String> dictionary = new ArrayList<>();
        for (int i = 0; i < 3; i++) dictionary.add(Integer.toString(i));

        int enlargeIn = 4;
        int numBits = 3;

        String first;
        switch (reader.readBits(2)) {
            case 0 -> first = String.valueOf((char) reader.readBits(8));
            case 1 -> first = String.valueOf((char) reader.readBits(16));
            default -> {
                return null;
            }
        }
        dictionary.add(first);
        String w = first;
        StringBuilder result = new StringBuilder(first);

        while (true) {
            if (reader.exhausted()) return null;

            int code = reader.readBits(numBits);
            switch (code) {
                case 0 -> {
                    dictionary.add(String.valueOf((char) reader.readBits(8)));
                    code = dictionary.size() - 1;
                    enlargeIn--;
                }
                case 1 -> {
                    dictionary.add(String.valueOf((char) reader.readBits(16)));
                    code = dictionary.size() - 1;
                    enlargeIn--;
                }
                case 2 -> {
                    return result.toString();
                }
                default -> {
                }
            }

            if (enlargeIn == 0) {
                enlargeIn = 1 << numBits;
                numBits++;
            }

            String entry;
            if (code < dictionary.size()) {
                entry = dictionary.get(code);
            } else if (code == dictionary.size()) {
                entry = w + w.charAt(0);
            } else {
                return null;
            }

            result.append(entry);
            if (result.length() > MAX_OUTPUT_CHARS) return null;

            dictionary.add(w + entry.charAt(0));
            enlargeIn--;
            w = entry;

            if (enlargeIn == 0) {
                enlargeIn = 1 << numBits;
                numBits++;
            }
        }
    }
}
