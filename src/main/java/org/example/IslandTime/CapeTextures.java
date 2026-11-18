package org.example.islandtime;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The visible face of each cape (the side people see from behind), downloaded once and kept as a small texture for
 * the Skin & Capes screen to draw.
 */
final class CapeTextures {

    static final int FACE_WIDTH = 20;
    static final int FACE_HEIGHT = 32;

    // The outside of a cape sits at (1, 1) and is 10x16 on a 64x32 texture; larger textures scale all of it
    private static final int SOURCE_X = 1;
    private static final int SOURCE_Y = 1;
    private static final int SOURCE_WIDTH = 10;
    private static final int SOURCE_HEIGHT = 16;
    private static final int SOURCE_SHEET_WIDTH = 64;

    private static final Map<String, Identifier> READY = new ConcurrentHashMap<>();
    private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

    private CapeTextures() {
    }

    /** The texture of the cape's face, or null while it loads (or if it could not be). Starts loading on first use. */
    static Identifier get(SkinService.Cape cape) {
        Identifier ready = READY.get(cape.id());
        if (ready != null || cape.url().isEmpty() || FAILED.contains(cape.id())) return ready;
        if (!LOADING.add(cape.id())) return null;

        Thread thread = new Thread(() -> load(cape), "IslandTime-Cape");
        thread.setDaemon(true);
        thread.start();
        return null;
    }

    /** Lets capes that failed to load be tried again, for when the screen is opened anew. */
    static void forgetFailures() {
        FAILED.clear();
    }

    private static void load(SkinService.Cape cape) {
        NativeImage face = null;
        try {
            face = crop(SkinService.downloadCape(cape.url()));
        } catch (SkinService.SkinException | RuntimeException ignored) {
        }

        NativeImage finished = face;
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            LOADING.remove(cape.id());
            if (finished == null) {
                FAILED.add(cape.id());
                return;
            }
            Identifier id = Identifier.fromNamespaceAndPath("islandtime", "cape/" + cape.id().toLowerCase());
            client.getTextureManager().register(id, new DynamicTexture(() -> "islandtime cape", finished));
            READY.put(cape.id(), id);
        });
    }

    /** Cuts the face out of a cape texture and scales it to the size it is drawn at, without blurring it. */
    private static NativeImage crop(BufferedImage sheet) {
        double scale = (double) sheet.getWidth() / SOURCE_SHEET_WIDTH;
        if (scale < 1) return null;

        double x0 = SOURCE_X * scale;
        double y0 = SOURCE_Y * scale;
        double width = SOURCE_WIDTH * scale;
        double height = SOURCE_HEIGHT * scale;
        if (y0 + height > sheet.getHeight()) return null;

        NativeImage face = new NativeImage(FACE_WIDTH, FACE_HEIGHT, false);
        for (int y = 0; y < FACE_HEIGHT; y++) {
            for (int x = 0; x < FACE_WIDTH; x++) {
                int sourceX = (int) (x0 + (x + 0.5) * width / FACE_WIDTH);
                int sourceY = (int) (y0 + (y + 0.5) * height / FACE_HEIGHT);
                face.setPixel(x, y, sheet.getRGB(sourceX, sourceY));
            }
        }
        return face;
    }
}
