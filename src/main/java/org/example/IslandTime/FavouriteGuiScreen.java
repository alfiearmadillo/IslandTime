package org.example.islandtime;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * Standalone screen that renders the FavouriteGuiViewer overlay.
 * Used when the viewer is opened outside of a HandledScreen context
 * (e.g., from chat or the world).
 */
public class FavouriteGuiScreen extends Screen {

    public FavouriteGuiScreen() {
        super(Text.empty());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        FavouriteGuiViewer.render(context, mouseX, mouseY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_E) {
            this.close();
            return true;
        }
        // Block all other key input while viewer is open
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Block all mouse clicks
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        // Block scrolling
        return true;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void removed() {
        FavouriteGuiViewer.close();
    }
}
