package org.example.islandtime;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public class IslandTimeMod implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Load saved data — TrackingToggleConfig must come LAST so it can
        // apply the persisted flags to already-loaded tracker state.
        IslandTimeTracker.load();
        FuelCycleTracker.load();
        MinionIdentityRegistry.load();
        DiscordWebhookManager.load();
        ItemTracker.load();
        IslandAlertTracker.load();
        DisconnectAlertTracker.load();
        FuelExpiredAlertTracker.load();
        FavouriteGuiManager.load();
        TrackingToggleConfig.load(); // applies toggle flags to trackers
        TooltipOverrideConfig.load(); // NEW: load tooltip override flag
        ResourcePackConfig.load();   // Load resource pack persistence config

        // Load chat relay config and register the chat listener
        ChatRelayConfig.load();
        PlayerListManager.load();
        BlockHighlightManager.load();
        AgingItemsConfig.load();
        ComposterTracker.load();
        IslandChatRelay.register();
        ChatMessageStore.register();
        NewYearCakeTracker.register();
        MinionFuelTracker.registerArmorStandInteractionListener();

        // Initialize MoulConfig
        ConfigManager.initialize();

        // Register tick events
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            IslandTimeTracker.tick(client);
            MinionFuelTracker.checkFuelSlot();
            FavouriteGuiManager.tickScreenSnapshot(client);
            ConfigManager.tick(client);  // Sync config when GUI closes
            IslandPlayerTracker.tick(client);  // Track player join/leave

            boolean isOnIsland      = IslandTimeTracker.isCurrentlyOnIsland();
            boolean statsIncreasing = ItemTracker.areStatsIncreasing();

            ItemTracker.tick(client);
            FuelCycleTracker.tick(statsIncreasing);
            IslandAlertTracker.tick(isOnIsland);
            DisconnectAlertTracker.tick(client);
            FuelExpiredAlertTracker.tick();
            NewYearCakeTracker.tick();
            BackpackHoverHandler.tick(client);
            SparklingAlert.tick(client);
            ComposterTracker.tick(client);
        });

        // Start the localhost poller when the player connects to a server,
        // stop it when they disconnect (handles /server switches too).
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
                IslandChatRelay.startPolling();
                ResourcePackManager.applyCachedPack();

                // Catch up on any fuel-day(s) that finished while disconnected or with
                // the game closed entirely (kicked, crashed, closed overnight, etc.) —
                // alert on login instead of waiting for a fresh tick to notice.
                FuelCycleTracker.checkDueDayRecords(System.currentTimeMillis());
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
                IslandChatRelay.stopPolling();
                // Hypixel never announces leaving the Safari, so end the run on disconnect.
                CritterSafariTracker.reset();
        });

        // Register block highlight renderer
        BlockHighlightRenderer.register();

        // Register the Critter Safari widget, its chat listener, and the sparkling tracer
        CritterSafariHud.register();
        SparklingAlert.register();

        // Register commands
        IslandTimeCommand.register();

        // Save everything on shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("IslandTimeMod shutting down...");

            ConfigManager.saveToFile();      // Save MoulConfig
            IslandTimeTracker.save();
            FuelCycleTracker.save();
            ItemTracker.save();
            IslandAlertTracker.save();
            DisconnectAlertTracker.save();
            FuelExpiredAlertTracker.save();
            DiscordWebhookManager.save();
            TrackingToggleConfig.save();
            FavouriteGuiManager.save();
            ChatRelayConfig.save();
            TooltipOverrideConfig.save();    // NEW: save tooltip override flag
            ResourcePackConfig.save();       // Save resource pack config
            BlockHighlightManager.save();    // Save block highlight entries
            ComposterTracker.save();

            DiscordWebhookManager.shutdown();
            IslandChatRelay.shutdown();      // stops poller + webhook thread
            ResourcePackManager.shutdown();  // stops daemon threads + HTTP client

            System.out.println("IslandTimeMod shutdown complete");
        }));
    }
}
