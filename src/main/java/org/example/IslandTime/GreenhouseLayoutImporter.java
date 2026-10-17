package org.example.islandtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.math.BigInteger;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a link from one of the greenhouse layout websites into a {@link GreenhouseBlueprint}.
 *
 * <ul>
 *   <li><b>skylayouts.io</b>: share links ({@code /l/1KI~p3...}), which carry one board per greenhouse
 *       plot (up to three), and {@code layout.html?m=..&p=..} links when {@code p} holds the boards.
 *       A {@code layout.html} link whose {@code p} is only a plot count has no layout in it.</li>
 *   <li><b>skymutations.eu</b>: {@code /greenhouse?layout=...}, a compressed JSON list of cells.</li>
 * </ul>
 */
final class GreenhouseLayoutImporter {

    /** What an import produced: a blueprint, or an error to show to the player. */
    record ImportResult(GreenhouseBlueprint blueprint, String error, List<String> notes) {
        boolean ok() {
            return blueprint != null;
        }

        static ImportResult failure(String error) {
            return new ImportResult(null, error, List.of());
        }
    }

    private static final String SKY_LAYOUTS_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    private static final int MAX_INPUT_LENGTH = 20_000;
    private static final int MAX_BOARD_LENGTH = 400;

    private GreenhouseLayoutImporter() {}

    static ImportResult parse(String input) {
        String text = clean(input);
        if (text.isEmpty()) {
            return ImportResult.failure("Nothing to import. Copy a skylayouts.io or skymutations.eu link first.");
        }
        if (text.length() > MAX_INPUT_LENGTH) {
            return ImportResult.failure("That text is too long to be a layout link.");
        }

        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("skylayouts")) {
            return parseSkyLayouts(text);
        }
        if (lower.contains("skymutations") || lower.contains("layout=")) {
            return parseSkyMutations(text);
        }
        if (lower.contains("skyshards")) {
            return ImportResult.failure("SkyShards layouts can't be imported yet. Use a skylayouts.io or skymutations.eu link.");
        }
        if (looksLikeSkyLayoutsCode(text)) {
            return parseSkyLayouts(text);
        }
        // A bare skymutations code is just the compressed layout
        if (!text.contains("/") && !text.contains(":")) {
            ImportResult bare = parseSkyMutations(text);
            if (bare.ok()) return bare;
        }
        return ImportResult.failure("That doesn't look like a skylayouts.io or skymutations.eu link.");
    }

    /** Takes the first line, undoes the HTML escaping that gets copied along with links, drops wrappers. */
    private static String clean(String input) {
        if (input == null) return "";
        String text = input.strip();
        int newline = text.indexOf('\n');
        if (newline >= 0) text = text.substring(0, newline).strip();
        text = text.replace("&amp;", "&");
        while (!text.isEmpty() && "<\"'`(".indexOf(text.charAt(0)) >= 0) text = text.substring(1);
        while (!text.isEmpty() && ">\"'`).,".indexOf(text.charAt(text.length() - 1)) >= 0) {
            text = text.substring(0, text.length() - 1);
        }
        return text.strip();
    }

    // ---------------------------------------------------------------------------------------------
    // skylayouts.io
    // ---------------------------------------------------------------------------------------------

    private static boolean looksLikeSkyLayoutsCode(String text) {
        int tilde = text.indexOf('~');
        if (tilde < 3 || text.charAt(0) != '1') return false;
        for (int i = 0; i < tilde; i++) {
            if (SKY_LAYOUTS_ALPHABET.indexOf(text.charAt(i)) < 0) return false;
        }
        return true;
    }

    private static ImportResult parseSkyLayouts(String text) {
        String path = text;
        String query = "";
        String fragment = "";
        int hash = path.indexOf('#');
        if (hash >= 0) {
            fragment = path.substring(hash + 1);
            path = path.substring(0, hash);
        }
        int question = path.indexOf('?');
        if (question >= 0) {
            query = path.substring(question + 1);
            path = path.substring(0, question);
        }

        String targetKey = null;
        String boardData = null;

        Map<String, String> params = parseQuery(query);
        if (params.containsKey("p")) {
            // layout.html?m=<mutation>&i=<interval>&p=<boards>
            boardData = params.get("p");
            String mutation = params.get("m");
            if (boardData.strip().length() < 3 && boardData.strip().chars().allMatch(Character::isDigit)) {
                // A board is at least three characters long, so this is a setting (the plot count),
                // not layout data: the site works the layout out itself when the page is opened
                return ImportResult.failure("That link only holds the layout's settings (mutation "
                        + (mutation == null ? "?" : mutation) + ", interval "
                        + params.getOrDefault("i", "?") + ", " + boardData.strip() + " plot(s)), not the layout itself. "
                        + "Open it on skylayouts.io, press Share and import the skylayouts.io/l/... link it gives you.");
            }
            if (mutation != null && !mutation.isBlank()) {
                GreenhouseCrops.Crop crop = GreenhouseCrops.find(mutation);
                if (crop != null) targetKey = crop.key();
            }
        } else {
            int marker = path.indexOf("/l/");
            String code = marker >= 0 ? path.substring(marker + 3) : path;
            if (code.endsWith("/")) code = code.substring(0, code.length() - 1);
            if (!looksLikeSkyLayoutsCode(code) && looksLikeSkyLayoutsCode(fragment)) code = fragment;
            if (!looksLikeSkyLayoutsCode(code)) {
                return ImportResult.failure("Couldn't find a layout in that SkyLayouts link. "
                        + "Use the site's Share button and paste the skylayouts.io/l/... link it gives you.");
            }
            int tilde = code.indexOf('~');
            String header = code.substring(0, tilde);
            GreenhouseCrops.Crop crop = GreenhouseCrops.bySkyLayoutsIndex(SKY_LAYOUTS_ALPHABET.indexOf(header.charAt(1)) - 1);
            if (crop != null) targetKey = crop.key();
            boardData = code.substring(tilde + 1);
        }

        if (boardData == null || boardData.isBlank()) {
            return ImportResult.failure("That SkyLayouts link holds no layout.");
        }
        return readSkyLayoutsBoards(boardData, targetKey);
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return params;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String key = pair.substring(0, eq).toLowerCase(Locale.ROOT);
            String value = pair.substring(eq + 1);
            try {
                value = URLDecoder.decode(value, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                // keep the raw value
            }
            // A link may carry one p parameter per board
            params.merge(key, value, (a, b) -> key.equals("p") ? a + "~" + b : a);
        }
        return params;
    }

    private static ImportResult readSkyLayoutsBoards(String boardData, String targetKey) {
        String data = boardData;
        // The board list is introduced with a "p" in share links: ~p<board>~<board>
        if (data.length() > 1 && data.charAt(0) == 'p' && data.charAt(1) == '3') data = data.substring(1);

        List<String> notes = new ArrayList<>();
        GreenhouseBlueprint blueprint = new GreenhouseBlueprint();
        blueprint.source = "SkyLayouts";

        String[] segments = data.split("~");
        int number = 0;
        for (String segment : segments) {
            if (segment.isEmpty()) continue;
            number++;
            if (blueprint.boards.size() >= GreenhouseBlueprint.MAX_BOARDS) {
                notes.add("Only the first " + GreenhouseBlueprint.MAX_BOARDS + " boards were imported.");
                break;
            }
            GreenhouseBlueprint.Board board = readSkyLayoutsBoard(segment, notes);
            if (board == null) {
                String hint = segment.length() < 8
                        ? " It looks cut off; copy the whole link with the site's Share button."
                        : "";
                return ImportResult.failure("Couldn't read board " + number + " of that SkyLayouts link." + hint);
            }
            blueprint.boards.add(board);
        }
        if (blueprint.boards.isEmpty()) {
            return ImportResult.failure("That SkyLayouts link holds no layout.");
        }

        if (targetKey != null) {
            GreenhouseCrops.Crop target = GreenhouseCrops.byKey(targetKey);
            blueprint.target = target == null ? "" : target.name();
            if (target != null) {
                notes.add("Only the ingredients are shown. SkyLayouts links don't say where " + target.name()
                        + " will spawn.");
            }
        }
        blueprint.name = blueprint.target.isEmpty() ? "SkyLayouts layout" : blueprint.target;

        boolean empty = true;
        for (GreenhouseBlueprint.Board board : blueprint.boards) {
            if (!board.placements.isEmpty()) empty = false;
        }
        if (empty) return ImportResult.failure("That layout is empty.");
        return new ImportResult(blueprint, null, dedupe(notes));
    }

    /**
     * Reads one board: {@code 3<size><type count><type ids...><packed cells>}. The type ids index into
     * {@link GreenhouseCrops#SKY_LAYOUTS_IDS}. The cells, row by row, are digits of one big number
     * (base type count + 1, 0 meaning empty, with a leading 1) that is written out in base 64.
     */
    private static GreenhouseBlueprint.Board readSkyLayoutsBoard(String board, List<String> notes) {
        if (board.length() < 3 || board.length() > MAX_BOARD_LENGTH || board.charAt(0) != '3') return null;
        int size = SKY_LAYOUTS_ALPHABET.indexOf(board.charAt(1));
        int typeCount = SKY_LAYOUTS_ALPHABET.indexOf(board.charAt(2));
        if (size <= 0 || size > GreenhouseGeometry.AREA_SIZE || typeCount < 0 || board.length() < 3 + typeCount) {
            return null;
        }

        String[] typeKeys = new String[typeCount];
        for (int i = 0; i < typeCount; i++) {
            int id = SKY_LAYOUTS_ALPHABET.indexOf(board.charAt(3 + i));
            if (id < 0 || id >= GreenhouseCrops.SKY_LAYOUTS_IDS.size()) return null;
            typeKeys[i] = GreenhouseCrops.SKY_LAYOUTS_IDS.get(id);
        }

        GreenhouseBlueprint.Board result = new GreenhouseBlueprint.Board();
        result.size = size;

        BigInteger packed = BigInteger.ZERO;
        BigInteger sixtyFour = BigInteger.valueOf(64);
        for (int i = 3 + typeCount; i < board.length(); i++) {
            int value = SKY_LAYOUTS_ALPHABET.indexOf(board.charAt(i));
            if (value < 0) return null;
            packed = packed.multiply(sixtyFour).add(BigInteger.valueOf(value));
        }

        int cells = size * size;
        int[] cellTypes = new int[cells];
        if (typeCount == 0) {
            // Nothing to decode, and base 1 would never get anywhere
            return result;
        }
        BigInteger base = BigInteger.valueOf(typeCount + 1L);
        int count = 0;
        // The digits come out last cell first
        while (packed.compareTo(BigInteger.ONE) > 0) {
            if (count >= cells) return null;
            BigInteger[] divided = packed.divideAndRemainder(base);
            cellTypes[cells - 1 - count] = divided[1].intValue() - 1;
            packed = divided[0];
            count++;
        }
        if (count != cells || !packed.equals(BigInteger.ONE)) return null;

        boolean[] covered = new boolean[cells];
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int index = z * size + x;
                int type = cellTypes[index];
                if (covered[index] || type < 0) continue;
                if (type >= typeCount) return null;

                GreenhouseCrops.Crop crop = GreenhouseCrops.byKey(typeKeys[type]);
                if (crop == null) {
                    notes.add("Unknown crop: " + typeKeys[type]);
                    continue;
                }
                int footprint = crop.size();
                if (footprint > 1 && !footprintMatches(cellTypes, covered, size, x, z, footprint, type)) {
                    notes.add(crop.name() + " didn't fit its " + footprint + "x" + footprint + " space and was shrunk.");
                    footprint = 1;
                }
                for (int dz = 0; dz < footprint; dz++) {
                    for (int dx = 0; dx < footprint; dx++) covered[(z + dz) * size + x + dx] = true;
                }
                result.placements.add(new GreenhouseBlueprint.Placement(crop.key(), x, z, footprint, false));
            }
        }
        return result;
    }

    private static boolean footprintMatches(int[] cellTypes, boolean[] covered, int size, int x, int z,
                                            int footprint, int type) {
        if (x + footprint > size || z + footprint > size) return false;
        for (int dz = 0; dz < footprint; dz++) {
            for (int dx = 0; dx < footprint; dx++) {
                int index = (z + dz) * size + x + dx;
                if (covered[index] || cellTypes[index] != type) return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------------
    // skymutations.eu
    // ---------------------------------------------------------------------------------------------

    private record SiteCell(String name, int marking) {}

    private static ImportResult parseSkyMutations(String text) {
        String param = text;
        int marker = text.indexOf("layout=");
        if (marker >= 0) {
            param = text.substring(marker + "layout=".length());
            for (char stop : new char[]{'&', '#'}) {
                int end = param.indexOf(stop);
                if (end >= 0) param = param.substring(0, end);
            }
        }
        param = param.strip();
        if (param.isEmpty()) {
            return ImportResult.failure("That SkyMutations link has no layout in it.");
        }
        if (param.indexOf('%') >= 0) {
            try {
                param = URLDecoder.decode(param, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return ImportResult.failure("That SkyMutations link isn't valid.");
            }
        }

        String json = LZString.decompressFromEncodedURIComponent(param);
        if (json == null) {
            return ImportResult.failure("Couldn't decode that SkyMutations layout.");
        }

        JsonArray entries;
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray()) return ImportResult.failure("That SkyMutations layout isn't a list of plants.");
            entries = root.getAsJsonArray();
        } catch (RuntimeException e) {
            return ImportResult.failure("That SkyMutations layout isn't a list of plants.");
        }

        int n = GreenhouseGeometry.AREA_SIZE;
        SiteCell[][] grid = new SiteCell[n][n];
        for (JsonElement element : entries) {
            try {
                JsonArray cell = element.getAsJsonArray();
                if (cell.size() < 3) continue;
                int row = cell.get(0).getAsInt();
                int col = cell.get(1).getAsInt();
                String name = cell.get(2).getAsString();
                int marking = cell.size() > 3 ? cell.get(3).getAsInt() : 1;
                if (row < 0 || row >= n || col < 0 || col >= n || grid[row][col] != null) continue;
                grid[row][col] = new SiteCell(name, marking);
            } catch (RuntimeException ignored) {
                // an entry that isn't [row, column, name, marking] is skipped
            }
        }

        List<String> notes = new ArrayList<>();
        GreenhouseBlueprint blueprint = new GreenhouseBlueprint();
        blueprint.source = "SkyMutations";
        GreenhouseBlueprint.Board board = new GreenhouseBlueprint.Board();
        board.size = n;
        blueprint.boards.add(board);

        boolean[][] covered = new boolean[n][n];
        Set<String> unknown = new LinkedHashSet<>();
        for (int row = 0; row < n; row++) {
            for (int col = 0; col < n; col++) {
                SiteCell cell = grid[row][col];
                if (cell == null || covered[row][col]) continue;

                GreenhouseCrops.Crop crop = GreenhouseCrops.find(cell.name());
                if (crop == null) {
                    unknown.add(cell.name());
                    covered[row][col] = true;
                    continue;
                }
                int footprint = crop.size();
                if (footprint > 1 && !skyMutationsFootprintFits(grid, covered, row, col, footprint, crop)) {
                    notes.add(crop.name() + " didn't fit its " + footprint + "x" + footprint + " space and was shrunk.");
                    footprint = 1;
                }
                for (int dr = 0; dr < footprint; dr++) {
                    for (int dc = 0; dc < footprint; dc++) covered[row + dr][col + dc] = true;
                }

                // The site draws the board with north on the left; the world has it turned
                int x = n - row - footprint;
                int z = col;
                boolean target = cell.marking() == 0;
                board.placements.add(new GreenhouseBlueprint.Placement(crop.key(), x, z, footprint, target));
                if (target && blueprint.target.isEmpty()) blueprint.target = crop.name();
            }
        }
        for (String name : unknown) notes.add("Unknown crop: " + name);

        if (board.placements.isEmpty()) return ImportResult.failure("That layout is empty.");
        blueprint.name = blueprint.target.isEmpty() ? "SkyMutations layout" : blueprint.target;
        return new ImportResult(blueprint, null, dedupe(notes));
    }

    /** A big crop either has an entry in all its cells, or only in its first one. */
    private static boolean skyMutationsFootprintFits(SiteCell[][] grid, boolean[][] covered, int row, int col,
                                                     int footprint, GreenhouseCrops.Crop crop) {
        int n = grid.length;
        if (row + footprint > n || col + footprint > n) return false;
        for (int dr = 0; dr < footprint; dr++) {
            for (int dc = 0; dc < footprint; dc++) {
                if (covered[row + dr][col + dc]) return false;
                SiteCell other = grid[row + dr][col + dc];
                if (other != null && GreenhouseCrops.find(other.name()) != crop) return false;
            }
        }
        return true;
    }

    private static List<String> dedupe(List<String> notes) {
        return new ArrayList<>(new LinkedHashSet<>(notes));
    }
}
