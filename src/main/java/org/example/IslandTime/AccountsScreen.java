package org.example.islandtime;

import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The accounts signed in through the mod. Adding one logs it in through Microsoft's code page, after which it can be
 * switched to at any time and its login is renewed without leaving the server you're on. See {@link AccountManager}.
 */
public class AccountsScreen extends ListEditorScreen {

    private static final int INFO_Y = 58;
    private static final int CODE_Y = 70;
    private static final int USE_WIDTH = 35;
    private static final int REMOVE_WIDTH = 20;

    private Button addButton;
    private Button pageButton;

    public AccountsScreen(Screen parent) {
        super(parent, "Accounts");
    }

    @Override
    protected String screenTitle() {
        return "Accounts";
    }

    @Override
    protected String emptyMessage() {
        return "No saved accounts. Add one to renew its login in game.";
    }

    @Override
    protected String idleStatus() {
        String status = AccountManager.status();
        return status.isEmpty() ? "Accounts are saved encrypted, on this computer only." : status;
    }

    @Override
    protected int listTop() {
        return 86;
    }

    @Override
    protected void addHeaderWidgets() {
        int total = 110 + 100 + 90 + BUTTON_GAP * 2;
        int x = width / 2 - total / 2;
        addButton = addRenderableWidget(Button.builder(Component.literal("+ Add Account"), btn -> {
                    if (AccountManager.pendingCode() != null) {
                        AccountManager.cancelLogin();
                    } else {
                        AccountManager.startLogin();
                    }
                })
                .bounds(x, HEADER_Y, 110, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Renew Login"), btn -> AccountManager.renewCurrent())
                .bounds(x + 110 + BUTTON_GAP, HEADER_Y, 100, 20).build());
        pageButton = addRenderableWidget(Button.builder(Component.literal("Open Page"), btn -> {
                    MsAuth.DeviceCode code = AccountManager.pendingCode();
                    if (code != null) Util.getPlatform().openUri(code.verificationUri());
                })
                .bounds(x + 110 + 100 + BUTTON_GAP * 2, HEADER_Y, 90, 20).build());
    }

    @Override
    protected void onFrame(GuiGraphicsExtractor context) {
        MsAuth.DeviceCode code = AccountManager.pendingCode();
        if (addButton != null) {
            addButton.setMessage(Component.literal(code != null ? "Cancel Login" : "+ Add Account"));
        }
        if (pageButton != null) pageButton.active = code != null;

        Minecraft client = Minecraft.getInstance();
        String name = client.getUser().getName();
        long expiry = AccountManager.currentTokenExpiryMs();
        long now = System.currentTimeMillis();
        String expires;
        int color = 0xFFAAAAAA;
        if (expiry < 0) {
            expires = "login expiry unknown";
        } else if (expiry <= now) {
            expires = "login expired";
            color = 0xFFFF5555;
        } else {
            expires = "login expires in " + formatSpan(expiry - now);
        }
        drawCentered(context, "In use: " + name + " (" + expires + ")", INFO_Y, color);

        if (code != null) {
            drawCentered(context, "Enter " + code.userCode() + " at " + code.verificationUri(), CODE_Y, 0xFFFFAA00);
        } else if (AccountManager.clientId().isEmpty()) {
            drawCentered(context, "Set a Microsoft Client ID in the config (Accounts) to log in", CODE_Y, 0xFFFF5555);
        } else if (AccountStore.loadProblem() != null) {
            drawCentered(context, "Saved accounts unreadable: " + AccountStore.loadProblem(), CODE_Y, 0xFFFF5555);
        }
    }

    private void drawCentered(GuiGraphicsExtractor context, String text, int y, int color) {
        String shown = fit(text, width - 20);
        context.text(font, Component.literal(shown), width / 2 - font.width(shown) / 2, y, color, true);
    }

    @Override
    protected List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        for (AccountStore.Account account : AccountStore.all()) {
            boolean current = AccountManager.isCurrentAccount(account.uuid);
            List<RowButton> buttons = new ArrayList<>();
            if (!current) {
                buttons.add(button("Use", USE_WIDTH, 0xFF55FF55, () -> AccountManager.useAccount(account.uuid),
                        "Switch the game to this account",
                        "Applies to the next server you join; you stay connected as you are now"));
            }
            buttons.add(confirmButton("X", "?", REMOVE_WIDTH, 0xFFFF5555, "remove:" + account.uuid,
                    () -> AccountManager.removeAccount(account.uuid),
                    "Forget this account. You will have to log it in again to use it"));
            rows.add(new Row(account.name + (current ? "  (in use)" : ""), account.uuid,
                    current ? 0x5000AA00 : 0, buttons));
        }
        return rows;
    }

    private static String formatSpan(long ms) {
        long minutes = Math.max(0, ms) / 60_000;
        long hours = minutes / 60;
        return hours > 0 ? hours + "h " + String.format("%02d", minutes % 60) + "m" : minutes + "m";
    }
}
