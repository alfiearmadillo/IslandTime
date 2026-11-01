package org.example.islandtime;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import org.lwjgl.glfw.GLFW;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Opt-in diagnostic log of everything that happens in container GUIs, written to
 * {@code config/IslandTime/gui-debug/} while {@code /it guilog start} is running. It exists to
 * pin down fuel-slot bugs by showing, in order and with the exact item data, what the player
 * did, what the client predicted and what the server answered.
 *
 * <p>Every record is one line: {@code time +msSinceStart tTickNumber TYPE details}. Items are
 * described as {@code count "name" fp=<fingerprint> [sbId= uuid=]}; the full data of each distinct
 * item (components, lore, custom data) is written once as an {@code ITEMDEF} line the first time
 * its fingerprint is used, so {@code grep "fp=abc"} finds every use. The fingerprint covers the
 * count, so the same item at another count has its own fingerprint.
 *
 * <p>All entry points are cheap no-ops while the log is off, never throw, and are only meant to
 * be called from the client thread. Every line is flushed immediately so a crash loses nothing.
 */
public final class GuiDebugLog {

    private static final int WATCHED_SLOT = MinionFuelTracker.FUEL_SLOT_INDEX;
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final int MAX_VALUE_CHARS = 400;
    private static final int MAX_LIST_ENTRIES = 128;
    private static final int MAX_ERRORS = 20;

    private static final Path LOG_DIR = Paths.get("config", "IslandTime", "gui-debug");
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter LINE_STAMP = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static final String[] LEGEND = {
            "OPEN / OPEN_SLOT: a container screen opened, followed by every non-empty slot in it",
            "SLOT: a slot changed between two ticks (old -> new); CURSOR: the item on the mouse changed; STATE: the menu's state id changed",
            "CLOSE: the container screen closed; shows the menu's last known state (a click made in the same tick is included)",
            "CLICK_RAW: a mouse press in the GUI, before the game handles it; KEY: a key press in the GUI",
            "CLICK_SENT: the click the game is about to send to the server (slot, button, type) with the state before it",
            "CLICK_PREDICTED: that same click after the client applied its own prediction, before the server has answered",
            "PACKET_IN / PACKET_DONE: a container packet from the server just before / after the client applies it, with the client's state",
            "MINION_CLICK: an armor stand was right-clicked (how a minion GUI is attributed to a physical minion)",
            "TRACKER: a decision made by the minion fuel tracker; NOTE: a line added with /it guilog note",
            "ITEMDEF: full data of one item fingerprint, written once before its first use",
            "client state: screen, menu#containerId, stateId, slot " + WATCHED_SLOT + " (the fuel slot) and the carried (cursor) item",
    };

    private static final Object LOCK = new Object();
    private static volatile boolean active = false;
    // Set while the session is being wound up, so the final lines written then can't trigger it again.
    private static boolean stopping = false;
    private static BufferedWriter writer;
    private static Path file;
    private static long startMs;
    private static long bytesWritten;
    private static long lineCount;
    private static long tickCount;
    private static int errorCount;
    private static final Set<String> definedItems = new HashSet<>();
    private static final Map<String, Integer> eventCounts = new TreeMap<>();

    private static AbstractContainerMenu trackedMenu;
    private static String trackedTitle = "";
    private static long trackedSinceTick;
    private static ItemStack[] lastSlots = new ItemStack[0];
    private static ItemStack lastCarried = ItemStack.EMPTY;
    private static int lastStateId;

    private GuiDebugLog() {}

    public static boolean isActive() {
        return active;
    }

    public static Path currentFile() {
        return file;
    }

    // -------------------------------------------------------------------------
    // Session control
    // -------------------------------------------------------------------------

    /** Starts a new log file (or returns the one already being written). */
    public static Path start() throws IOException {
        synchronized (LOCK) {
            if (active) return file;

            Files.createDirectories(LOG_DIR);
            file = LOG_DIR.resolve("gui-debug-" + LocalDateTime.now().format(FILE_STAMP) + ".log").toAbsolutePath();
            writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);

            startMs = System.currentTimeMillis();
            bytesWritten = 0;
            lineCount = 0;
            tickCount = 0;
            errorCount = 0;
            definedItems.clear();
            eventCounts.clear();
            trackedMenu = null;
            trackedTitle = "";
            lastSlots = new ItemStack[0];
            lastCarried = ItemStack.EMPTY;
            stopping = false;
            active = true;

            write("SESSION_START", "islandtime=" + modVersion("islandtime") + " minecraft=" + modVersion("minecraft")
                    + " tracker{" + MinionFuelTracker.debugSettings() + "}");
            for (String legend : LEGEND) write("LEGEND", legend);
            return file;
        }
    }

    /** Ends the session and closes the file. Returns the file that was written, or null if none was running. */
    public static Path stop(String reason) {
        synchronized (LOCK) {
            if (!active) return null;
            return stopLocked(reason);
        }
    }

    private static Path stopLocked(String reason) {
        stopping = true;
        Minecraft client = Minecraft.getInstance();
        try {
            if (trackedMenu != null) closeTracked(client, "session ended");
        } catch (Throwable t) {
            // Closing the file matters more than one last snapshot.
        }
        write("SESSION_END", "reason=" + reason + " lines=" + lineCount + " counts=" + eventCounts);
        active = false;
        Path written = file;
        try {
            writer.close();
        } catch (IOException ignored) {
        }
        writer = null;
        trackedMenu = null;
        return written;
    }

    public static String status() {
        synchronized (LOCK) {
            if (!active) return "GUI debug log is off.";
            return "GUI debug log is ON: " + lineCount + " lines, " + (bytesWritten / 1024) + " KB written to " + file
                    + " (events so far: " + eventCounts + ")";
        }
    }

    public static void note(String text) {
        write("NOTE", text);
    }

    /** A decision made by {@link MinionFuelTracker}. */
    public static void tracker(String message) {
        write("TRACKER", message);
    }

    public static void event(String type, String message) {
        write(type, message);
    }

    // -------------------------------------------------------------------------
    // Per-tick menu tracking
    // -------------------------------------------------------------------------

    /** Call once per client tick, before anything that reacts to the menu's contents. */
    public static void tick(Minecraft client) {
        if (!active) return;
        tickCount++;
        try {
            AbstractContainerScreen<?> screen = null;
            AbstractContainerMenu menu = null;
            if (client.player != null && client.screen instanceof AbstractContainerScreen<?> containerScreen) {
                screen = containerScreen;
                menu = client.player.containerMenu;
            }

            if (menu != trackedMenu) {
                if (trackedMenu != null) {
                    closeTracked(client, menu == null ? "screen closed" : "menu replaced");
                }
                if (menu != null) openTracked(client, screen, menu);
            } else if (menu != null) {
                logDiff(menu);
            }
        } catch (Throwable t) {
            error("tick", t);
        }
    }

    private static void openTracked(Minecraft client, AbstractContainerScreen<?> screen, AbstractContainerMenu menu) {
        trackedMenu = menu;
        trackedTitle = screen.getTitle().getString();
        trackedSinceTick = tickCount;

        int slotCount = menu.slots.size();
        lastSlots = new ItemStack[slotCount];
        for (int i = 0; i < slotCount; i++) lastSlots[i] = menu.getSlot(i).getItem().copy();
        lastCarried = menu.getCarried().copy();
        lastStateId = menu.getStateId();

        write("OPEN", "screen=" + typeName(screen) + " title=\"" + trackedTitle + "\" menu=" + typeName(menu)
                + " containerId=" + menu.containerId + " stateId=" + lastStateId + " slots=" + slotCount
                + " carried=" + ref(lastCarried));
        for (int i = 0; i < slotCount; i++) {
            if (!lastSlots[i].isEmpty()) write("OPEN_SLOT", "slot=" + i + " item=" + ref(lastSlots[i]));
        }
    }

    private static void closeTracked(Minecraft client, String reason) {
        AbstractContainerMenu menu = trackedMenu;
        // The menu keeps what it last showed after closing, so anything that changed since the
        // previous tick (including a click made in the same tick as the close) is still visible.
        logDiff(menu);

        String watched = menu instanceof ChestMenu && menu.slots.size() > WATCHED_SLOT
                ? " slot" + WATCHED_SLOT + "=" + ref(menu.getSlot(WATCHED_SLOT).getItem())
                : "";
        write("CLOSE", "reason=" + reason + " title=\"" + trackedTitle + "\" containerId=" + menu.containerId
                + " stateId=" + menu.getStateId() + " openForTicks=" + (tickCount - trackedSinceTick) + watched
                + " carried=" + ref(menu.getCarried()));

        trackedMenu = null;
        lastSlots = new ItemStack[0];
    }

    private static void logDiff(AbstractContainerMenu menu) {
        int count = Math.min(menu.slots.size(), lastSlots.length);
        for (int i = 0; i < count; i++) {
            ItemStack now = menu.getSlot(i).getItem();
            if (!ItemStack.matches(now, lastSlots[i])) {
                write("SLOT", "slot=" + i + " " + ref(lastSlots[i]) + " -> " + ref(now));
                lastSlots[i] = now.copy();
            }
        }

        ItemStack carried = menu.getCarried();
        if (!ItemStack.matches(carried, lastCarried)) {
            write("CURSOR", ref(lastCarried) + " -> " + ref(carried));
            lastCarried = carried.copy();
        }

        int stateId = menu.getStateId();
        if (stateId != lastStateId) {
            write("STATE", "stateId " + lastStateId + " -> " + stateId);
            lastStateId = stateId;
        }
    }

    // -------------------------------------------------------------------------
    // Input and network hooks (called from the mixins)
    // -------------------------------------------------------------------------

    public static void onRawClick(Slot hovered, int button, int modifiers, boolean doubleClick, boolean blocked) {
        if (!active) return;
        try {
            Minecraft client = Minecraft.getInstance();
            write("CLICK_RAW", "button=" + buttonName(button) + " modifiers=" + modifierNames(modifiers)
                    + " double=" + doubleClick + " blockedByMod=" + blocked + " " + hoveredText(hovered)
                    + " | client " + clientState(client));
        } catch (Throwable t) {
            error("raw click", t);
        }
    }

    public static void onKey(int key, Slot hovered) {
        if (!active) return;
        try {
            Minecraft client = Minecraft.getInstance();
            write("KEY", "key=" + keyName(key) + " " + hoveredText(hovered) + " | client " + clientState(client));
        } catch (Throwable t) {
            error("key", t);
        }
    }

    /**
     * The click the game sends to the server. Called before it runs ({@code predicted} false) and
     * after the client has applied its own prediction of the result ({@code predicted} true).
     */
    public static void onContainerInput(boolean predicted, int containerId, int slotId, int button, Object input) {
        if (!active) return;
        try {
            Minecraft client = Minecraft.getInstance();
            String clicked = "none";
            if (client.player != null) {
                AbstractContainerMenu menu = client.player.containerMenu;
                if (slotId >= 0 && slotId < menu.slots.size()) clicked = ref(menu.getSlot(slotId).getItem());
            }
            write(predicted ? "CLICK_PREDICTED" : "CLICK_SENT", "containerId=" + containerId + " slot=" + slotId
                    + " button=" + button + " type=" + input + " clickedSlotItem=" + clicked
                    + " | client " + clientState(client));
        } catch (Throwable t) {
            error("container input", t);
        }
    }

    /**
     * A container-related packet from the server. Packet handlers run once on the network thread
     * (which only hands the packet over) and again on the client thread where it takes effect;
     * only the latter is recorded.
     */
    public static void onPacket(boolean applied, Object packet) {
        if (!active) return;
        try {
            Minecraft client = Minecraft.getInstance();
            if (!client.isSameThread()) return;
            if (applied) {
                write("PACKET_DONE", typeName(packet) + " | client " + clientState(client));
            } else {
                write("PACKET_IN", describePacket(packet) + " | client " + clientState(client));
            }
        } catch (Throwable t) {
            error("packet", t);
        }
    }

    // -------------------------------------------------------------------------
    // Describing things
    // -------------------------------------------------------------------------

    /** One-line description of a stack; its full data is written the first time it is seen. */
    public static String describe(ItemStack stack) {
        return ref(stack);
    }

    private static String ref(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "empty";
        synchronized (LOCK) {
            String snbt = snbt(stack);
            String fingerprint = fingerprint(snbt);
            if (definedItems.add(fingerprint)) defineItem(fingerprint, stack, snbt);
            return stack.getCount() + "x \"" + stack.getHoverName().getString() + "\" fp=" + fingerprint
                    + identityOf(stack);
        }
    }

    private static void defineItem(String fingerprint, ItemStack stack, String snbt) {
        StringBuilder text = new StringBuilder("fp=").append(fingerprint)
                .append(" name=\"").append(stack.getHoverName().getString()).append('"');
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore != null && !lore.lines().isEmpty()) {
            text.append(" lore=[");
            for (int i = 0; i < lore.lines().size(); i++) {
                if (i > 0) text.append(" | ");
                text.append(lore.lines().get(i).getString());
            }
            text.append(']');
        }
        text.append(" data=").append(snbt);
        write("ITEMDEF", text.toString());
    }

    /** The SkyBlock id and uuid, when the item has them, so identity can be compared at a glance. */
    private static String identityOf(ItemStack stack) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null || customData.isEmpty()) return "";
        CompoundTag tag = customData.copyTag();
        String id = tag.getStringOr("id", "");
        String uuid = tag.getStringOr("uuid", "");
        StringBuilder text = new StringBuilder();
        if (!id.isEmpty()) text.append(" sbId=").append(id);
        if (!uuid.isEmpty()) text.append(" uuid=").append(uuid);
        return text.toString();
    }

    private static String snbt(ItemStack stack) {
        Minecraft client = Minecraft.getInstance();
        RegistryAccess registries = client.getConnection() != null
                ? client.getConnection().registryAccess()
                : RegistryAccess.EMPTY;
        try {
            return ItemStack.CODEC
                    .encode(stack, RegistryOps.create(NbtOps.INSTANCE, registries), new CompoundTag())
                    .result()
                    .map(Object::toString)
                    .orElse("<could not encode item>");
        } catch (RuntimeException e) {
            return "<could not encode item: " + e + ">";
        }
    }

    private static String fingerprint(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 5; i++) hex.append(String.format("%02x", digest[i]));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    /**
     * Describes a packet by reading its fields reflectively, so it keeps working whatever the
     * packet class looks like in a given game version.
     */
    private static String describePacket(Object packet) {
        StringBuilder text = new StringBuilder(typeName(packet));
        for (Field field : packet.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            text.append(' ').append(field.getName()).append('=');
            try {
                field.setAccessible(true);
                text.append(describeValue(field.get(packet)));
            } catch (Throwable t) {
                text.append("<unreadable ").append(t.getClass().getSimpleName()).append('>');
            }
        }
        return text.toString();
    }

    private static String describeValue(Object value) {
        if (value == null) return "null";
        if (value instanceof ItemStack stack) return ref(stack);
        if (value instanceof Component component) return "\"" + component.getString() + "\"";
        if (value instanceof List<?> list) {
            StringBuilder text = new StringBuilder("list(size=" + list.size() + "){");
            boolean first = true;
            for (int i = 0; i < list.size() && i < MAX_LIST_ENTRIES; i++) {
                Object element = list.get(i);
                if (element instanceof ItemStack stack && stack.isEmpty()) continue;
                if (!first) text.append(", ");
                first = false;
                text.append(i).append(':').append(describeValue(element));
            }
            return text.append('}').toString();
        }
        String text = String.valueOf(value);
        return text.length() > MAX_VALUE_CHARS ? text.substring(0, MAX_VALUE_CHARS) + "..." : text;
    }

    /** The state of the open menu at this moment, in the same terms for every hook. */
    private static String clientState(Minecraft client) {
        if (client.player == null) return "player=none";
        AbstractContainerMenu menu = client.player.containerMenu;
        StringBuilder text = new StringBuilder("screen=")
                .append(client.screen == null ? "none" : typeName(client.screen))
                .append(" menu=").append(typeName(menu)).append('#').append(menu.containerId)
                .append(" stateId=").append(menu.getStateId());
        if (menu instanceof ChestMenu && menu.slots.size() > WATCHED_SLOT) {
            text.append(" slot").append(WATCHED_SLOT).append('=').append(ref(menu.getSlot(WATCHED_SLOT).getItem()));
        }
        return text.append(" carried=").append(ref(menu.getCarried())).toString();
    }

    private static String hoveredText(Slot hovered) {
        if (hovered == null) return "hoveredSlot=none";
        return "hoveredSlot=" + hovered.index + " hoveredItem=" + ref(hovered.getItem());
    }

    private static String typeName(Object object) {
        String simple = object.getClass().getSimpleName();
        return simple.isEmpty() ? object.getClass().getName() : simple;
    }

    private static String buttonName(int button) {
        return switch (button) {
            case GLFW.GLFW_MOUSE_BUTTON_LEFT -> "left";
            case GLFW.GLFW_MOUSE_BUTTON_RIGHT -> "right";
            case GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> "middle";
            default -> "button" + button;
        };
    }

    private static String modifierNames(int modifiers) {
        StringBuilder text = new StringBuilder("[");
        if ((modifiers & GLFW.GLFW_MOD_SHIFT) != 0) text.append("shift ");
        if ((modifiers & GLFW.GLFW_MOD_CONTROL) != 0) text.append("ctrl ");
        if ((modifiers & GLFW.GLFW_MOD_ALT) != 0) text.append("alt ");
        if (text.length() > 1) text.setLength(text.length() - 1);
        return text.append(']').toString();
    }

    private static String keyName(int key) {
        String name = switch (key) {
            case GLFW.GLFW_KEY_ESCAPE -> "ESC";
            case GLFW.GLFW_KEY_E -> "E";
            case GLFW.GLFW_KEY_Q -> "Q";
            case GLFW.GLFW_KEY_F -> "F";
            case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> "SHIFT";
            case GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL -> "CTRL";
            default -> key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9 ? "hotbar" + (key - GLFW.GLFW_KEY_0) : "?";
        };
        return name + "(" + key + ")";
    }

    private static String modVersion(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    // -------------------------------------------------------------------------
    // Writing
    // -------------------------------------------------------------------------

    private static void write(String type, String body) {
        if (!active) return;
        synchronized (LOCK) {
            if (!active) return;
            eventCounts.merge(type, 1, Integer::sum);
            String line = LocalTime.now().format(LINE_STAMP) + " +" + (System.currentTimeMillis() - startMs)
                    + "ms t" + tickCount + " " + type + " " + body.replace("\r", "\\r").replace("\n", "\\n");
            try {
                writer.write(line);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                active = false;
                System.err.println("[GuiDebugLog] Could not write to " + file + ", logging stopped: " + e);
                return;
            }
            bytesWritten += line.length() + 1L;
            lineCount++;
            if (bytesWritten > MAX_BYTES && !stopping) {
                stopLocked("size limit of " + (MAX_BYTES / (1024 * 1024)) + " MB reached");
            }
        }
    }

    private static void error(String where, Throwable t) {
        if (errorCount++ < MAX_ERRORS) write("ERROR", where + " failed: " + t);
    }
}
