package org.example.islandtime;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class IslandTimeMod implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Load saved data
        IslandTimeTracker.load();
        FuelCycleTracker.load();
        DiscordWebhookManager.load();
        ItemTracker.load();
        IslandAlertTracker.load();
        DisconnectAlertTracker.load();

        // Register tick events
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Update island time tracker (still useful for /islandtime command)
            IslandTimeTracker.tick(client);

            // Update minion fuel tracker
            MinionFuelTracker.checkFuelSlot();

            // Get island status from IslandTimeTracker (for fuel cycle tracking)
            boolean isOnIsland = IslandTimeTracker.isCurrentlyOnIsland();

            // Update item tracker (now checks regardless of island status)
            ItemTracker.tick(client);

            // Get whether stats are increasing from ItemTracker
            boolean statsIncreasing = ItemTracker.areStatsIncreasing();

            // Update fuel cycle tracker with stats-increasing status
            FuelCycleTracker.tick(statsIncreasing);

            // Update island alert tracker (still useful for off-island warnings)
            IslandAlertTracker.tick(isOnIsland);

            // Update disconnect alert tracker
            DisconnectAlertTracker.tick(client);

            // Open the backpack preview screen if the player held Shift
            // while hovering a backpack in the previous render frame.
            BackpackHoverHandler.tick(client);
        });

        // Register commands
        IslandTimeCommand.register();


        // Save data when Minecraft closes.
        // Also shuts down webhook thread pool to prevent resource leak.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("IslandTimeMod shutting down...");

            IslandTimeTracker.save();
            FuelCycleTracker.save();
            ItemTracker.save();
            IslandAlertTracker.save();
            DisconnectAlertTracker.save();
            DiscordWebhookManager.save();

            DiscordWebhookManager.shutdown();

            System.out.println("IslandTimeMod shutdown complete");
        }));
    }
}
