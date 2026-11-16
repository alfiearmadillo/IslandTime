package org.example.islandtime;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The accounts signed in through the mod, kept so you can switch between them without logging in each time.
 * Only the Microsoft refresh token is kept (never a password), and the whole file is encrypted by
 * {@link LocalSecretBox} before it is written. It lives in {@code config/IslandTime/accounts.dat}, never leaves
 * the computer, and is not part of any exported or shared setting.
 */
final class AccountStore {

    private static final Path FILE = Paths.get("config/IslandTime/accounts.dat");
    private static final Path TMP = Paths.get("config/IslandTime/accounts.tmp");
    private static final Gson GSON = new Gson();

    static final class Account {
        String uuid;
        String name;
        String refreshToken;

        Account(String uuid, String name, String refreshToken) {
            this.uuid = uuid;
            this.name = name;
            this.refreshToken = refreshToken;
        }
    }

    private static final class Document {
        List<Account> accounts = new ArrayList<>();
    }

    private static Document document = new Document();
    private static boolean loaded = false;
    private static String loadProblem = null;

    private AccountStore() {
    }

    /** Why the saved accounts couldn't be read, or null. They're left on disk untouched until a save replaces them. */
    static synchronized String loadProblem() {
        load();
        return loadProblem;
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        if (!Files.exists(FILE)) return;
        try {
            byte[] plain = LocalSecretBox.open(Files.readAllBytes(FILE));
            Document read = GSON.fromJson(new String(plain, StandardCharsets.UTF_8), Document.class);
            if (read != null && read.accounts != null) document = read;
        } catch (IOException | RuntimeException e) {
            loadProblem = e instanceof IOException ? e.getMessage() : "The saved accounts file is damaged";
            System.err.println("[AccountStore] Could not read saved accounts: " + loadProblem);
        }
    }

    static synchronized List<Account> all() {
        load();
        return new ArrayList<>(document.accounts);
    }

    static synchronized Account find(String uuid) {
        load();
        for (Account account : document.accounts) {
            if (account.uuid.equalsIgnoreCase(uuid)) return account;
        }
        return null;
    }

    /** Adds the account, or updates the one with the same uuid. An empty {@code refreshToken} keeps the existing one. */
    static synchronized void put(String uuid, String name, String refreshToken) throws IOException {
        load();
        Account existing = find(uuid);
        if (existing != null) {
            existing.name = name;
            if (refreshToken != null && !refreshToken.isEmpty()) existing.refreshToken = refreshToken;
        } else {
            document.accounts.add(new Account(uuid, name, refreshToken));
        }
        save();
    }

    static synchronized void remove(String uuid) throws IOException {
        load();
        document.accounts.removeIf(account -> account.uuid.equalsIgnoreCase(uuid));
        save();
    }

    private static void save() throws IOException {
        Files.createDirectories(FILE.getParent());
        if (loadProblem != null && Files.exists(FILE)) {
            // Keep what couldn't be read rather than writing over it
            Files.move(FILE, FILE.resolveSibling("accounts.dat.unreadable"), StandardCopyOption.REPLACE_EXISTING);
            loadProblem = null;
        }
        Files.write(TMP, LocalSecretBox.seal(GSON.toJson(document).getBytes(StandardCharsets.UTF_8)));
        Files.move(TMP, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
