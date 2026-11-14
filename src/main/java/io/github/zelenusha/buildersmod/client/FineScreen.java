package io.github.zelenusha.buildersmod.client;

import io.github.zelenusha.buildersmod.contract.ContractNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class FineScreen extends Screen {
    private static final int WIDTH = 344;
    private static final int HEIGHT = 232;
    private final ContractNetwork.FinePanel panel;
    private final long receivedNanos = System.nanoTime();
    private int selected;
    private boolean refreshing;

    FineScreen(ContractNetwork.FinePanel panel) {
        super(Component.literal(panel.attempt() == null ? "Штрафы прораба" : "Спор с прорабом"));
        this.panel = panel;
    }

    @Override
    protected void init() {
        int x = (width - WIDTH) / 2 + 12;
        int y = (height - HEIGHT) / 2;
        if (panel.attempt() != null) {
            var attempt = panel.attempt();
            for (int index = 0; index < attempt.cards().size(); index++) {
                var card = attempt.cards().get(index);
                button(card.text(), x, y + 72 + index * 19, WIDTH - 24, 18, () ->
                        ContractNetwork.request(panel.foreman(), ContractNetwork.Action.CARD, card.id(), attempt.id()));
            }
            return;
        }
        button("К заказам и выплатам", x, y + 180, WIDTH - 24, 20, () ->
                ContractNetwork.request(panel.foreman(), ContractNetwork.Action.SHOW_ORDERS, 0));
        var fine = selectedFine();
        if (fine == null) {
            return;
        }
        button("‹", x, y + 32, 25, 20, () -> select(selected - 1)).active = selected > 0;
        button("›", x + WIDTH - 49, y + 32, 25, 20, () -> select(selected + 1)).active = selected + 1 < panel.fines().size();
        button("Оспорить штраф", x, y + 152, WIDTH - 24, 20, () ->
                ContractNetwork.request(panel.foreman(), ContractNetwork.Action.APPEAL, 0, fine.id()))
                .active = !fine.overturned() && fine.attemptsLeft() > 0;
    }

    private Button button(String label, int x, int y, int buttonWidth, int buttonHeight, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(label), clicked -> action.run())
                .bounds(x, y, buttonWidth, buttonHeight).build());
    }

    private void select(int next) {
        selected = next;
        rebuildWidgets();
    }

    private ContractNetwork.FineRow selectedFine() {
        return panel.fines().isEmpty() ? null : panel.fines().get(selected);
    }

    @Override
    public void tick() {
        if (panel.attempt() != null && !refreshing && remainingMillis() == 0) {
            refreshing = true;
            ContractNetwork.request(panel.foreman(), ContractNetwork.Action.SHOW_FINES, 0);
        }
    }

    private long remainingMillis() {
        return Math.max(0, panel.attempt().remainingMillis() - (System.nanoTime() - receivedNanos) / 1_000_000);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int x = (width - WIDTH) / 2;
        int y = (height - HEIGHT) / 2;
        graphics.fill(x, y, x + WIDTH, y + HEIGHT, 0xF8212932);
        graphics.drawString(font, title, x + 12, y + 11, 0xFFFFFF, false);
        if (panel.attempt() == null) {
            var fine = selectedFine();
            if (fine == null) {
                graphics.drawString(font, "Штрафов пока нет.", x + 12, y + 43, 0xE4E9ED, false);
            } else {
                graphics.drawCenteredString(font, (selected + 1) + " / " + panel.fines().size(), x + WIDTH / 2, y + 38, 0x8BCCF3);
                graphics.drawWordWrap(font, Component.literal(fine.reason()), x + 12, y + 62, WIDTH - 24, 0xFFFFFF);
                String status = fine.overturned() ? "Отменён" : "Остаток " + fine.outstanding() + " · попыток " + fine.attemptsLeft();
                graphics.drawString(font, "Назначено " + fine.amount() + " · " + status, x + 12, y + 92, 0xE4E9ED, false);
            }
            graphics.drawWordWrap(font, Component.literal(panel.feedback()), x + 12, y + 208, WIDTH - 24, 0xF3D88C);
        } else {
            var attempt = panel.attempt();
            String progress = attempt.selected() + " / " + attempt.required() + "    " + (remainingMillis() + 999) / 1000 + " с";
            graphics.drawString(font, progress, x + WIDTH - 12 - font.width(progress), y + 11, 0xF3D88C, false);
            graphics.drawWordWrap(font, Component.literal(attempt.hint()), x + 12, y + 28, WIDTH - 24, 0xE4E9ED);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
