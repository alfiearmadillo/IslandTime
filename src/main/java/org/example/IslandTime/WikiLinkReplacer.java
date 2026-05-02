package org.example.islandtime;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.PlainTextContent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.net.URI;

public class WikiLinkReplacer {

    private static final String OFFICIAL   = "Official SkyBlock Wiki";
    private static final String UNOFFICIAL = "official SkyBlock Wiki"; // "Un" is prepended separately as bold

    private static boolean enabled = true;

    public static void register() {
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> {
            if (!enabled || overlay) return message;
            if (!containsHypixelContent(message)) return message;
            return rebuildWithReplacements(message, Style.EMPTY);  // [Bug 3] seed with EMPTY so inheritance works
        });
    }

    // -------------------------------------------------------------------------
    // Fast-path scan — only rebuild if there's something to change
    // -------------------------------------------------------------------------

    private static boolean containsHypixelContent(Text text) {
        ClickEvent ce = text.getStyle().getClickEvent();
        if (ce instanceof ClickEvent.OpenUrl openUrl
                && openUrl.uri().toString().startsWith("https://wiki.hypixel.net/")) {
            return true;
        }
        if (text.getContent() instanceof PlainTextContent plain
                && plain.string().contains(OFFICIAL)) {
            return true;
        }
        // [Bug 2] Also scan hover text
        HoverEvent he = text.getStyle().getHoverEvent();
        if (he instanceof HoverEvent.ShowText showText
                && containsHypixelContent(showText.value())) {
            return true;
        }
        for (Text sibling : text.getSiblings()) {
            if (containsHypixelContent(sibling)) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Tree rebuild — replaces URLs, "Official" label, and hover text
    // -------------------------------------------------------------------------

    /**
     * @param original       the node being rebuilt
     * @param inheritedStyle the effective style of this node's parent — used to
     *                       resolve colour and other formatting that isn't set
     *                       explicitly on this node (fixes bug 3).
     */
    private static Text rebuildWithReplacements(Text original, Style inheritedStyle) {
        Style ownStyle = original.getStyle();

        // effectiveStyle is what this node actually renders as, and what its
        // children inherit.  We need it before branching so both branches can use it.
        Style effectiveStyle = ownStyle.withParent(inheritedStyle);  // [Bug 3]

        // --- [Bug 2] Fix hover event: rebuild its text tree recursively ---
        Style newOwnStyle = ownStyle;
        HoverEvent hoverEvent = ownStyle.getHoverEvent();
        if (hoverEvent instanceof HoverEvent.ShowText showText) {
            if (containsHypixelContent(showText.value())) {
                Text fixedHover = rebuildWithReplacements(showText.value(), Style.EMPTY);
                newOwnStyle = newOwnStyle.withHoverEvent(new HoverEvent.ShowText(fixedHover));
            }
        }

        // --- [Bug 1] Pre-compute the transformed click URL so BOTH branches can use it ---
        Style styleWithFixedUrl = newOwnStyle;
        ClickEvent clickEvent = newOwnStyle.getClickEvent();
        if (clickEvent instanceof ClickEvent.OpenUrl openUrl) {
            String newUrl = transformUrl(openUrl.uri().toString());
            if (newUrl != null) {
                styleWithFixedUrl = newOwnStyle.withClickEvent(new ClickEvent.OpenUrl(URI.create(newUrl)));
            }
        }
        // The effective style that replacement literals should carry (colour + fixed URL)
        Style effectiveWithFixedUrl = styleWithFixedUrl.withParent(inheritedStyle);  // [Bug 1 + 3]

        // --- Text content: replace "Official SkyBlock Wiki" ---
        if (original.getContent() instanceof PlainTextContent plain) {
            String str = plain.string();
            int idx = str.indexOf(OFFICIAL);
            if (idx >= 0) {
                String before = str.substring(0, idx);
                String after  = str.substring(idx + OFFICIAL.length());

                // Recover the §6 (or whatever colour) that is active at the match
                // site inside the raw legacy-formatted string. The Style on this node
                // may have no explicit colour (the colour was encoded inline via §
                // codes), so without this the replacement literals would render white.
                String colorCode = activeColorCode(str, idx);

                // Use effectiveWithFixedUrl so the replacement literals carry
                // (a) the inherited colour  [Bug 3]
                // (b) the transformed click URL from this same node  [Bug 1]
                // Prepend colorCode so any inline §-encoded colour is also preserved.
                MutableText result = Text.empty();
                if (!before.isEmpty()) result.append(Text.literal(before).setStyle(effectiveWithFixedUrl));
                result.append(Text.literal(colorCode + "Un").setStyle(effectiveWithFixedUrl.withBold(true)));
                result.append(Text.literal(colorCode + UNOFFICIAL + after).setStyle(effectiveWithFixedUrl));

                for (Text sibling : original.getSiblings()) {
                    result.append(rebuildWithReplacements(sibling, effectiveStyle));
                }
                return result;
            }
        }

        // --- No text replacement needed for this node; copy and fix style ---
        MutableText result = original.copyContentOnly();
        result.setStyle(styleWithFixedUrl);  // carries fixed hover + fixed URL

        for (Text sibling : original.getSiblings()) {
            result.append(rebuildWithReplacements(sibling, effectiveStyle));
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Legacy colour-code helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the last §[0-9a-f] or §r code that is "active" just before
     * {@code upToIndex} in a legacy-formatted string, or "" if there is none.
     * A colour code overrides any previous colour; §r resets to none.
     */
    private static String activeColorCode(String s, int upToIndex) {
        String last = "";
        for (int i = 0; i < upToIndex - 1; i++) {
            if (s.charAt(i) == '§') {
                char code = Character.toLowerCase(s.charAt(i + 1));
                if ("0123456789abcdef".indexOf(code) >= 0) {
                    last = "§" + s.charAt(i + 1);   // colour code — remember it
                } else if (code == 'r') {
                    last = "§r";                      // reset — clears any active colour
                }
                // formatting codes (l, m, n, o) intentionally ignored here;
                // they don't affect colour and are already covered by the Style.
            }
        }
        return last;
    }

    // -------------------------------------------------------------------------
    // URL transform
    // -------------------------------------------------------------------------

    /**
     * Maps a hypixel wiki URL to the minecraft.wiki equivalent.
     * e.g. https://wiki.hypixel.net/VALKYRIE      -> https://hypixelskyblock.minecraft.wiki/w/Valkyrie
     *      https://wiki.hypixel.net/DIAMOND_SWORD -> https://hypixelskyblock.minecraft.wiki/w/Diamond_Sword
     */
    private static String transformUrl(String url) {
        if (url == null) return null;
        if (url.startsWith("https://wiki.hypixel.net/")) {
            String itemPath = url.substring("https://wiki.hypixel.net/".length());
            return "https://hypixelskyblock.minecraft.wiki/w/" + toTitleCase(itemPath);
        }
        return null;
    }

    /**
     * Converts ALL_CAPS_WITH_UNDERSCORES to Title_Case_With_Underscores.
     * VALKYRIE -> Valkyrie,  DIAMOND_SWORD -> Diamond_Sword
     */
    private static String toTitleCase(String input) {
        if (input == null || input.isEmpty()) return input;
        String[] parts = input.split("_");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append("_");
            String part = parts[i];
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0)));
                sb.append(part.substring(1).toLowerCase());
            }
        }
        return sb.toString();
    }

    public static void setEnabled(boolean value) { enabled = value; }
    public static boolean isEnabled()            { return enabled; }
}
