package org.example.islandtime;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import net.minecraft.client.Minecraft;

/**
 * Shares greenhouse blueprints as text: {@link #export} turns a layout into a short single line that can
 * be posted anywhere, and {@link #importCode} reads one back in as a new blueprint. A code that comes from
 * someone else is checked thoroughly first, since nothing in it can be trusted to be sane.
 *
 * <p>The layout is written by {@link GreenhouseShareFormat} and turned into text by {@link ShareCodec}.
 * Codes from the first version of the mod, which were a few thousand characters and started with
 * {@value #LEGACY_PREFIX}, can still be imported.
 */
final class GreenhouseBlueprintShare {

    /** The start of the long codes the first version of the mod made. */
    static final String LEGACY_PREFIX = "ITGH1:";
    /** What imported blueprints say they came from. */
    static final String SOURCE_IMPORTED = "Imported";

    private static final int MAX_STORED_CHARS = 4 * 1024 * 1024;
    private static final int MAX_NAME_LENGTH = 48;
    private static final int MAX_TEXT_LENGTH = 512;
    private static final int MAX_GEAR_DATA_LENGTH = 65_536;
    private static final int MAX_PLOT_BOARDS = 64;
    private static final int MAX_STANDS_PER_PLACEMENT = 32;
    private static final int MAX_GEAR_PER_STAND = 16;
    private static final double MAX_COORDINATE = 1000.0;

    private static final Gson GSON = new Gson();

    private GreenhouseBlueprintShare() {}

    /** What an import or a copy did, and a line to tell the user about it. */
    record Outcome(boolean ok, String message) {}

    /** The result of reading a code: the blueprint, or why there isn't one. */
    record Parsed(GreenhouseBlueprint blueprint, String error) {
        boolean ok() {
            return blueprint != null;
        }
    }

    // -------------------------------------------------------------------------
    // Export
    // -------------------------------------------------------------------------

    /**
     * The shareable code for a blueprint. The blueprint's own id stays private to this install.
     *
     * @throws IllegalArgumentException with a message fit to show the user if it can't be shared
     */
    static String export(GreenhouseBlueprint blueprint) {
        byte[] layout = GreenhouseShareFormat.write(blueprint, GreenhouseStandItems.SHARE_LOOKS);
        return ShareCodec.encode(layout, GreenhouseShareFormat.DICTIONARY);
    }

    /** Puts the blueprint's code on the clipboard. */
    static Outcome copyToClipboard(Minecraft client, GreenhouseBlueprint blueprint) {
        String code;
        try {
            code = export(blueprint);
        } catch (IllegalArgumentException e) {
            return new Outcome(false, "Couldn't make a code for \"" + blueprint.name + "\": " + e.getMessage());
        } catch (RuntimeException e) {
            return new Outcome(false, "Couldn't make a code for \"" + blueprint.name + "\".");
        }
        client.keyboardHandler.setClipboard(code);
        return new Outcome(true, "Copied the import code for \"" + blueprint.name + "\" ("
                + code.length() + " characters). Anyone can paste it with Import.");
    }

    // -------------------------------------------------------------------------
    // Import
    // -------------------------------------------------------------------------

    /** Imports whatever code is on the clipboard. */
    static Outcome importFromClipboard(Minecraft client) {
        return importCode(client.keyboardHandler.getClipboard());
    }

    /** Reads a code, adds the layout in it as a new blueprint and selects it. */
    static Outcome importCode(String code) {
        Parsed parsed = parse(code);
        if (!parsed.ok()) return new Outcome(false, parsed.error());
        GreenhouseBlueprint blueprint = parsed.blueprint();
        if (!GreenhouseBlueprintManager.add(blueprint)) {
            return new Outcome(false, "You already have " + GreenhouseBlueprintManager.MAX_BLUEPRINTS
                    + " blueprints. Delete one first.");
        }
        GreenhouseBlueprintRenderer.requestRefresh();
        int boards = blueprint.boards.size();
        return new Outcome(true, "Imported \"" + blueprint.name + "\" (" + boards + " board"
                + (boards == 1 ? "" : "s") + ") and selected it.");
    }

    /** Decodes and checks a code without storing anything. */
    static Parsed parse(String code) {
        GreenhouseBlueprint blueprint;
        try {
            blueprint = ShareCodec.stripPasteNoise(code).startsWith(LEGACY_PREFIX) ? readLegacy(code) : read(code);
        } catch (IllegalArgumentException e) {
            return new Parsed(null, e.getMessage());
        }

        try {
            blueprint.upgrade();
        } catch (RuntimeException e) {
            return new Parsed(null, "The code is damaged, it doesn't hold a greenhouse layout.");
        }
        String problem = validate(blueprint);
        if (problem != null) return new Parsed(null, "The code isn't a usable layout: " + problem);
        // However it got there, what is stored must stay as small as a layout can honestly be
        if (GSON.toJson(blueprint).length() > MAX_STORED_CHARS) return new Parsed(null, "That layout is too big.");

        blueprint.id = "";
        blueprint.source = SOURCE_IMPORTED;
        blueprint.rotation = Math.floorMod(blueprint.rotation, 4);
        blueprint.name = clean(blueprint.name, MAX_NAME_LENGTH);
        return new Parsed(blueprint, null);
    }

    private static GreenhouseBlueprint read(String code) {
        byte[] layout = ShareCodec.decode(code, GreenhouseShareFormat.DICTIONARY);
        try {
            return GreenhouseShareFormat.read(layout, GreenhouseStandItems.SHARE_LOOKS);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw ShareCodec.damaged();
        }
    }

    /** Reads a code of the first version of the mod: gzipped JSON of the blueprint as it is stored. */
    private static GreenhouseBlueprint readLegacy(String code) {
        String json = ShareCodec.decodeLegacy(LEGACY_PREFIX, code);
        GreenhouseBlueprint blueprint;
        try {
            blueprint = GSON.fromJson(json, GreenhouseBlueprint.class);
        } catch (JsonParseException | IllegalStateException e) {
            throw new IllegalArgumentException("The code is damaged, it doesn't hold a greenhouse layout.");
        }
        if (blueprint == null) throw new IllegalArgumentException("The code is damaged, it doesn't hold a greenhouse layout.");
        return blueprint;
    }

    /**
     * Checks an already upgraded blueprint for anything the overlay isn't built to draw or that is
     * absurdly big.
     *
     * @return what is wrong with it, or null if it is fine
     */
    private static String validate(GreenhouseBlueprint blueprint) {
        if (blueprint.boards.isEmpty()) return "it has no boards.";
        if (blueprint.boards.size() > GreenhouseBlueprint.MAX_BOARDS) {
            return "it has more than " + GreenhouseBlueprint.MAX_BOARDS + " boards.";
        }
        if (blueprint.plotBoards.size() > MAX_PLOT_BOARDS) return "it assigns too many plots.";
        for (java.util.Map.Entry<String, Integer> plot : blueprint.plotBoards.entrySet()) {
            if (plot.getKey() == null || plot.getValue() == null || plot.getKey().length() > 32) {
                return "a plot assignment is invalid.";
            }
        }

        for (GreenhouseBlueprint.Board board : blueprint.boards) {
            String problem = validate(board);
            if (problem != null) return problem;
        }
        return null;
    }

    private static String validate(GreenhouseBlueprint.Board board) {
        int size = GreenhouseGeometry.AREA_SIZE;
        if (board.size != size) return "a board isn't " + size + "x" + size + " cells.";
        if (board.placements.size() > size * size) return "a board has too many crops.";

        if (board.floorPalette.size() > GreenhouseBlueprint.PALETTE_CHARS.length()) {
            return "a board has too many kinds of soil.";
        }
        for (String block : board.floorPalette) {
            if (block == null || block.length() > MAX_TEXT_LENGTH) return "a soil block is invalid.";
        }
        if (board.floorRows.size() > size) return "a board's soil has too many rows.";
        for (String row : board.floorRows) {
            if (row == null || row.length() > size) return "a board's soil row is too long.";
        }

        for (GreenhouseBlueprint.Placement placement : board.placements) {
            String problem = validate(placement, size);
            if (problem != null) return problem;
        }
        return null;
    }

    private static String validate(GreenhouseBlueprint.Placement p, int boardSize) {
        if (p.size < 1 || p.size > boardSize || p.x < 0 || p.z < 0
                || p.x > boardSize - p.size || p.z > boardSize - p.size) {
            return "a crop is outside the greenhouse.";
        }
        if (tooLong(p.crop) || tooLong(p.name) || tooLong(p.block)) return "a crop has an invalid name.";
        p.name = clean(p.name, MAX_TEXT_LENGTH);
        if (p.stands.size() > MAX_STANDS_PER_PLACEMENT) return "a crop has too many armor stands.";

        for (GreenhouseBlueprint.Stand stand : p.stands) {
            if (tooLong(stand.head)) return "an armor stand has an invalid name.";
            stand.head = clean(stand.head, MAX_TEXT_LENGTH);
            if (!inRange(stand.x) || !inRange(stand.y) || !inRange(stand.z)
                    || !inRange(stand.width) || !inRange(stand.height) || stand.width < 0 || stand.height < 0) {
                return "an armor stand is somewhere impossible.";
            }
            if (stand.gear.size() > MAX_GEAR_PER_STAND) return "an armor stand wears too much.";
            for (GreenhouseBlueprint.Gear gear : stand.gear) {
                if (tooLong(gear.slot) || tooLong(gear.key) || tooLong(gear.name)
                        || gear.data.length() > MAX_GEAR_DATA_LENGTH) {
                    return "an armor stand's item is invalid.";
                }
                gear.name = clean(gear.name, MAX_TEXT_LENGTH);
            }
        }
        return null;
    }

    private static boolean tooLong(String text) {
        return text != null && text.length() > MAX_TEXT_LENGTH;
    }

    private static boolean inRange(double value) {
        return Double.isFinite(value) && Math.abs(value) <= MAX_COORDINATE;
    }

    /** Drops control characters and formatting codes from text that will be drawn, and cuts it to length. */
    private static String clean(String text, int maxLength) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(Math.min(text.length(), maxLength));
        for (int i = 0; i < text.length() && sb.length() < maxLength; i++) {
            char c = text.charAt(i);
            if (c == '\u00a7' || Character.isISOControl(c)) continue;
            sb.append(c);
        }
        return sb.toString().strip();
    }
}
