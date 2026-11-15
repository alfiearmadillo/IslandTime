package org.example.islandtime;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

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
    private static final String HASH_PATTERN = "[0-9a-fA-F]{16,64}";

    private SkinService.Profile profile;
    private boolean slim = false;
    private boolean loading = true;
    private Button modelButton;

    public SkinScreen(Screen parent) {
        super(parent, "Skin & Capes");
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
        profile = loaded;
        slim = "slim".equals(loaded.skinVariant());
        if (modelButton != null) modelButton.setMessage(modelLabel());
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
        return Component.literal("Model: " + (slim ? "Slim" : "Classic"));
    }

    @Override
    protected void addHeaderWidgets() {
        int[] widths = {85, 90, 85, 80};
        int total = BUTTON_GAP * 3;
        for (int w : widths) total += w;
        int x = width / 2 - total / 2;

        addRenderableWidget(Button.builder(Component.literal("Paste Skin"), btn -> {
                    setStatus("Reading the clipboard...", true);
                    SkinActions.paste(slim, reporter);
                })
                .bounds(x, HEADER_Y, widths[0], 20).build());
        x += widths[0] + BUTTON_GAP;

        addRenderableWidget(Button.builder(Component.literal("Set Skin..."), btn -> openSetSkin())
                .bounds(x, HEADER_Y, widths[1], 20).build());
        x += widths[1] + BUTTON_GAP;

        modelButton = addRenderableWidget(Button.builder(modelLabel(), btn -> {
                    slim = !slim;
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
                List.of(new ChoiceField("Model", List.of("Classic", "Slim"), slim ? 1 : 0)),
                (texts, choices) -> {
                    String input = texts[0].trim();
                    String saveAs = texts[1].trim();
                    if (input.isEmpty()) return "Enter the path of a skin PNG, or a texture hash";
                    if (!saveAs.isEmpty()) {
                        String problem = SkinLibrary.validateName(saveAs, null);
                        if (problem != null) return problem;
                    }
                    boolean chosenSlim = choices[0] == 1;
                    slim = chosenSlim;
                    setStatus("Changing the skin...", true);
                    // Saving reads back what the account now has, so it only follows a change that worked
                    SkinActions.Reporter then = (message, ok, loaded) -> {
                        reporter.report(message, ok, loaded);
                        if (ok && !saveAs.isEmpty()) SkinActions.saveCurrent(saveAs, reporter);
                    };
                    if (input.matches(HASH_PATTERN)) {
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
        if (profile != null) addCapeRows(rows);

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

    private void addCapeRows(List<Row> rows) {
        boolean anyActive = profile.capes().stream().anyMatch(SkinService.Cape::active);
        List<RowButton> hide = new ArrayList<>();
        if (anyActive) {
            hide.add(button("Use", USE_WIDTH, 0xFF55FF55, () -> {
                setStatus("Hiding the cape...", true);
                SkinActions.hideCape(reporter);
            }, "Wear no cape"));
        }
        rows.add(new Row("No cape", anyActive ? "" : "Active", anyActive ? 0 : 0x5000AA00, hide));

        for (SkinService.Cape cape : profile.capes()) {
            List<RowButton> buttons = new ArrayList<>();
            if (!cape.active()) {
                buttons.add(button("Use", USE_WIDTH, 0xFF55FF55, () -> {
                    setStatus("Changing the cape...", true);
                    SkinActions.setCape(cape, reporter);
                }, "Wear this cape"));
            }
            rows.add(new Row(cape.alias(), cape.active() ? "Active" : "Owned", cape.active() ? 0x5000AA00 : 0, buttons));
        }
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
