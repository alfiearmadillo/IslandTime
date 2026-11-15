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
                List.of(new TextField("PNG file path or texture hash", "", 260, "")),
                List.of(new ChoiceField("Model", List.of("Classic", "Slim"), slim ? 1 : 0)),
                (texts, choices) -> {
                    String input = texts[0].trim();
                    if (input.isEmpty()) return "Enter the path of a skin PNG, or a texture hash";
                    boolean chosenSlim = choices[0] == 1;
                    slim = chosenSlim;
                    setStatus("Changing the skin...", true);
                    if (input.matches(HASH_PATTERN)) {
                        SkinActions.fromTexture(input, chosenSlim, reporter);
                    } else {
                        SkinActions.fromFile(input, chosenSlim, reporter);
                    }
                    return null;
                });
    }

    @Override
    protected List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        if (profile == null) return rows;

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
        return rows;
    }
}
