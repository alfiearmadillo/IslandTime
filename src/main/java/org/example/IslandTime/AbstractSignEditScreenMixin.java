package org.example.islandtime.mixin;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.example.islandtime.ConfigManager;
import org.example.islandtime.IslandTimeConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractSignEditScreen.class)
public abstract class AbstractSignEditScreenMixin extends Screen {

    protected AbstractSignEditScreenMixin() {
        super(Text.empty());
    }

    @Shadow private String[] messages;
    @Shadow private int currentRow;
    @Shadow protected abstract void finishEditing();

    @Inject(method = "init", at = @At("TAIL"))
    private void addSignButtons(CallbackInfo ci) {
        IslandTimeConfig config = ConfigManager.getConfig();
        if (config == null || !config.signs.enabled) {
            return;
        }

        // "Paste from Clipboard" — pastes clipboard text into the first row only
        addDrawableChild(
                ButtonWidget.builder(
                        Text.literal("Paste from Clipboard"),
                        btn -> {
                            String clip = client.keyboard.getClipboard();
                            if (clip != null && !clip.isEmpty()) {
                                // Only paste into the first line
                                String firstLine = clip.split("\n", 2)[0];
                                messages[0] = firstLine;
                                currentRow = 0;
                            }
                        }
                ).dimensions(width / 2 - 100, height / 4 + 168, 200, 20).build()
        );

        // "Paste from Config" — pastes all 4 pre-set lines from moulconfig
        addDrawableChild(
                ButtonWidget.builder(
                        Text.literal("Paste from Config"),
                        btn -> {
                            IslandTimeConfig cfg = ConfigManager.getConfig();
                            if (cfg != null) {
                                if (!cfg.signs.line1.isEmpty()) messages[0] = cfg.signs.line1;
                                if (!cfg.signs.line2.isEmpty()) messages[1] = cfg.signs.line2;
                                if (!cfg.signs.line3.isEmpty()) messages[2] = cfg.signs.line3;
                                if (!cfg.signs.line4.isEmpty()) messages[3] = cfg.signs.line4;
                                currentRow = messages.length - 1;
                            }
                        }
                ).dimensions(width / 2 - 100, height / 4 + 192, 200, 20).build()
        );
    }
}
