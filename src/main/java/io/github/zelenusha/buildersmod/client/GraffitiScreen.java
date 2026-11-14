package io.github.zelenusha.buildersmod.client;

import io.github.zelenusha.buildersmod.maintenance.GraffitiEvents;
import io.github.zelenusha.buildersmod.maintenance.GraffitiPattern;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.BitSet;
import java.util.UUID;

final class GraffitiScreen extends Screen {
    private final UUID token;
    private final BitSet erased;
    private int cursorX = -1;
    private int cursorY = -1;

    GraffitiScreen(UUID token, BitSet erased) {
        super(Component.literal("Отмывание граффити"));
        this.token = token;
        this.erased = erased;
    }

    void progress(UUID id, BitSet serverErased, boolean finished) {
        if (token.equals(id)) {
            erased.or(serverErased);
            if (finished) {
                onClose();
            }
        }
    }

    private int scale() {
        return Math.max(1, Math.min((width - 32) / GraffitiPattern.CANVAS_WIDTH,
                (height - 76) / GraffitiPattern.CANVAS_HEIGHT));
    }

    private int left() { return (width - GraffitiPattern.CANVAS_WIDTH * scale()) / 2; }

    private int top() { return (height - GraffitiPattern.CANVAS_HEIGHT * scale()) / 2; }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int left = left();
        int top = top();
        int size = GraffitiPattern.CELL * scale();
        int right = left + GraffitiPattern.WIDTH * size;
        int bottom = top + GraffitiPattern.HEIGHT * size;
        graphics.fill(left - 2, top - 2, right + 2, bottom + 2, 0xFF52504B);
        graphics.fill(left, top, right, bottom, 0xFFCCC1A9);
        for (int y = 0; y < GraffitiPattern.HEIGHT; y++) {
            for (int x = 0; x < GraffitiPattern.WIDTH; x++) {
                int patch = GraffitiPattern.patchAt(x, y);
                if (patch >= 0 && !erased.get(patch)) {
                    int px = left + x * size;
                    int py = top + y * size;
                    graphics.fill(px, py, px + size, py + size, 0xFF34765E);
                }
            }
        }
        graphics.drawCenteredString(font, title, width / 2, top - 28, 0xFFFFFF);
        graphics.drawCenteredString(font, "Веди мышью, удерживая ЛКМ", width / 2, top - 15, 0xE5EBE8);
        graphics.drawCenteredString(font, "Отмыто " + erased.cardinality() + " из " + GraffitiPattern.PATCH_COUNT,
                width / 2, bottom + 10, 0xE5EBE8);
        graphics.drawCenteredString(font, "Esc — прервать попытку", width / 2, bottom + 24, 0xB7C1BE);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return button == 0 && scrub(mouseX, mouseY, true) || super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return button == 0 && scrub(mouseX, mouseY, cursorX < 0) || super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            cursorX = cursorY = -1;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private boolean scrub(double mouseX, double mouseY, boolean begin) {
        int x = (int) Math.floor((mouseX - left()) / scale());
        int y = (int) Math.floor((mouseY - top()) / scale());
        if (!GraffitiPattern.inside(x, y)) {
            cursorX = cursorY = -1;
            return false;
        }
        if (begin || x != cursorX || y != cursorY) {
            GraffitiEvents.sendStroke(token, begin, x, y);
            cursorX = x;
            cursorY = y;
        }
        return true;
    }

    @Override
    public void removed() {
        GraffitiEvents.endAttempt(token);
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
