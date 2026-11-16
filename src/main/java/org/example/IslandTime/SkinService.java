package org.example.islandtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Changes the skin and cape on the account the game is logged in as, through Mojang's profile API. It uses the
 * game's current login token, so it works with whatever is signed in (renew an expired one in the Accounts screen).
 * Every call blocks, so run them off the game thread.
 *
 * <p>These change the account, not the running game: servers read a skin when you join, so others see the change
 * once you reconnect.
 */
final class SkinService {

    private static final String PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    private SkinService() {
    }

    record Cape(String id, String alias, boolean active, String url) {
    }

    record Profile(String name, String skinUrl, String skinVariant, List<Cape> capes) {
    }

    /** A failure with a message that is safe to show. */
    static final class SkinException extends Exception {
        SkinException(String message) {
            super(message);
        }
    }

    // -------------------------------------------------------------------------
    // Profile, skin and cape calls
    // -------------------------------------------------------------------------

    static Profile fetchProfile() throws SkinException {
        return parseProfile(call(HttpRequest.newBuilder(URI.create(PROFILE_URL)).GET()));
    }

    /**
     * Whether the arms on a skin texture are the thin (3 pixel) kind. A slim arm leaves the last column of each arm
     * face unused, so those pixels are fully transparent; a classic arm paints them. Old 64x32 skins are always classic.
     */
    static boolean looksSlim(BufferedImage image) {
        if (image.getWidth() != 64 || image.getHeight() != 64) return false;
        return isClear(image, 54, 20, 2, 12) && isClear(image, 46, 52, 2, 12);
    }

    private static boolean isClear(BufferedImage image, int x, int y, int width, int height) {
        for (int px = x; px < x + width; px++) {
            for (int py = y; py < y + height; py++) {
                if ((image.getRGB(px, py) >>> 24) != 0) return false;
            }
        }
        return true;
    }

    /** Sets the skin from a PNG that is 64x64 or 64x32. A null {@code slim} picks the model from the arms in the image. */
    static Profile uploadSkin(BufferedImage image, Boolean slim) throws SkinException {
        validate(image);
        boolean thin = slim != null ? slim : looksSlim(image);
        BufferedImage argb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = argb.createGraphics();
        graphics.setComposite(java.awt.AlphaComposite.Src);
        graphics.drawImage(image, 0, 0, null);
        graphics.dispose();
        byte[] png;
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(argb, "png", out);
            png = out.toByteArray();
        } catch (IOException e) {
            throw new SkinException("Could not encode the skin image");
        }

        String boundary = "islandtime" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"variant\"\r\n\r\n"
                    + (thin ? "slim" : "classic") + "\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\n"
                    + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(png);
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new SkinException("Could not build the upload");
        }

        return parseProfile(call(HttpRequest.newBuilder(URI.create(PROFILE_URL + "/skins"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))));
    }

    private static final java.util.regex.Pattern TEXTURE = java.util.regex.Pattern.compile(
            "(?i)^(?:https?://textures\\.minecraft\\.net/texture/)?([0-9a-f]{16,64})$");

    /** The texture hash in a bare hash or a textures.minecraft.net link, or null if the text is neither. */
    static String parseTextureHash(String text) {
        if (text == null) return null;
        java.util.regex.Matcher matcher = TEXTURE.matcher(text.trim());
        return matcher.matches() ? matcher.group(1).toLowerCase() : null;
    }

    /**
     * Sets the skin from a texture on Mojang's texture server, such as the one a player head wears. The texture is
     * downloaded and uploaded like a pasted image, so it goes through the same call as every other skin change.
     */
    static Profile setSkinFromTexture(String texture, Boolean slim) throws SkinException {
        String hash = parseTextureHash(texture);
        if (hash == null) throw new SkinException("That isn't a skin texture hash");
        return uploadSkin(downloadSkin("https://textures.minecraft.net/texture/" + hash), slim);
    }

    /** Goes back to the default skin. */
    static Profile resetSkin() throws SkinException {
        call(HttpRequest.newBuilder(URI.create(PROFILE_URL + "/skins/active")).DELETE());
        return fetchProfile();
    }

    static Profile setCape(String capeId) throws SkinException {
        JsonObject json = new JsonObject();
        json.addProperty("capeId", capeId);
        return parseProfile(call(HttpRequest.newBuilder(URI.create(PROFILE_URL + "/capes/active"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json.toString()))));
    }

    static Profile hideCape() throws SkinException {
        call(HttpRequest.newBuilder(URI.create(PROFILE_URL + "/capes/active")).DELETE());
        return fetchProfile();
    }

    // -------------------------------------------------------------------------
    // Reading a skin to upload
    // -------------------------------------------------------------------------

    /** Downloads a skin from Mojang's texture server, as given by a profile. */
    static BufferedImage downloadSkin(String url) throws SkinException {
        return download(url, "This account has no custom skin to save", "skin");
    }

    /** Downloads a cape from Mojang's texture server, as given by a profile. */
    static BufferedImage downloadCape(String url) throws SkinException {
        return download(url, "This cape has no texture", "cape");
    }

    private static BufferedImage download(String url, String noTexture, String what) throws SkinException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new SkinException(noTexture);
        }
        if (uri.getHost() == null || !uri.getHost().equals("textures.minecraft.net")) {
            throw new SkinException(noTexture);
        }
        try {
            HttpResponse<java.io.InputStream> response = HTTP.send(
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (java.io.InputStream in = response.body()) {
                if (response.statusCode() != 200) throw new SkinException("Could not download the " + what + " (" + response.statusCode() + ")");
                BufferedImage image = ImageIO.read(in);
                if (image == null) throw new SkinException("The downloaded " + what + " is not an image");
                return image;
            }
        } catch (IOException e) {
            throw new SkinException("Could not download the " + what + ": " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SkinException("Cancelled");
        }
    }

    static void validate(BufferedImage image) throws SkinException {
        if (image == null) throw new SkinException("That isn't an image");
        boolean ok = image.getWidth() == 64 && (image.getHeight() == 64 || image.getHeight() == 32);
        if (!ok) {
            throw new SkinException("A skin must be 64x64 (or 64x32), but this is "
                    + image.getWidth() + "x" + image.getHeight());
        }
    }

    static BufferedImage readFile(String path) throws SkinException {
        File file = new File(path.trim().replaceAll("^\"|\"$", ""));
        if (!file.isFile()) throw new SkinException("No file at that path");
        try {
            BufferedImage image = ImageIO.read(file);
            validate(image);
            return image;
        } catch (IOException e) {
            throw new SkinException("Could not read that file as an image");
        }
    }

    /** Reads an image off the clipboard (a copied image, or a copied .png file), using the same tools Copy Skin writes with. */
    static BufferedImage readClipboardImage() throws SkinException {
        File temp = null;
        try {
            temp = File.createTempFile("islandtime_paste_", ".png");
            String os = System.getProperty("os.name", "").toLowerCase();
            String error;
            if (os.contains("win")) {
                String path = temp.getAbsolutePath().replace("'", "''");
                // The clipboard's PNG format keeps transparency, which a plain bitmap read loses (it fills it with white)
                String script = "Add-Type -AssemblyName System.Windows.Forms; Add-Type -AssemblyName System.Drawing; "
                        + "$d = [System.Windows.Forms.Clipboard]::GetDataObject(); "
                        + "if ($d -ne $null -and $d.GetDataPresent('PNG')) { "
                        + "$s = $d.GetData('PNG'); $o = [System.IO.File]::Create('" + path + "'); "
                        + "$s.CopyTo($o); $o.Close(); exit 0 } "
                        + "elseif ([System.Windows.Forms.Clipboard]::ContainsImage()) { "
                        + "$i = [System.Windows.Forms.Clipboard]::GetImage(); "
                        + "$i.Save('" + path + "', [System.Drawing.Imaging.ImageFormat]::Png); exit 0 } "
                        + "elseif ([System.Windows.Forms.Clipboard]::ContainsFileDropList()) { "
                        + "$f = [System.Windows.Forms.Clipboard]::GetFileDropList()[0]; "
                        + "Copy-Item -LiteralPath $f -Destination '" + path + "' -Force; exit 0 } "
                        + "else { exit 3 }";
                error = run(new String[]{"powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-STA",
                        "-Command", script}, temp, false);
            } else if (os.contains("mac") || os.contains("darwin")) {
                String path = temp.getAbsolutePath().replace("\"", "\\\"");
                String script = "set f to open for access POSIX file \"" + path + "\" with write permission\n"
                        + "set eof f to 0\nwrite (the clipboard as \u00ABclass PNGf\u00BB) to f\nclose access f";
                error = run(new String[]{"osascript", "-e", script}, temp, false);
            } else {
                String[] command = commandExists("wl-paste")
                        ? new String[]{"wl-paste", "--type", "image/png"}
                        : commandExists("xclip") ? new String[]{"xclip", "-selection", "clipboard", "-t", "image/png", "-o"}
                        : null;
                error = command == null ? "No clipboard tool found. Install wl-paste (Wayland) or xclip (X11)."
                        : run(command, temp, true);
            }
            if (error != null) throw new SkinException(error);
            if (temp.length() == 0) throw new SkinException("There is no image on the clipboard");

            BufferedImage image = ImageIO.read(temp);
            validate(image);
            return image;
        } catch (IOException e) {
            throw new SkinException("Could not read the clipboard");
        } finally {
            if (temp != null) temp.delete();
        }
    }

    /** Runs a clipboard tool. Returns null on success, or what to tell the user. */
    private static String run(String[] command, File output, boolean stdoutIsImage) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (stdoutIsImage) builder.redirectOutput(output);
            Process process = builder.start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "The clipboard tool timed out";
            }
            if (process.exitValue() == 0) return null;
            return process.exitValue() == 3 ? "There is no image on the clipboard" : "There is no image on the clipboard (or it could not be read)";
        } catch (IOException e) {
            return "Could not run the clipboard tool";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Cancelled";
        }
    }

    private static boolean commandExists(String name) {
        try {
            Process process = new ProcessBuilder("which", name).start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // HTTP
    // -------------------------------------------------------------------------

    private static JsonObject call(HttpRequest.Builder builder) throws SkinException {
        String token = Minecraft.getInstance().getUser().getAccessToken();
        HttpRequest request = builder.timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .build();
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 401 || status == 403) {
                throw new SkinException("The game's login has expired. Renew it in the Accounts screen (/it account).");
            }
            if (status == 429) throw new SkinException("Mojang is limiting skin and cape changes. Try again in a bit.");
            if (status < 200 || status >= 300) {
                throw new SkinException("Mojang refused the change (" + status + ")" + describe(response.body()));
            }
            String body = response.body();
            if (body == null || body.isBlank()) return new JsonObject();
            JsonElement parsed = JsonParser.parseString(body);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (IOException e) {
            throw new SkinException("Could not reach Mojang: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SkinException("Cancelled");
        } catch (RuntimeException e) {
            throw new SkinException("Mojang sent a reply that could not be read");
        }
    }

    private static String describe(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String message = json.has("errorMessage") ? json.get("errorMessage").getAsString() : "";
            return message.isEmpty() ? "" : ": " + message;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static Profile parseProfile(JsonObject json) throws SkinException {
        if (!json.has("id")) throw new SkinException("Mojang did not return a profile");
        String name = json.has("name") ? json.get("name").getAsString() : "";
        String skinUrl = "";
        String variant = "classic";
        if (json.has("skins") && json.get("skins").isJsonArray()) {
            for (JsonElement element : json.getAsJsonArray("skins")) {
                JsonObject skin = element.getAsJsonObject();
                if (skin.has("state") && "ACTIVE".equals(skin.get("state").getAsString())) {
                    if (skin.has("url")) skinUrl = skin.get("url").getAsString();
                    if (skin.has("variant")) variant = skin.get("variant").getAsString().toLowerCase();
                }
            }
        }
        List<Cape> capes = new ArrayList<>();
        if (json.has("capes") && json.get("capes").isJsonArray()) {
            JsonArray array = json.getAsJsonArray("capes");
            for (JsonElement element : array) {
                JsonObject cape = element.getAsJsonObject();
                capes.add(new Cape(cape.get("id").getAsString(),
                        cape.has("alias") ? cape.get("alias").getAsString() : "Cape",
                        cape.has("state") && "ACTIVE".equals(cape.get("state").getAsString()),
                        cape.has("url") ? cape.get("url").getAsString() : ""));
            }
        }
        return new Profile(name, skinUrl, variant, capes);
    }
}
