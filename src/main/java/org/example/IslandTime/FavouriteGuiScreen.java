package org.example.islandtime;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Standalone screen that renders the FavouriteGuiViewer overlay.
 * Used when the viewer is opened outside of a AbstractContainerScreen context
 * (e.g., from chat or the world).
 *
 * Mouse click and scroll blocking is handled globally by MouseMixin,
 * so we only need to override the methods that exist on Screen.
 */
public class FavouriteGuiScreen extends Screen {

    public FavouriteGuiScreen() {
        super(Component.empty());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        FavouriteGuiViewer.render(context, mouseX, mouseY);

        if (BackpackHoverHandler.isFrozen()) {
            BackpackHoverHandler.drawFrozen(context, mouseX, mouseY);
        } else {
            ItemStack hoveredItem = FavouriteGuiViewer.getHoveredItem();
            if (!hoveredItem.isEmpty() && BackpackNbtParser.hasBackpackData(hoveredItem)) {
                BackpackHoverHandler.onBackpackHovered(hoveredItem, context, mouseX, mouseY);
            } else {
                BackpackHoverHandler.clearHovered();
            }
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        long window = Minecraft.getInstance().getWindow().handle();

        if (BackpackHoverHandler.isFrozen()) {
            boolean ePressed = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_E) == GLFW.GLFW_PRESS;
            if (ePressed || event.key() == GLFW.GLFW_KEY_ESCAPE) {
                BackpackHoverHandler.requestUnfreeze();
            }
            return true;
        }

        boolean ePressed = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_E) == GLFW.GLFW_PRESS;
        if (ePressed || event.key() == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }

        boolean shiftPressed = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT)  == GLFW.GLFW_PRESS
                            || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
        if (shiftPressed && BackpackHoverHandler.isShowingOverlay()) {
            BackpackHoverHandler.freeze();
            return true;
        }

        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return true;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void removed() {
        FavouriteGuiViewer.close();
        BackpackHoverHandler.clearHovered();
    }
}
