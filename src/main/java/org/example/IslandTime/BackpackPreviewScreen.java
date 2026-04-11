package org.example.islandtime;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.input.MouseInput;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Interactive backpack preview screen.
 *
 * Ported to Minecraft 1.21.11 (Yarn mappings).
 *
 * Key changes vs original:
 *   - keyPressed(int, int, int) → keyPressed(KeyInput input) (1.21.9+).
 *     Use input.isEscape() instead of comparing keyCode to GLFW_KEY_ESCAPE.
 *     The GLFW import is no longer needed here.
 *   - mouseClicked(double, double, int) → mouseClicked(double, double, MouseInput)
 *   - mouseDragged(double, double, int, double, double) →
 *       mouseDragged(double, double, MouseInput, double, double)
 *   - DrawContext.drawItemInSlot() renamed to drawStackOverlay() (1.21.8+).
 */
public class BackpackPreviewScreen extends Screen {

    // -------------------------------------------------------------------------
    // Layout constants
    // -------------------------------------------------------------------------

    private static final int SLOT_SIZE    = 18;
    private static final int SLOT_INNER   = 16;
    private static final int PADDING      = 12;
    private static final int COLS         = 9;
    private static final int TITLE_HEIGHT = 14;

    // Vanilla tooltip palette — matches the hover overlay exactly
    private static final int BG          = 0xF0100010;
    private static final int BORDER_TOP  = 0x505000FF;
    private static final int BORDER_BOT  = 0x5028007F;
    private static final int SLOT_BG     = 0x40000000;
    private static final int SLOT_HOVER  = 0x60FFFFFF;
    private static final int SEP_COLOR   = 0x44FFFFFF;
    private static final int COL_HINT    = 0xFF888888;
    private static final int COL_DIMMER  = 0x90000000;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final List<ItemStack> contents;
    private final Screen parent;

    private int panelX, panelY, panelW, panelH;
    private int rows;
    private int hoveredSlot  = -1;
    private int copiedSlot   = -1;
    private long copiedAtMs  = 0L;
    private static final long COPY_FLASH_MS = 500L;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public BackpackPreviewScreen(List<ItemStack> contents, Screen parent) {
        super(Text.literal("Backpack Preview"));
        this.contents = contents;
        this.parent   = parent;
        this.rows     = (int) Math.ceil(contents.size() / (double) COLS);
    }

    // -------------------------------------------------------------------------
    // Screen lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void init() {
        panelW = COLS * SLOT_SIZE + PADDING * 2;
        panelH = TITLE_HEIGHT + rows * SLOT_SIZE + PADDING * 2 + 10;
        panelX = (width  - panelW) / 2;
        panelY = (height - panelH) / 2;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (parent != null) {
            parent.render(context, -9999, -9999, delta);
        }

        context.fill(0, 0, width, height, COL_DIMMER);
        drawPanel(context, mouseX, mouseY);

        if (hoveredSlot >= 0 && hoveredSlot < contents.size()) {
            ItemStack hovered = contents.get(hoveredSlot);
            if (!hovered.isEmpty()) {
                MinecraftClient client = MinecraftClient.getInstance();
                List<Text> tooltip = hovered.getTooltip(
                        Item.TooltipContext.create(client.world),
                        client.player,
                        client.options.advancedItemTooltips
                                ? TooltipType.ADVANCED
                                : TooltipType.BASIC);
                context.drawTooltip(textRenderer, tooltip, mouseX, mouseY);
            }
        }
    }

    private void drawPanel(DrawContext context, int mouseX, int mouseY) {
        TextRenderer tr = textRenderer;

        // Vanilla-style border + background (matches hover overlay)
        context.fill(panelX - 1, panelY - 1,
                     panelX + panelW + 1, panelY + panelH + 1, BORDER_TOP);
        context.fill(panelX, panelY,
                     panelX + panelW, panelY + panelH, BG);
        context.fillGradient(panelX,            panelY + 1,
                             panelX + 1,         panelY + panelH - 1, BORDER_TOP, BORDER_BOT);
        context.fillGradient(panelX + panelW - 1, panelY + 1,
                             panelX + panelW,     panelY + panelH - 1, BORDER_TOP, BORDER_BOT);
        context.fill(panelX + 1, panelY,              panelX + panelW - 1, panelY + 1,          BORDER_TOP);
        context.fill(panelX + 1, panelY + panelH - 1, panelX + panelW - 1, panelY + panelH,     BORDER_BOT);

        // Title
        String title = "Backpack Contents  §8(" + contents.size() + " slots)";
        Text titleText = BackpackNbtParser.parseLegacy(title);
        int titleX = panelX + (panelW - tr.getWidth(titleText)) / 2;
        context.drawText(tr, titleText, titleX, panelY + 4, -1, true);

        // Separator under title
        int sepY = panelY + TITLE_HEIGHT;
        context.fill(panelX + 4, sepY, panelX + panelW - 4, sepY + 1, SEP_COLOR);

        int gridTop = panelY + TITLE_HEIGHT + PADDING;
        hoveredSlot = -1;

        for (int i = 0; i < contents.size(); i++) {
            int col = i % COLS;
            int row = i / COLS;
            int sx  = panelX + PADDING + col * SLOT_SIZE;
            int sy  = gridTop + row * SLOT_SIZE;

            context.fill(sx, sy, sx + SLOT_INNER, sy + SLOT_INNER, SLOT_BG);

            ItemStack slotItem = contents.get(i);
            if (!slotItem.isEmpty()) {
                context.drawItem(slotItem, sx, sy);
                context.drawStackOverlay(tr, slotItem, sx, sy);
            }

            if (mouseX >= sx && mouseX < sx + SLOT_INNER
                    && mouseY >= sy && mouseY < sy + SLOT_INNER) {
                hoveredSlot = i;
                context.fill(sx, sy, sx + SLOT_INNER, sy + SLOT_INNER, SLOT_HOVER);
            }

            // Green flash on copy
            if (i == copiedSlot) {
                long elapsed = System.currentTimeMillis() - copiedAtMs;
                if (elapsed < COPY_FLASH_MS) {
                    float alpha = 1.0f - (float) elapsed / COPY_FLASH_MS;
                    int a = (int) (alpha * 0xA0);
                    context.fill(sx, sy, sx + SLOT_INNER, sy + SLOT_INNER, (a << 24) | 0x0055FF00);
                } else {
                    copiedSlot = -1;
                }
            }
        }

        String hint = "E to close";
        int hintX = panelX + (panelW - tr.getWidth(hint)) / 2;
        context.drawText(tr, hint, hintX, panelY + panelH - 10, COL_HINT, false);
    }

    // -------------------------------------------------------------------------
    // Input
    // -------------------------------------------------------------------------

    /**
     * 1.21.9+: keyPressed now takes a {@link KeyInput} record instead of three
     * separate int parameters (keyCode, scanCode, modifiers).
     * Use {@code input.isEscape()} from the {@link net.minecraft.client.input.AbstractInput}
     * interface instead of comparing against GLFW constants.
     *
     * Ctrl+C copies the hovered item's full component data to the clipboard as
     * pretty-printed SNBT (same format as the /give command / NBT editors).
     */
    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.isEscape() || GLFW.glfwGetKey(MinecraftClient.getInstance().getWindow().getHandle(), GLFW.GLFW_KEY_E) == GLFW.GLFW_PRESS) {
            close();
            return true;
        }

        // Ctrl+C — copy hovered item data
        MinecraftClient client = MinecraftClient.getInstance();
        long window = client.getWindow().getHandle();
        boolean ctrlHeld = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL)  == GLFW.GLFW_PRESS
                            || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
        boolean ctrlC = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_C) == GLFW.GLFW_PRESS && ctrlHeld;
        if (ctrlC && hoveredSlot >= 0 && hoveredSlot < contents.size()) {
            ItemStack stack = contents.get(hoveredSlot);
            if (!stack.isEmpty()) {
                copyItemData(client, stack);
                return true;
            }
        }

        return super.keyPressed(input);
    }

    /**
     * Serialises {@code stack} to pretty-printed SNBT and puts it on the
     * system clipboard. The format matches Minecraft's internal component
     * encoding — identical to what you'd paste into an NBT editor or /give.
     */
    private void copyItemData(MinecraftClient client, ItemStack stack) {
        DynamicRegistryManager registries =
                client.world != null
                        ? client.world.getRegistryManager()
                        : client.getNetworkHandler() != null
                            ? client.getNetworkHandler().getRegistryManager()
                            : DynamicRegistryManager.EMPTY;

        ItemStack.CODEC
                .encodeStart(registries.getOps(NbtOps.INSTANCE), stack)
                .resultOrPartial(err -> System.err.println("[IslandTime] Copy encode failed: " + err))
                .ifPresent(nbt -> {
                    String snbt = NbtHelper.toFormattedString(nbt);
                    client.keyboard.setClipboard(snbt);
                    System.out.println("[IslandTime] Copied item data for: "
                            + stack.getName().getString());
                    copiedSlot  = hoveredSlot;
                    copiedAtMs  = System.currentTimeMillis();
                });
    }

    @Override
    public void close() {
        MinecraftClient.getInstance().setScreen(parent);
    }

    // -------------------------------------------------------------------------
    // Mouse – block clicks reaching the parent screen below
    // -------------------------------------------------------------------------

    /**
     * 1.21.9+: mouseClicked now takes a {@link MouseInput} record for the
     * button argument instead of a plain {@code int}.
     */
    public boolean mouseClicked(double mouseX, double mouseY, MouseInput input) {
        return true; // consume; do not forward to parent
    }

    /**
     * 1.21.9+: mouseDragged now takes a {@link MouseInput} record for the
     * button argument instead of a plain {@code int}.
     */
    public boolean mouseDragged(double mouseX, double mouseY, MouseInput input,
                                double deltaX, double deltaY) {
        return true;
    }
}
