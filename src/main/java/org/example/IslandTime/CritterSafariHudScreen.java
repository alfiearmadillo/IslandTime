package org.example.islandtime;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Screen for repositioning the Critter Safari widget. Drag the widget anywhere on screen; the
 * position is stored as a screen percentage in the MoulConfig config and saved on close.
 */
public class CritterSafariHudScreen extends Screen {

    private boolean dragging = false;
    private int dragOffsetX = 0;
    private int dragOffsetY = 0;
    private int widgetX;
    private int widgetY;

    public CritterSafariHudScreen() {
        super(Component.literal("Critter Safari Widget"));
    }

    @Override
    protected void init() {
        super.init();
        widgetX = CritterSafariHud.getX();
        widgetY = CritterSafariHud.getY();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xC0101010);

        Component hint = Component.literal("Drag the widget to move it — press ESC to save")
                .withStyle(ChatFormatting.GOLD);
        int hintWidth = font.width(hint);
        context.text(font, hint, width / 2 - hintWidth / 2, 6, -1, true);

        // Diagnostic: shows what the tab list currently reports, so a mismatch is easy to spot.
        String area = BlockHighlightRenderer.detectCurrentArea();
        Component areaLine = Component.literal("Detected area: " + (area == null ? "none" : area)
                        + (CritterSafariHud.isInSafariArea() ? " (widget shown here)" : " (widget hidden here)"))
                .withStyle(CritterSafariHud.isInSafariArea() ? ChatFormatting.GREEN : ChatFormatting.GRAY);
        int areaWidth = font.width(areaLine);
        context.text(font, areaLine, width / 2 - areaWidth / 2, 18, -1, true);

        CritterSafariHud.render(context, widgetX, widgetY);

        super.extractRenderState(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;

        double mouseX = scaledMouseX();
        double mouseY = scaledMouseY();
        int w = CritterSafariHud.getWidth();
        int h = CritterSafariHud.getHeight();

        if (mouseX >= widgetX && mouseX <= widgetX + w && mouseY >= widgetY && mouseY <= widgetY + h) {
            dragging = true;
            dragOffsetX = (int) (mouseX - widgetX);
            dragOffsetY = (int) (mouseY - widgetY);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (dragging) {
            int w = CritterSafariHud.getWidth();
            int h = CritterSafariHud.getHeight();
            widgetX = clamp((int) scaledMouseX() - dragOffsetX, 0, Math.max(0, width - w));
            widgetY = clamp((int) scaledMouseY() - dragOffsetY, 0, Math.max(0, height - h));
            CritterSafariHud.setPosition(widgetX, widgetY);
            return true;
        }
        return super.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        dragging = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE || event.key() == GLFW.GLFW_KEY_E) {
            this.onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void removed() {
        CritterSafariHud.setPosition(widgetX, widgetY);
        ConfigManager.saveToFile();
        super.removed();
    }

    private static double scaledMouseX() {
        Minecraft client = Minecraft.getInstance();
        return client.mouseHandler.xpos() * (double) client.getWindow().getGuiScaledWidth()
                / (double) client.getWindow().getScreenWidth();
    }

    private static double scaledMouseY() {
        Minecraft client = Minecraft.getInstance();
        return client.mouseHandler.ypos() * (double) client.getWindow().getGuiScaledHeight()
                / (double) client.getWindow().getScreenHeight();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
