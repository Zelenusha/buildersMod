package io.github.zelenusha.buildersmod.client;

import io.github.zelenusha.buildersmod.contract.ContractNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class DemolitionScreen extends Screen {
    private static final int WIDTH = 284;
    private static final int HEIGHT = 196;
    final ContractNetwork.DemolitionPanel panel;
    private int selected;
    private int offset;

    DemolitionScreen(ContractNetwork.DemolitionPanel panel) {
        super(Component.literal("Задания на снос"));
        this.panel = panel;
    }

    @Override
    protected void init() {
        int x = (width - WIDTH) / 2 + 12;
        int y = (height - HEIGHT) / 2;
        addRenderableWidget(Button.builder(Component.literal("К заказам"), button ->
                ContractNetwork.request(panel.foreman(), ContractNetwork.Action.SHOW_ORDERS, 0))
                .bounds(x, y + 155, 124, 20).build());
        ContractNetwork.DemolitionCard card = selectedCard();
        if (card == null) {
            return;
        }
        addRenderableWidget(Button.builder(Component.literal("Сдать материалы"), button -> {
            button.active = false;
            ContractNetwork.deliverDemolition(panel.foreman(), card.id());
        }).bounds(x + 136, y + 155, 124, 20).build());
        addRenderableWidget(Button.builder(Component.literal("‹"), button -> select(selected - 1))
                .bounds(x, y + 29, 22, 20).build()).active = selected > 0;
        addRenderableWidget(Button.builder(Component.literal("›"), button -> select(selected + 1))
                .bounds(x + 238, y + 29, 22, 20).build()).active = selected + 1 < panel.cards().size();
    }

    private void select(int index) {
        selected = index;
        offset = 0;
        rebuildWidgets();
    }

    private ContractNetwork.DemolitionCard selectedCard() {
        return panel.cards().isEmpty() ? null : panel.cards().get(selected);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int x = (width - WIDTH) / 2;
        int y = (height - HEIGHT) / 2;
        graphics.fill(x, y, x + WIDTH, y + HEIGHT, 0xF8212932);
        graphics.drawString(font, title, x + 12, y + 11, 0xFFFFFF, false);
        ContractNetwork.DemolitionCard card = selectedCard();
        if (card == null) {
            graphics.drawString(font, "Активных сносов нет.", x + 12, y + 42, 0xE4E9ED, false);
        } else {
            String description = (selected + 1) + " / " + panel.cards().size() + " · " + card.position().toShortString();
            graphics.drawCenteredString(font, description, x + WIDTH / 2, y + 35, 0x8BCCF3);
            graphics.drawString(font, "Материалы к сдаче", x + 12, y + 59, 0xFFFFFF, false);
            if (card.materials().isEmpty()) {
                graphics.drawString(font, "Пока ничего не собрано при разборке.", x + 12, y + 73, 0xE4E9ED, false);
            }
            for (int index = offset; index < Math.min(card.materials().size(), offset + 6); index++) {
                ContractNetwork.Material item = card.materials().get(index);
                String material = Component.translatable(item.label()).getString() + " ×" + item.needed();
                graphics.drawString(font, material, x + 12, y + 73 + (index - offset) * 11, 0xE4E9ED, false);
            }
        }
        graphics.drawString(font, font.plainSubstrByWidth(panel.feedback(), WIDTH - 24), x + 12, y + 181,
                0xF3D88C, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        ContractNetwork.DemolitionCard card = selectedCard();
        if (card == null) {
            return false;
        }
        offset = Math.max(0, Math.min(Math.max(0, card.materials().size() - 6), offset - (int) Math.signum(delta)));
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
