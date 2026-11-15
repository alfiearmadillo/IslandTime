package org.example.islandtime;

import net.minecraft.client.Minecraft;

import java.awt.image.BufferedImage;

/**
 * Runs {@link SkinService} calls off the game thread and reports the outcome back on it, for both the Skin & Capes
 * screen and the chat commands.
 */
final class SkinActions {

    /** Receives the outcome on the game thread. {@code profile} is the account's new state, or null on failure. */
    interface Reporter {
        void report(String message, boolean ok, SkinService.Profile profile);
    }

    private interface Call {
        SkinService.Profile run() throws SkinService.SkinException;
    }

    private SkinActions() {
    }

    static final String RECONNECT_NOTE = " Others see it once you reconnect.";

    static void refresh(Reporter reporter) {
        run(reporter, "", SkinService::fetchProfile);
    }

    static void paste(boolean slim, Reporter reporter) {
        run(reporter, "Skin changed." + RECONNECT_NOTE, () -> {
            BufferedImage image = SkinService.readClipboardImage();
            return SkinService.uploadSkin(image, slim);
        });
    }

    static void fromFile(String path, boolean slim, Reporter reporter) {
        run(reporter, "Skin changed." + RECONNECT_NOTE,
                () -> SkinService.uploadSkin(SkinService.readFile(path), slim));
    }

    static void fromTexture(String hash, boolean slim, Reporter reporter) {
        run(reporter, "Skin changed." + RECONNECT_NOTE, () -> SkinService.setSkinFromTexture(hash, slim));
    }

    static void reset(Reporter reporter) {
        run(reporter, "Skin reset to the default." + RECONNECT_NOTE, SkinService::resetSkin);
    }

    static void setCape(SkinService.Cape cape, Reporter reporter) {
        run(reporter, "Cape changed to " + cape.alias() + "." + RECONNECT_NOTE, () -> SkinService.setCape(cape.id()));
    }

    static void hideCape(Reporter reporter) {
        run(reporter, "Cape hidden." + RECONNECT_NOTE, SkinService::hideCape);
    }

    private static void run(Reporter reporter, String success, Call call) {
        Minecraft client = Minecraft.getInstance();
        Thread thread = new Thread(() -> {
            try {
                SkinService.Profile profile = call.run();
                client.execute(() -> reporter.report(success, true, profile));
            } catch (SkinService.SkinException e) {
                client.execute(() -> reporter.report(e.getMessage(), false, null));
            } catch (RuntimeException e) {
                client.execute(() -> reporter.report("Something went wrong: " + e.getClass().getSimpleName(), false, null));
            }
        }, "IslandTime-Skin");
        thread.setDaemon(true);
        thread.start();
    }
}
