package org.example.islandtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Skins saved to switch back to later: the image, the model (classic or slim) and which cape was worn with it.
 * The index is {@code config/IslandTime/saved_skins.json} and each image is a PNG in
 * {@code config/IslandTime/skins/}. Nothing here is private; a skin is public once it is on your account.
 */
final class SkinLibrary {

    static final int MAX_NAME_LENGTH = 32;

    private static final Path INDEX = Paths.get("config/IslandTime/saved_skins.json");
    private static final Path INDEX_TMP = Paths.get("config/IslandTime/saved_skins.tmp");
    private static final Path IMAGES = Paths.get("config/IslandTime/skins");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    static final class Entry {
        String id;
        String name;
        boolean slim;
        /** The cape worn with the skin: its id (empty for no cape), or null if that wasn't recorded. */
        String capeId;
        /** The cape's name, for display only. */
        String capeAlias;
    }

    private static final class Document {
        List<Entry> skins = new ArrayList<>();
    }

    private static Document document = new Document();
    private static boolean loaded = false;

    private SkinLibrary() {
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        if (!Files.exists(INDEX)) return;
        try {
            Document read = GSON.fromJson(Files.readString(INDEX), Document.class);
            if (read != null && read.skins != null) document = read;
        } catch (IOException | RuntimeException e) {
            System.err.println("[SkinLibrary] Could not read saved skins: " + e.getMessage());
        }
    }

    static synchronized List<Entry> all() {
        load();
        return new ArrayList<>(document.skins);
    }

    /** The saved skin with this name (ignoring case), or null. */
    static synchronized Entry findByName(String name) {
        load();
        for (Entry entry : document.skins) {
            if (entry.name.equalsIgnoreCase(name.trim())) return entry;
        }
        return null;
    }

    /** Why this can't be the name of a saved skin ({@code ownId} is the one being renamed, or null), or null if it can. */
    static synchronized String validateName(String name, String ownId) {
        load();
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) return "Give the skin a name";
        if (trimmed.length() > MAX_NAME_LENGTH) return "Names can be at most " + MAX_NAME_LENGTH + " characters";
        for (Entry entry : document.skins) {
            if (entry.name.equalsIgnoreCase(trimmed) && !entry.id.equals(ownId)) return "A saved skin already has that name";
        }
        return null;
    }

    static synchronized Entry add(String name, BufferedImage image, boolean slim, String capeId, String capeAlias)
            throws IOException {
        load();
        String problem = validateName(name, null);
        if (problem != null) throw new IOException(problem);

        Entry entry = new Entry();
        entry.id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        entry.name = name.trim();
        entry.slim = slim;
        entry.capeId = capeId;
        entry.capeAlias = capeAlias;

        Files.createDirectories(IMAGES);
        if (!ImageIO.write(image, "png", imageFile(entry.id).toFile())) throw new IOException("Could not save the image");
        document.skins.add(entry);
        save();
        return entry;
    }

    static synchronized void rename(String id, String name) throws IOException {
        load();
        String problem = validateName(name, id);
        if (problem != null) throw new IOException(problem);
        for (Entry entry : document.skins) {
            if (entry.id.equals(id)) entry.name = name.trim();
        }
        save();
    }

    static synchronized void remove(String id) throws IOException {
        load();
        if (document.skins.removeIf(entry -> entry.id.equals(id))) {
            Files.deleteIfExists(imageFile(id));
            save();
        }
    }

    static synchronized BufferedImage readImage(Entry entry) throws IOException {
        BufferedImage image = ImageIO.read(imageFile(entry.id).toFile());
        if (image == null) throw new IOException("The saved image is missing or damaged");
        return image;
    }

    private static Path imageFile(String id) {
        // Ids are generated here, but the index is a file a person can edit
        return IMAGES.resolve(id.replaceAll("[^a-z0-9]", "").toLowerCase(Locale.ROOT) + ".png");
    }

    private static void save() throws IOException {
        Files.createDirectories(INDEX.getParent());
        Files.writeString(INDEX_TMP, GSON.toJson(document));
        Files.move(INDEX_TMP, INDEX, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
