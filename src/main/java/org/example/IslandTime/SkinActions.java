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

    /** Pastes a skin from the clipboard: a texture hash or textures.minecraft.net link if that's the text there, else an image. */
    static void paste(Boolean slim, Reporter reporter) {
        // The clipboard text can only be read on the game thread
        String hash = SkinService.parseTextureHash(Minecraft.getInstance().keyboardHandler.getClipboard());
        if (hash != null) {
            fromTexture(hash, slim, reporter);
            return;
        }
        run(reporter, "Skin changed." + RECONNECT_NOTE, () -> {
            BufferedImage image = SkinService.readClipboardImage();
            return SkinService.uploadSkin(image, slim);
        });
    }

    static void fromFile(String path, Boolean slim, Reporter reporter) {
        run(reporter, "Skin changed." + RECONNECT_NOTE,
                () -> SkinService.uploadSkin(SkinService.readFile(path), slim));
    }

    static void fromTexture(String hash, Boolean slim, Reporter reporter) {
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

    /** Saves the skin the account has now, with its model and cape, under {@code name}. */
    static void saveCurrent(String name, Reporter reporter) {
        run(reporter, "Saved the current skin as \"" + name + "\".", () -> {
            SkinService.Profile profile = SkinService.fetchProfile();
            BufferedImage image = SkinService.downloadSkin(profile.skinUrl());
            SkinService.Cape worn = profile.capes().stream().filter(SkinService.Cape::active).findFirst().orElse(null);
            try {
                SkinLibrary.add(name, image, "slim".equals(profile.skinVariant()),
                        worn == null ? "" : worn.id(), worn == null ? "" : worn.alias());
            } catch (java.io.IOException e) {
                throw new SkinService.SkinException(e.getMessage());
            }
            return profile;
        });
    }

    /** Applies a saved skin and, when it was saved with one the account owns, its cape. */
    static void useSaved(SkinLibrary.Entry entry, Reporter reporter) {
        run(reporter, "Applied \"" + entry.name + "\"." + RECONNECT_NOTE, () -> {
            BufferedImage image;
            try {
                image = SkinLibrary.readImage(entry);
            } catch (java.io.IOException e) {
                throw new SkinService.SkinException(e.getMessage());
            }
            SkinService.Profile profile = SkinService.uploadSkin(image, entry.slim);

            if (entry.capeId == null) return profile;
            SkinService.Cape worn = profile.capes().stream().filter(SkinService.Cape::active).findFirst().orElse(null);
            if (entry.capeId.isEmpty()) {
                return worn != null ? SkinService.hideCape() : profile;
            }
            boolean owned = profile.capes().stream().anyMatch(cape -> cape.id().equals(entry.capeId));
            if (owned && (worn == null || !worn.id().equals(entry.capeId))) return SkinService.setCape(entry.capeId);
            return profile;
        });
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
