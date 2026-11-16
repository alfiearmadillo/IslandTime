package org.example.islandtime;

import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.example.islandtime.mixin.MinecraftSessionAccessor;

import java.net.Proxy;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Signs accounts in and renews their logins while the game is running, without disconnecting. A server only checks
 * your login when you join, so swapping in a fresh token mid-session is invisible to it; the new one is what
 * Minecraft sends the next time it connects, and what anything reading {@code Minecraft.getUser()} sees from then on.
 *
 * <p>Logging in needs a Microsoft Client ID (see the Accounts settings). Once an account has been logged in through
 * here its renewable login is saved (encrypted, see {@link AccountStore}), which allows switching between accounts
 * and renewing the one in use shortly before its token expires.
 */
final class AccountManager {

    /** A token this close to expiring is renewed (when "Renew Login Automatically" is on). */
    private static final long RENEW_MARGIN_MS = 30L * 60 * 1000;
    private static final long CHECK_INTERVAL_MS = 30_000L;
    private static final long RETRY_AFTER_FAILURE_MS = 10L * 60 * 1000;

    private static final AtomicBoolean busy = new AtomicBoolean(false);
    private static volatile boolean cancelRequested = false;
    private static volatile MsAuth.DeviceCode pendingCode = null;
    private static volatile String status = "";
    private static volatile boolean statusOk = true;

    private static long nextCheckMs = 0;
    private static long lastAutomaticAttemptMs = 0;
    private static volatile String appliedToken = null;
    private static volatile long appliedExpiryMs = -1;

    private AccountManager() {
    }

    // -------------------------------------------------------------------------
    // State for the screen
    // -------------------------------------------------------------------------

    static String clientId() {
        IslandTimeConfig config = ConfigManager.getConfig();
        return config == null || config.accounts.msClientId == null ? "" : config.accounts.msClientId.trim();
    }

    static boolean isBusy() {
        return busy.get();
    }

    /** The code to enter on the Microsoft page while a login is waiting for it, or null. */
    static MsAuth.DeviceCode pendingCode() {
        return pendingCode;
    }

    static String status() {
        return status;
    }

    static boolean statusOk() {
        return statusOk;
    }

    static void cancelLogin() {
        cancelRequested = true;
    }

    /** When the login the game is using stops working, or -1 if that isn't known. */
    static long currentTokenExpiryMs() {
        String token = Minecraft.getInstance().getUser().getAccessToken();
        long fromToken = MsAuth.tokenExpiryMs(token);
        if (fromToken > 0) return fromToken;
        return token.equals(appliedToken) ? appliedExpiryMs : -1;
    }

    static boolean isCurrentAccount(String uuid) {
        return Minecraft.getInstance().getUser().getProfileId().toString().equalsIgnoreCase(uuid);
    }

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    /** Logs a Microsoft account in through the device-code page and saves it. */
    static void startLogin() {
        if (!preflight()) return;
        String clientId = clientId();
        cancelRequested = false;
        runAsync("IslandTime-Login", () -> {
            MsAuth.DeviceCode code = MsAuth.requestDeviceCode(clientId);
            pendingCode = code;
            announceCode(code);
            MsAuth.MsTokens tokens = MsAuth.pollForTokens(clientId, code, () -> cancelRequested);
            pendingCode = null;
            setStatus("Microsoft login accepted. Logging in to Minecraft...", true);
            chat("Microsoft login accepted. Logging in to Minecraft...", ChatFormatting.GRAY);
            finishLogin(tokens, true);
        });
    }

    /** Makes a saved account the one the game uses, renewing its login first. */
    static void useAccount(String uuid) {
        renew(uuid, true);
    }

    /** Renews the login of the account in use, if it is a saved one. */
    static void renewCurrent() {
        String uuid = Minecraft.getInstance().getUser().getProfileId().toString();
        if (AccountStore.find(uuid) == null) {
            setStatus("This account hasn't been logged in through IslandTime, so it can't be renewed. Add it first.", false);
            return;
        }
        renew(uuid, true);
    }

    private static void renew(String uuid, boolean announce) {
        if (!preflight()) return;
        AccountStore.Account account = AccountStore.find(uuid);
        if (account == null) {
            setStatus("That account isn't saved.", false);
            return;
        }
        String clientId = clientId();
        runAsync("IslandTime-Renew", () -> {
            setStatus("Renewing the login for " + account.name + "...", true);
            MsAuth.MsTokens tokens = MsAuth.refresh(clientId, account.refreshToken);
            finishLogin(tokens, announce);
        });
    }

    static void removeAccount(String uuid) {
        try {
            AccountStore.remove(uuid);
            setStatus("Removed the saved account.", true);
        } catch (java.io.IOException e) {
            setStatus("Could not update the saved accounts: " + e.getMessage(), false);
        }
    }

    /** Called every client tick: renews a saved account's login shortly before it expires. */
    static void tick(Minecraft client) {
        IslandTimeConfig config = ConfigManager.getConfig();
        if (config == null || !config.accounts.autoRenew || client.player == null) return;

        long now = System.currentTimeMillis();
        if (now < nextCheckMs || busy.get() || clientId().isEmpty()) return;
        nextCheckMs = now + CHECK_INTERVAL_MS;

        String uuid = client.getUser().getProfileId().toString();
        if (AccountStore.find(uuid) == null) return;

        long expiry = currentTokenExpiryMs();
        if (expiry < 0 || expiry - now > RENEW_MARGIN_MS) return;
        if (now - lastAutomaticAttemptMs < RETRY_AFTER_FAILURE_MS) return;
        lastAutomaticAttemptMs = now;
        renew(uuid, false);
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private interface Task {
        void run() throws MsAuth.AuthException;
    }

    private static boolean preflight() {
        if (clientId().isEmpty()) {
            setStatus("Set a Microsoft Client ID first (config > Accounts).", false);
            return false;
        }
        if (!busy.compareAndSet(false, true)) {
            setStatus("Already working on a login.", false);
            return false;
        }
        return true;
    }

    private static void runAsync(String name, Task task) {
        Thread thread = new Thread(() -> {
            try {
                task.run();
            } catch (MsAuth.AuthException e) {
                setStatus(e.message(), false);
            } catch (RuntimeException e) {
                setStatus("Login failed: " + e.getClass().getSimpleName(), false);
            } finally {
                pendingCode = null;
                busy.set(false);
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
    }

    /** Trades Microsoft's tokens for a Minecraft login, saves the account and swaps the login into the game. */
    private static void finishLogin(MsAuth.MsTokens tokens, boolean announce) throws MsAuth.AuthException {
        MsAuth.McSession session = MsAuth.minecraftLogin(tokens);

        try {
            AccountStore.put(session.uuid().toString(), session.name(), tokens.refreshToken());
        } catch (java.io.IOException e) {
            // The login itself worked, so use it; it just won't be there to switch back to
            setStatus("Signed in, but the account could not be saved: " + e.getMessage(), false);
        }

        // Built here because creating the service talks to Mojang
        UserApiService userApiService = new YggdrasilAuthenticationService(Proxy.NO_PROXY)
                .createUserApiService(session.accessToken());

        Minecraft client = Minecraft.getInstance();
        client.execute(() -> apply(client, session, userApiService, announce));
    }

    /** Swaps the login into the running game. Runs on the game thread. */
    private static void apply(Minecraft client, MsAuth.McSession session, UserApiService userApiService, boolean announce) {
        try {
            User old = client.getUser();
            boolean sameAccount = old.getProfileId().equals(session.uuid());
            User user = new User(session.name(), session.uuid(), session.accessToken(),
                    sameAccount ? old.getXuid() : Optional.empty(),
                    sameAccount ? old.getClientId() : Optional.empty());
            ProfileKeyPairManager keys = ProfileKeyPairManager.create(userApiService, user,
                    FabricLoader.getInstance().getGameDir().resolve("profilekeys"));

            MinecraftSessionAccessor accessor = (MinecraftSessionAccessor) (Object) client;
            accessor.islandtime$setUser(user);
            accessor.islandtime$setUserApiService(userApiService);
            accessor.islandtime$setProfileKeyPairManager(keys);
        } catch (RuntimeException | LinkageError e) {
            setStatus("Signed in, but the game's login could not be replaced: " + e.getClass().getSimpleName(), false);
            return;
        }

        appliedToken = session.accessToken();
        appliedExpiryMs = session.expiresAtMs();

        String connectedAs = client.player != null ? client.player.getGameProfile().name() : null;
        boolean differs = connectedAs != null && !connectedAs.equalsIgnoreCase(session.name());
        String message = differs
                ? "Now signed in as " + session.name() + ". You stay connected as " + connectedAs
                        + " until you reconnect."
                : announce ? "Login for " + session.name() + " renewed. No need to reconnect."
                        : "Login for " + session.name() + " renewed in the background.";
        setStatus(message, true);
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal("[IslandTime] " + message).withStyle(ChatFormatting.GREEN));
        }
    }

    private static void announceCode(MsAuth.DeviceCode code) {
        Minecraft client = Minecraft.getInstance();
        setStatus("Enter the code " + code.userCode() + " at " + code.verificationUri(), true);
        client.execute(() -> {
            client.keyboardHandler.setClipboard(code.userCode());
            if (client.player == null) return;
            Component link = Component.literal(code.verificationUri()).withStyle(style -> {
                try {
                    return style.withClickEvent(new ClickEvent.OpenUrl(URI.create(code.verificationUri())))
                            .withUnderlined(true).withColor(ChatFormatting.AQUA);
                } catch (IllegalArgumentException e) {
                    return style;
                }
            });
            client.player.sendSystemMessage(Component.literal("[IslandTime] Open ").withStyle(ChatFormatting.YELLOW)
                    .append(link)
                    .append(Component.literal(" and enter the code ").withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal(code.userCode()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                    .append(Component.literal(" (copied to your clipboard)").withStyle(ChatFormatting.GRAY)));
        });
    }

    static void setStatus(String message, boolean ok) {
        status = message;
        statusOk = ok;
        // Logins run in the background and the screen may not be open, so a failure has to be seen in chat
        if (!ok) chat(message, ChatFormatting.RED);
    }

    private static void chat(String message, ChatFormatting color) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendSystemMessage(Component.literal("[IslandTime] " + message).withStyle(color));
            }
        });
    }
}
