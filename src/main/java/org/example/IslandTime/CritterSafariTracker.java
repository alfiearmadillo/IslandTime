package org.example.islandtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks which unique Critter Safari critters have been caught during the current run.
 *
 * <p>The full Critterdex is 37 species split across the four Safari biomes (Forest, Cavern, Icy,
 * Haunted). "SPARKLING" catches are the same species with a bonus reward, not separate entries, so
 * they are folded into the normal critter they name.
 *
 * <p>State is reset when the Safari Manager starts a new run
 * ({@code [NPC] Safari Manager: Looks good to me. Have fun out there!}).
 *
 * <p>This class deliberately has no Minecraft dependencies — chat lines are handed to
 * {@link #onChatMessage(String)} from the chat listener registered in {@code IslandTimeMod}.
 */
public class CritterSafariTracker {

    /** Strips Minecraft §-formatting codes before parsing (same approach as IslandChatRelay). */
    private static final Pattern FORMAT_STRIP = Pattern.compile("§[0-9a-fk-orA-FK-OR]");

    /** Exact NPC line Hypixel sends when a new Safari run begins. */
    private static final String RUN_START_MESSAGE =
            "Safari Manager: Looks good to me. Have fun out there!";

    // "CAPTURE! You caught a Gimmiegold and gained a Gimmiegold Shard! (2)"
    // "CAPTURE! You caught a SPARKLING Nozzlenose and received a Rainbow Feather and 2x Nozzlenose Shard!"
    private static final Pattern CAPTURE_CAUGHT =
            Pattern.compile("^CAPTURE! You caught (?:a|an|the) (?:SPARKLING )?(.+?) and ");

    // "CAPTURE! You found Hideyho, and as a reward he gave you a Hideyho Shard!"
    private static final Pattern CAPTURE_FOUND =
            Pattern.compile("^CAPTURE! You found (?:the )?([^,!]+)[,!]");

    // "LOOT SHARE! You received a Billygoat Shard from Bulczos catching a Billygoat!"
    private static final Pattern LOOT_SHARE_CATCHING =
            Pattern.compile("^LOOT SHARE! You received .+ catching (?:a|an|the) (?:SPARKLING )?([^!]+)!");

    // "LOOT SHARE! You received 3x Hideyho Shard from Bulczos finding the Hideyho!"
    private static final Pattern LOOT_SHARE_FINDING =
            Pattern.compile("^LOOT SHARE! You received .+ finding (?:the )?([^!]+)!");

    /** The complete Critterdex, grouped by Safari biome, in wiki/biome order. */
    private static final Map<String, List<String>> CRITTERS_BY_BIOME = new LinkedHashMap<>();

    /** Lowercase critter name → canonical display name, for validating parsed names. */
    private static final Map<String, String> CRITTER_LOOKUP = new LinkedHashMap<>();

    static {
        CRITTERS_BY_BIOME.put("Forest", List.of(
                "Foxtrot", "Bluebird", "Honeybug", "Treefrog", "Woodchucker",
                "Fluffling", "Hideonfloor", "Parakeet", "Macaw"));
        CRITTERS_BY_BIOME.put("Cavern", List.of(
                "Cavernfish", "Flitter", "Shyworm", "Driftling", "Chuckwalla",
                "Rockmite", "Scrappy", "Snoozle", "Gemzie"));
        CRITTERS_BY_BIOME.put("Icy", List.of(
                "Strongarm", "Tepid", "Polaris", "Shuddersquid", "Billygoat",
                "Mantis Shrimp", "Nozzlenose", "Troodon", "Wumpa"));
        CRITTERS_BY_BIOME.put("Haunted", List.of(
                "Areita", "Bloodbat", "Duplico", "Gazer", "Litterbug",
                "Solsnatcher", "Gimmiegold", "Hideonwall", "Hideyho", "Doomspiral"));

        for (List<String> critters : CRITTERS_BY_BIOME.values()) {
            for (String critter : critters) {
                CRITTER_LOOKUP.put(critter.toLowerCase(Locale.ROOT), critter);
            }
        }
    }

    /** Canonical names of the critters caught so far this run. */
    private static final Set<String> caught = new LinkedHashSet<>();

    private CritterSafariTracker() {}

    // -------------------------------------------------------------------------
    // Chat handling
    // -------------------------------------------------------------------------

    /**
     * Feeds a raw chat line (formatting codes allowed) to the tracker. Resets the run when the
     * Safari Manager's start line is seen, otherwise marks any recognised critter as caught.
     *
     * @return the canonical name of the critter marked caught, or null if the line was not a catch
     */
    public static String onChatMessage(String rawMessage) {
        if (rawMessage == null) return null;
        String message = strip(rawMessage);

        if (isRunStartMessage(message)) {
            reset();
            return null;
        }

        String critter = parseCaughtCritter(message);
        if (critter != null) {
            caught.add(critter);
        }
        return critter;
    }

    /**
     * Returns true if the line is the Safari Manager's "new run" confirmation.
     */
    public static boolean isRunStartMessage(String message) {
        if (message == null) return false;
        return strip(message).contains(RUN_START_MESSAGE);
    }

    /**
     * Extracts the critter named by a capture or loot-share chat line.
     * Returns null for any line that does not name a known critter, so unrelated chat (and the
     * shard counts in trailing parentheses) can never be mistaken for a catch.
     */
    public static String parseCaughtCritter(String message) {
        if (message == null) return null;
        String line = strip(message).trim();
        if (line.startsWith("CAPTURE!")) {
            String name = firstGroup(CAPTURE_CAUGHT, line);
            if (name == null) name = firstGroup(CAPTURE_FOUND, line);
            return canonicalise(name);
        }
        if (line.startsWith("LOOT SHARE!")) {
            String name = firstGroup(LOOT_SHARE_CATCHING, line);
            if (name == null) name = firstGroup(LOOT_SHARE_FINDING, line);
            return canonicalise(name);
        }
        return null;
    }

    private static String firstGroup(Pattern pattern, String line) {
        Matcher matcher = pattern.matcher(line);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Maps a parsed name onto a known critter (case-insensitive), or null if it isn't one.
     */
    private static String canonicalise(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return null;
        return CRITTER_LOOKUP.get(trimmed.toLowerCase(Locale.ROOT));
    }

    private static String strip(String message) {
        return FORMAT_STRIP.matcher(message).replaceAll("");
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    public static void reset() {
        caught.clear();
    }

    public static boolean isCaught(String critter) {
        return critter != null && caught.contains(critter);
    }

    public static int getCaughtCount() {
        return caught.size();
    }

    public static int getTotalCount() {
        return CRITTER_LOOKUP.size();
    }

    /** Biome names in display order. */
    public static List<String> getBiomes() {
        return new ArrayList<>(CRITTERS_BY_BIOME.keySet());
    }

    /** The critters belonging to a biome, in display order. */
    public static List<String> getCritters(String biome) {
        return CRITTERS_BY_BIOME.getOrDefault(biome, Collections.emptyList());
    }
}
