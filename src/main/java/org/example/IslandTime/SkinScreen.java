package org.example.islandtime;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Change the skin and cape of the account in use without leaving the game: paste a skin from the clipboard, set one
 * from a file or a texture hash (the one a player head wears), pick a cape or hide it. The change goes to your Mojang
 * account straight away, but servers read your skin when you join, so it shows to others after you reconnect.
 */
public class SkinScreen extends ListEditorScreen {

    private static final int USE_WIDTH = 40;
    private static final int SAVE_WIDTH = 40;
    private static final int RENAME_WIDTH = 52;
    private static final int REMOVE_WIDTH = 20;

    private static final int CAPE_COLUMNS = 8;
    private static final int CAPE_GAP = 4;
    private static final int CAPE_TILE_HEIGHT = CapeTextures.FACE_HEIGHT + 6;

    private static final List<String> MODELS = List.of("Auto", "Classic", "Slim");

    private SkinService.Profile profile;
    private int model = 0;
    private boolean loading = true;
    private int mouseX;
    private int mouseY;

    public SkinScreen(Screen parent) {
        super(parent, "Skin & Capes");
        CapeTextures.forgetFailures();
        SkinActions.refresh((message, ok, loaded) -> {
            loading = false;
            if (ok) {
                accept(loaded);
            } else {
                setStatus(message, false);
            }
        });
    }

    private void accept(SkinService.Profile loaded) {
        int before = listTop();
        profile = loaded;
        // The list sits under the cape tiles, so it moves when the number of tiles changes
        if (width > 0 && listTop() != before) rebuildWidgets();
    }

    /** The model to send, or null to pick it from the skin's arms. */
    private Boolean chosenModel() {
        return model == 0 ? null : model == 2;
    }

    private final SkinActions.Reporter reporter = (message, ok, loaded) -> {
        if (ok && loaded != null) accept(loaded);
        if (!message.isEmpty()) setStatus(message, ok);
    };

    @Override
    protected String screenTitle() {
        return "Skin & Capes";
    }

    @Override
    protected String emptyMessage() {
        return loading ? "Loading..." : "No capes on this account.";
    }

    @Override
    protected String idleStatus() {
        return "Changes show to others after you reconnect";
    }

    private Component modelLabel() {
        return Component.literal("Model: " + MODELS.get(model));
    }

    @Override
    protected void addHeaderWidgets() {
        int[] widths = {85, 90, 85, 80};
        int total = BUTTON_GAP * 3;
        for (int w : widths) total += w;
        int x = width / 2 - total / 2;

        addRenderableWidget(Button.builder(Component.literal("Paste Skin"), btn -> {
                    setStatus("Reading the clipboard...", true);
                    SkinActions.paste(chosenModel(), reporter);
                })
                .bounds(x, HEADER_Y, widths[0], 20).build());
        x += widths[0] + BUTTON_GAP;

        addRenderableWidget(Button.builder(Component.literal("Set Skin..."), btn -> openSetSkin())
                .bounds(x, HEADER_Y, widths[1], 20).build());
        x += widths[1] + BUTTON_GAP;

        addRenderableWidget(Button.builder(modelLabel(), btn -> {
                    model = (model + 1) % MODELS.size();
                    btn.setMessage(modelLabel());
                })
                .bounds(x, HEADER_Y, widths[2], 20).build());
        x += widths[2] + BUTTON_GAP;

        addRenderableWidget(Button.builder(Component.literal("Reset Skin"), btn -> {
                    setStatus("Resetting the skin...", true);
                    SkinActions.reset(reporter);
                })
                .bounds(x, HEADER_Y, widths[3], 20).build());
    }

    private void openSetSkin() {
        openForm("Set Skin", "Apply",
                List.of(new TextField("PNG file path or texture hash", "", 260, ""),
                        new TextField("Also save it as (optional)", "", SkinLibrary.MAX_NAME_LENGTH, "")),
                List.of(new ChoiceField("Model", MODELS, model)),
                (texts, choices) -> {
                    String input = texts[0].trim();
                    String saveAs = texts[1].trim();
                    if (input.isEmpty()) return "Enter the path of a skin PNG, or a texture hash";
                    if (!saveAs.isEmpty()) {
                        String problem = SkinLibrary.validateName(saveAs, null);
                        if (problem != null) return problem;
                    }
                    model = choices[0];
                    Boolean chosenSlim = chosenModel();
                    setStatus("Changing the skin...", true);
                    // Saving reads back what the account now has, so it only follows a change that worked
                    SkinActions.Reporter then = (message, ok, loaded) -> {
                        reporter.report(message, ok, loaded);
                        if (ok && !saveAs.isEmpty()) SkinActions.saveCurrent(saveAs, reporter);
                    };
                    if (SkinService.parseTextureHash(input) != null) {
                        SkinActions.fromTexture(input, chosenSlim, then);
                    } else {
                        SkinActions.fromFile(input, chosenSlim, then);
                    }
                    return null;
                });
    }

    @Override
    protected List<Row> rows() {
        List<Row> rows = new ArrayList<>();

        rows.add(new Row("Save current skin", "Keeps the skin with its model and cape, to switch back to later", 0x30FFFFFF,
                List.of(button("Save", SAVE_WIDTH, 0xFFFFFF55, this::openSave,
                        "Save the skin the account has now under a name"))));

        for (SkinLibrary.Entry entry : SkinLibrary.all()) {
            List<RowButton> buttons = new ArrayList<>();
            buttons.add(button("Use", USE_WIDTH, 0xFF55FF55, () -> {
                setStatus("Applying \"" + entry.name + "\"...", true);
                SkinActions.useSaved(entry, reporter);
            }, "Make this your skin" + (entry.capeId != null ? " and put its cape back on" : "")));
            buttons.add(button("Rename", RENAME_WIDTH, 0xFFFFFF55, () -> openRename(entry), "Give it another name"));
            buttons.add(confirmButton("X", "?", REMOVE_WIDTH, 0xFFFF5555, "skin:" + entry.id,
                    () -> remove(entry), "Delete this saved skin"));
            rows.add(new Row(entry.name, describe(entry), 0, buttons));
        }
        return rows;
    }

    // -------------------------------------------------------------------------
    // Cape tiles: "no cape" first, then each cape the account owns
    // -------------------------------------------------------------------------

    private int tileCount() {
        return profile == null ? 0 : profile.capes().size() + 1;
    }

    private int tileRows() {
        return (tileCount() + CAPE_COLUMNS - 1) / CAPE_COLUMNS;
    }

    private int tileWidth() {
        return (contentWidth() - (CAPE_COLUMNS - 1) * CAPE_GAP) / CAPE_COLUMNS;
    }

    private int tileX(int index) {
        int used = CAPE_COLUMNS * tileWidth() + (CAPE_COLUMNS - 1) * CAPE_GAP;
        return contentLeft() + (contentWidth() - used) / 2 + (index % CAPE_COLUMNS) * (tileWidth() + CAPE_GAP);
    }

    private int tileY(int index) {
        return DEFAULT_LIST_TOP + (index / CAPE_COLUMNS) * (CAPE_TILE_HEIGHT + CAPE_GAP);
    }

    @Override
    protected int listTop() {
        int tiles = tileRows();
        return tiles == 0 ? DEFAULT_LIST_TOP : DEFAULT_LIST_TOP + tiles * (CAPE_TILE_HEIGHT + CAPE_GAP) + 2;
    }

    private int tileAt(double x, double y) {
        for (int i = 0; i < tileCount(); i++) {
            if (isInBounds(x, y, tileX(i), tileY(i), tileWidth(), CAPE_TILE_HEIGHT)) return i;
        }
        return -1;
    }

    private boolean isTileActive(int index) {
        if (index == 0) return profile.capes().stream().noneMatch(SkinService.Cape::active);
        return profile.capes().get(index - 1).active();
    }

    private void chooseTile(int index) {
        if (isTileActive(index)) return;
        if (index == 0) {
            setStatus("Hiding the cape...", true);
            SkinActions.hideCape(reporter);
        } else {
            setStatus("Changing the cape...", true);
            SkinActions.setCape(profile.capes().get(index - 1), reporter);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        super.extractRenderState(context, mouseX, mouseY, delta);
    }

    @Override
    protected void onFrame(GuiGraphicsExtractor context) {
        int hovered = tileAt(mouseX, mouseY);
        for (int i = 0; i < tileCount(); i++) {
            int x = tileX(i);
            int y = tileY(i);
            int w = tileWidth();
            boolean active = isTileActive(i);

            context.fill(x, y, x + w, y + CAPE_TILE_HEIGHT, i == hovered ? 0x80FFFFFF : active ? 0x5000AA00 : 0x40000000);
            if (active) context.outline(x, y, w, CAPE_TILE_HEIGHT, 0xFF55FF55);

            if (i == 0) {
                Component none = Component.literal("None").withStyle(ChatFormatting.GRAY);
                context.text(font, none, x + w / 2 - font.width(none) / 2, y + CAPE_TILE_HEIGHT / 2 - 4, 0xFFAAAAAA, true);
                continue;
            }
            SkinService.Cape cape = profile.capes().get(i - 1);
            int faceX = x + (w - CapeTextures.FACE_WIDTH) / 2;
            int faceY = y + (CAPE_TILE_HEIGHT - CapeTextures.FACE_HEIGHT) / 2;
            Identifier texture = CapeTextures.get(cape);
            if (texture != null) {
                context.blit(RenderPipelines.GUI_TEXTURED, texture, faceX, faceY, 0, 0,
                        CapeTextures.FACE_WIDTH, CapeTextures.FACE_HEIGHT,
                        CapeTextures.FACE_WIDTH, CapeTextures.FACE_HEIGHT);
            } else {
                String shown = fit(cape.alias(), w - 4);
                context.text(font, Component.literal(shown), x + w / 2 - font.width(shown) / 2,
                        y + CAPE_TILE_HEIGHT / 2 - 4, 0xFFFFFFFF, true);
            }
        }

        if (hovered >= 0) {
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(hovered == 0 ? "No cape" : profile.capes().get(hovered - 1).alias())
                    .withStyle(ChatFormatting.WHITE));
            tip.add(Component.literal(isTileActive(hovered) ? "Active" : hovered == 0 ? "Click to wear no cape" : "Click to wear this cape")
                    .withStyle(isTileActive(hovered) ? ChatFormatting.GREEN : ChatFormatting.GRAY));
            context.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!isDialogOpen() && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            int tile = tileAt(event.x(), event.y());
            if (tile >= 0) {
                chooseTile(tile);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private String describe(SkinLibrary.Entry entry) {
        String model = entry.slim ? "Slim model" : "Classic model";
        if (entry.capeId == null) return model;
        if (entry.capeId.isEmpty()) return model + ", no cape";
        String alias = entry.capeAlias == null || entry.capeAlias.isEmpty() ? "cape" : entry.capeAlias;
        boolean owned = profile == null || profile.capes().stream().anyMatch(cape -> cape.id().equals(entry.capeId));
        return model + ", " + alias + (owned ? "" : " (not on this account)");
    }

    private void openSave() {
        openForm("Save Current Skin", "Save",
                List.of(new TextField("Name", "", SkinLibrary.MAX_NAME_LENGTH, "e.g. Summer")),
                List.of(),
                (texts, choices) -> {
                    String problem = SkinLibrary.validateName(texts[0], null);
                    if (problem != null) return problem;
                    setStatus("Saving the skin...", true);
                    SkinActions.saveCurrent(texts[0].trim(), reporter);
                    return null;
                });
    }

    private void openRename(SkinLibrary.Entry entry) {
        openForm("Rename Skin", "Rename",
                List.of(new TextField("Name", entry.name, SkinLibrary.MAX_NAME_LENGTH, "")),
                List.of(),
                (texts, choices) -> {
                    try {
                        String problem = SkinLibrary.validateName(texts[0], entry.id);
                        if (problem != null) return problem;
                        SkinLibrary.rename(entry.id, texts[0]);
                        setStatus("Renamed to \"" + texts[0].trim() + "\".", true);
                    } catch (java.io.IOException e) {
                        return e.getMessage();
                    }
                    return null;
                });
    }

    private void remove(SkinLibrary.Entry entry) {
        try {
            SkinLibrary.remove(entry.id);
            setStatus("Deleted \"" + entry.name + "\".", true);
        } catch (java.io.IOException e) {
            setStatus("Could not delete it: " + e.getMessage(), false);
        }
    }
}
