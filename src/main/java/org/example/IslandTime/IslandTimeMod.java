package org.example.islandtime;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class IslandTimeMod implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Load saved data — TrackingToggleConfig must come LAST so it can
        // apply the persisted flags to already-loaded tracker state.
        IslandTimeTracker.load();
        FuelCycleTracker.load();
        DiscordWebhookManager.load();
        ItemTracker.load();
        IslandAlertTracker.load();
        DisconnectAlertTracker.load();
        TrackingToggleConfig.load(); // applies toggle flags to trackers

        // Register tick events
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            IslandTimeTracker.tick(client);
            MinionFuelTracker.checkFuelSlot();

            boolean isOnIsland      = IslandTimeTracker.isCurrentlyOnIsland();
            boolean statsIncreasing = ItemTracker.areStatsIncreasing();

            ItemTracker.tick(client);
            FuelCycleTracker.tick(statsIncreasing);
            IslandAlertTracker.tick(isOnIsland);
            DisconnectAlertTracker.tick(client);
            BackpackHoverHandler.tick(client);
        });

        // Register commands
        IslandTimeCommand.register();

        // Intercept chat messages and redirect Hypixel wiki links to minecraft.wiki
        WikiLinkReplacer.register();

        // Save everything on shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("IslandTimeMod shutting down...");

            IslandTimeTracker.save();
            FuelCycleTracker.save();
            ItemTracker.save();
            IslandAlertTracker.save();
            DisconnectAlertTracker.save();
            DiscordWebhookManager.save();
            TrackingToggleConfig.save();

            DiscordWebhookManager.shutdown();

            System.out.println("IslandTimeMod shutdown complete");
        }));
    }
}
