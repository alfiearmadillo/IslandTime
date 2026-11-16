package org.example.islandtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Signs a Microsoft account in to Minecraft the way launchers do: the Microsoft "device code" login (you enter a
 * short code on microsoft.com, so no password ever goes through the game), then Xbox Live, then Xbox's security
 * token service, then Minecraft services. Every call here blocks, so run them off the game thread.
 *
 * <p>Nothing here logs a token or puts one in an error message.
 */
final class MsAuth {

    private static final String SCOPE = "XboxLive.signin offline_access";
    private static final String DEVICE_CODE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    private static final String TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    private static final String XBL_URL = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_LOGIN_URL = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MC_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private MsAuth() {
    }

    /** A login step failed. {@link #message()} is safe to show; {@code needsLogin} means the saved login is no good any more. */
    static final class AuthException extends Exception {
        final boolean needsLogin;

        AuthException(String message, boolean needsLogin) {
            super(message);
            this.needsLogin = needsLogin;
        }

        AuthException(String message) {
            this(message, false);
        }

        String message() {
            return getMessage();
        }
    }

    record DeviceCode(String deviceCode, String userCode, String verificationUri, int expiresInSeconds,
                      int intervalSeconds) {
    }

    /** Microsoft's tokens. The refresh token is what lets a login be renewed without asking you again. */
    record MsTokens(String accessToken, String refreshToken) {
    }

    /** A Minecraft login: the token the game and servers use, and who it belongs to. */
    record McSession(String accessToken, String name, UUID uuid, long expiresAtMs) {
    }

    // -------------------------------------------------------------------------
    // Microsoft
    // -------------------------------------------------------------------------

    static DeviceCode requestDeviceCode(String clientId) throws AuthException {
        JsonObject json = postForm(DEVICE_CODE_URL, Map.of("client_id", clientId, "scope", SCOPE));
        if (json.has("error")) throw microsoftError(json, "Could not start the login");
        return new DeviceCode(
                string(json, "device_code"), string(json, "user_code"), string(json, "verification_uri"),
                json.has("expires_in") ? json.get("expires_in").getAsInt() : 900,
                json.has("interval") ? json.get("interval").getAsInt() : 5);
    }

    /** Waits for the code to be entered on the Microsoft page. Gives up when it expires or {@code cancelled} says so. */
    static MsTokens pollForTokens(String clientId, DeviceCode code, BooleanSupplier cancelled) throws AuthException {
        long deadline = System.currentTimeMillis() + code.expiresInSeconds() * 1000L;
        long intervalMs = Math.max(1, code.intervalSeconds()) * 1000L;

        while (System.currentTimeMillis() < deadline) {
            sleep(intervalMs);
            if (cancelled.getAsBoolean()) throw new AuthException("Login cancelled");

            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
            form.put("client_id", clientId);
            form.put("device_code", code.deviceCode());
            JsonObject json = postForm(TOKEN_URL, form);

            if (!json.has("error")) return tokensFrom(json);
            switch (string(json, "error")) {
                case "authorization_pending" -> { }
                case "slow_down" -> intervalMs += 5000;
                case "authorization_declined" -> throw new AuthException("The login was declined");
                case "expired_token" -> throw new AuthException("The code expired before it was entered");
                default -> throw microsoftError(json, "Login failed");
            }
        }
        throw new AuthException("The code expired before it was entered");
    }

    static MsTokens refresh(String clientId, String refreshToken) throws AuthException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("client_id", clientId);
        form.put("refresh_token", refreshToken);
        form.put("scope", SCOPE);
        JsonObject json = postForm(TOKEN_URL, form);
        if (json.has("error")) {
            boolean dead = "invalid_grant".equals(string(json, "error"));
            throw new AuthException(dead
                    ? "This saved login has expired or was revoked. Add the account again."
                    : microsoftError(json, "Could not renew the login").message(), dead);
        }
        return tokensFrom(json);
    }

    private static MsTokens tokensFrom(JsonObject json) throws AuthException {
        String access = string(json, "access_token");
        if (access.isEmpty()) throw new AuthException("Microsoft did not return a token");
        // A refresh without a new refresh token keeps the old one valid, so the caller falls back to it
        return new MsTokens(access, string(json, "refresh_token"));
    }

    private static AuthException microsoftError(JsonObject json, String prefix) {
        String error = string(json, "error");
        String description = string(json, "error_description");
        if (error.equals("invalid_client") || description.contains("AADSTS700016") || description.contains("AADSTS70002")) {
            return new AuthException("Microsoft does not recognise the Client ID in the settings. It needs to be an "
                    + "app registered for 'personal Microsoft accounts' with public client flows allowed.");
        }
        int cut = description.indexOf('\r');
        if (cut < 0) cut = description.indexOf('\n');
        String shortDescription = cut > 0 ? description.substring(0, cut) : description;
        return new AuthException(prefix + (shortDescription.isEmpty() ? " (" + error + ")" : ": " + shortDescription));
    }

    // -------------------------------------------------------------------------
    // Xbox Live and Minecraft
    // -------------------------------------------------------------------------

    /** Turns Microsoft's tokens into a Minecraft login, checking that the account owns the game. */
    static McSession minecraftLogin(MsTokens tokens) throws AuthException {
        JsonObject xblRequest = new JsonObject();
        JsonObject xblProperties = new JsonObject();
        xblProperties.addProperty("AuthMethod", "RPS");
        xblProperties.addProperty("SiteName", "user.auth.xboxlive.com");
        xblProperties.addProperty("RpsTicket", "d=" + tokens.accessToken());
        xblRequest.add("Properties", xblProperties);
        xblRequest.addProperty("RelyingParty", "http://auth.xboxlive.com");
        xblRequest.addProperty("TokenType", "JWT");
        JsonObject xbl = postJson(XBL_URL, xblRequest);
        String xblToken = string(xbl, "Token");
        if (xbl.has("XErr")) throw xstsError(xbl.get("XErr").getAsLong());
        if (xblToken.isEmpty()) throw new AuthException("Xbox Live did not accept the login" + detail(xbl));

        JsonObject xstsRequest = new JsonObject();
        JsonObject xstsProperties = new JsonObject();
        xstsProperties.addProperty("SandboxId", "RETAIL");
        JsonArray userTokens = new JsonArray();
        userTokens.add(xblToken);
        xstsProperties.add("UserTokens", userTokens);
        xstsRequest.add("Properties", xstsProperties);
        xstsRequest.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        xstsRequest.addProperty("TokenType", "JWT");
        JsonObject xsts = postJson(XSTS_URL, xstsRequest);
        if (xsts.has("XErr")) throw xstsError(xsts.get("XErr").getAsLong());
        String xstsToken = string(xsts, "Token");
        String userHash = userHash(xsts);
        if (xstsToken.isEmpty() || userHash.isEmpty()) throw new AuthException("Xbox Live did not accept the login");

        JsonObject mcRequest = new JsonObject();
        mcRequest.addProperty("identityToken", "XBL3.0 x=" + userHash + ";" + xstsToken);
        JsonObject mc = postJson(MC_LOGIN_URL, mcRequest);
        String mcToken = string(mc, "access_token");
        if (mcToken.isEmpty()) throw minecraftRefusal(mc);
        long expiresAt = System.currentTimeMillis() + (mc.has("expires_in") ? mc.get("expires_in").getAsLong() : 86_400L) * 1000L;

        JsonObject profile = getJson(MC_PROFILE_URL, mcToken);
        if (profile.has("error") || string(profile, "id").isEmpty()) {
            throw new AuthException("This Microsoft account does not own Minecraft: Java Edition" + detail(profile));
        }
        return new McSession(mcToken, string(profile, "name"), uuidOf(string(profile, "id")), expiresAt);
    }

    private static AuthException minecraftRefusal(JsonObject json) {
        String message = string(json, "errorMessage");
        if (message.isEmpty()) message = string(json, "error_description");
        if (message.toLowerCase().contains("invalid app registration")) {
            return new AuthException("Microsoft signed you in, but Minecraft refused the app (Invalid app registration). "
                    + "Mojang has to approve the app's Client ID before it can log in to Minecraft: request it at "
                    + "aka.ms/AppRegInfo. Until then, use a Client ID that is already approved.");
        }
        return new AuthException("Minecraft did not accept the login" + detail(json));
    }

    /** What a failed reply said, as a suffix for an error message, or nothing. */
    private static String detail(JsonObject json) {
        String message = string(json, "errorMessage");
        if (message.isEmpty()) message = string(json, "error_description");
        if (message.isEmpty()) message = string(json, "error");
        return message.isEmpty() ? "" : " (" + message + ")";
    }

    private static String userHash(JsonObject xsts) {
        try {
            return xsts.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0).getAsJsonObject().get("uhs").getAsString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static AuthException xstsError(long code) {
        if (code == 2148916233L) {
            return new AuthException("This Microsoft account has no Xbox profile. Sign in once at xbox.com to create one.");
        }
        if (code == 2148916238L) {
            return new AuthException("This is a child account; an adult has to add it to a Microsoft family first.");
        }
        if (code == 2148916235L) return new AuthException("Xbox Live is not available in this account's country.");
        return new AuthException("Xbox Live refused the login (" + code + ")");
    }

    static UUID uuidOf(String undashed) {
        return UUID.fromString(undashed.replaceFirst("(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)",
                "$1-$2-$3-$4-$5"));
    }

    /**
     * When a Minecraft access token stops working, read from the token itself, or -1 if it isn't in the usual
     * form. This is how a login handed over by a launcher, which gives no expiry, can still be watched.
     */
    static long tokenExpiryMs(String accessToken) {
        if (accessToken == null) return -1;
        String[] parts = accessToken.split("\\.");
        if (parts.length < 2) return -1;
        try {
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            JsonObject json = JsonParser.parseString(payload).getAsJsonObject();
            return json.has("exp") ? json.get("exp").getAsLong() * 1000L : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    // -------------------------------------------------------------------------
    // HTTP
    // -------------------------------------------------------------------------

    private static JsonObject postForm(String url, Map<String, String> form) throws AuthException {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> field : form.entrySet()) {
            if (body.length() > 0) body.append('&');
            body.append(URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return send(request);
    }

    private static JsonObject postJson(String url, JsonObject body) throws AuthException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return send(request);
    }

    private static JsonObject getJson(String url, String bearer) throws AuthException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + bearer)
                .GET()
                .build();
        return send(request);
    }

    /**
     * Sends a request and reads its JSON reply. The services here report most failures as a 4xx with a JSON body
     * ({@code error}, {@code XErr}) that the caller needs to see, so those are returned rather than thrown.
     */
    private static JsonObject send(HttpRequest request) throws AuthException {
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            JsonObject json = new JsonObject();
            if (body != null && !body.isBlank()) {
                try {
                    JsonElement parsed = JsonParser.parseString(body);
                    if (parsed.isJsonObject()) json = parsed.getAsJsonObject();
                } catch (RuntimeException ignored) {
                    // Not JSON; handled by the status below
                }
            }
            int status = response.statusCode();
            if (status >= 200 && status < 300) return json;
            if (json.has("error") || json.has("XErr")) return json;
            if (status == 429) throw new AuthException("Too many requests; wait a minute and try again");
            throw new AuthException("The server replied with an error (" + status + ")");
        } catch (IOException e) {
            throw new AuthException("Could not reach the login servers: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthException("Login cancelled");
        }
    }

    private static String string(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : "";
    }

    private static void sleep(long ms) throws AuthException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthException("Login cancelled");
        }
    }
}
