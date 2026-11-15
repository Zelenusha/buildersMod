package io.github.zelenusha.buildersmod.client;

import io.github.zelenusha.buildersmod.contract.ContractBalance;
import io.github.zelenusha.buildersmod.contract.ContractNetwork;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class ForemanScreen extends Screen {
    private static final int WIDTH = 316;
    private static final int HEIGHT = 227;
    private final ContractNetwork.Panel panel;
    private int selected;
    private int detailOffset;
    private Detail detail = Detail.MATERIALS;
    private boolean lastCallOpen;

    private enum Detail { CONDITIONS, MATERIALS, BONUS }

    ForemanScreen(ContractNetwork.Panel panel) {
        super(Component.literal(panel.dialogue() ? "Прораб" : "Заказы строительной конторы"));
        this.panel = panel;
    }

    @Override
    protected void init() {
        int x = (width - WIDTH) / 2 + 12;
        int y = (height - HEIGHT) / 2;
        if (panel.dialogue()) {
            action("Открыть заказы", ContractNetwork.Action.SHOW_ORDERS, 0, x, y + 153, WIDTH - 24);
            Button demolitions = action("Сносы — " + panel.failed(), ContractNetwork.Action.SHOW_DEMOLITIONS, 0,
                    x, y + 176, 141);
            demolitions.active = panel.failed() > 0;
            action("Штрафы — " + panel.fineCount(), ContractNetwork.Action.SHOW_FINES, 0,
                    x + 151, y + 176, 141).active = panel.fineCount() > 0;
            return;
        }
        if (!panel.lastCall().isEmpty()) {
            String caption = lastCallOpen ? "К заказам" : "Звонок";
            addRenderableWidget(Button.builder(Component.literal(caption), button -> {
                lastCallOpen = !lastCallOpen;
                rebuildWidgets();
            }).bounds(x + 221, y + 6, 71, 18).build());
        }
        if (lastCallOpen) {
            return;
        }

        ContractNetwork.Card card = selectedCard();
        {
            int end = Math.min(panel.cards().size(), 5);
            for (int index = 0; index < end; index++) {
                if (panel.cards().get(index).slot() < 0) {
                    break;
                }
                Button tab = selector(Integer.toString(panel.cards().get(index).slot() + 1), index,
                        x + index * 27, y + 29, 24);
                tab.active = selected != index;
            }
            int specialIndex = 0;
            while (specialIndex < panel.cards().size() && panel.cards().get(specialIndex).slot() >= 0) {
                specialIndex++;
            }
            if (specialIndex < panel.cards().size()) {
                int next = selected >= specialIndex && selected + 1 < panel.cards().size() ? selected + 1 : specialIndex;
                int total = panel.cards().size() - specialIndex;
                String label = "Отдельные заказы" + (total > 1 ? " · " + total : "");
                selector(label, next, x + 141, y + 29, WIDTH - 165).active = next != selected;
            }
        }

        if (card != null && card.remaining() >= 0) {
            detailButton("Условия", Detail.CONDITIONS, x, y + 86);
            detailButton("Материалы", Detail.MATERIALS, x + 97, y + 86);
            detailButton("Бонус", Detail.BONUS, x + 194, y + 86);
        }

        if (card != null && card.incidentId() != null) {
            addRenderableWidget(Button.builder(Component.literal("Сдать работу"), button -> {
                button.active = false;
                ContractNetwork.request(panel.foreman(), ContractNetwork.Action.SUBMIT, 0, card.incidentId());
            }).bounds(x, y + 150, WIDTH - 24, 20).build());
        } else if (isActiveConstruction(card)) {
            action("Сдать", ContractNetwork.Action.SUBMIT, 0, x, y + 150, 55).active = panel.pauseReason().isEmpty();
            action("Отменить", ContractNetwork.Action.CANCEL, 0, x + 60, y + 150, 70);
            int price = ContractBalance.Difficulty.values()[card.level() - 1].extraDayPrice();
            Button extra = action("Доп. сутки — " + price + " изумр.", ContractNetwork.Action.BUY_TIME, 0,
                    x + 135, y + 150, WIDTH - 159);
            extra.active = card.canBuyTime();
        } else if (card != null) {
            Button accept = action("Принять заказ", ContractNetwork.Action.ACCEPT, card.slot(), x, y + 150, WIDTH - 24);
            accept.active = card.available();
        }
        Button claim = action("Получить выплату · " + panel.unpaid(), ContractNetwork.Action.CLAIM, 0,
                x, y + 176, 176);
        claim.active = panel.unpaid() > 0;
        Button demolitions = action("Сносы — " + panel.failed(), ContractNetwork.Action.SHOW_DEMOLITIONS, 0,
                x + 181, y + 176, WIDTH - 205);
        demolitions.active = panel.failed() > 0;
    }

    private ContractNetwork.Card selectedCard() {
        return selected < panel.cards().size() ? panel.cards().get(selected) : null;
    }

    private boolean isActiveConstruction(ContractNetwork.Card card) {
        return card != null && panel.state() == ContractNetwork.State.ACTIVE && card.available() && card.remaining() >= 0;
    }

    private Button selector(String label, int index, int x, int y, int width) {
        return addRenderableWidget(Button.builder(Component.literal(label), button -> {
            selected = index;
            detailOffset = 0;
            rebuildWidgets();
        }).bounds(x, y, width, 20).build());
    }

    private void detailButton(String label, Detail next, int x, int y) {
        Button button = addRenderableWidget(Button.builder(Component.literal(label), clicked -> {
            detail = next;
            detailOffset = 0;
            rebuildWidgets();
        }).bounds(x, y, 94, 18).build());
        button.active = detail != next;
    }

    private Button action(String label, ContractNetwork.Action action, int choice, int x, int y, int width) {
        return addRenderableWidget(Button.builder(Component.literal(label), button -> {
            button.active = false;
            ContractNetwork.request(panel.foreman(), action, choice);
        }).bounds(x, y, width, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int x = (width - WIDTH) / 2;
        int y = (height - HEIGHT) / 2;
        graphics.fill(x, y, x + WIDTH, y + HEIGHT, 0xF8212932);
        graphics.fill(x, y, x + WIDTH, y + 2, 0xFF5396C4);
        graphics.drawString(font, title, x + 12, y + 11, 0xFFFFFF, false);
        if (panel.dialogue()) {
            graphics.drawWordWrap(font, Component.literal("Работа у нас найдётся. Посмотрим, что ты умеешь строить."),
                    x + 12, y + 44, WIDTH - 24, 0xE4E9ED);
            if (!panel.lastCall().isEmpty()) {
                graphics.drawWordWrap(font, Component.literal("Последний звонок · " + panel.lastCall()),
                        x + 12, y + 79, WIDTH - 24, 0x8BCCF3);
            }
        } else if (lastCallOpen) {
            graphics.drawString(font, "Последний звонок прораба", x + 12, y + 44, 0x8BCCF3, false);
            graphics.drawWordWrap(font, Component.literal(panel.lastCall()), x + 12, y + 63, WIDTH - 24, 0xE4E9ED);
        } else {
            renderOrder(graphics, mouseX, mouseY, x + 12, y + 55);
        }
        if (!panel.feedback().isEmpty()) {
            graphics.drawString(font, font.plainSubstrByWidth(panel.feedback(), WIDTH - 24),
                    x + 12, y + 207, 0xF3D88C, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderOrder(GuiGraphics graphics, int mouseX, int mouseY, int x, int y) {
        ContractNetwork.Card card = selectedCard();
        boolean active = isActiveConstruction(card);
        if (card == null) {
            String message = panel.state() == ContractNetwork.State.NOT_READY ? "Первые заказы появятся завтра." : "Нет места.";
            graphics.drawString(font, message, x, y, 0xE4E9ED, false);
            return;
        }
        boolean paused = active && !panel.pauseReason().isEmpty();
        String state = paused ? " · на паузе" : active || card.incidentId() != null ? " · в работе" : "";
        graphics.drawString(font, card.title() + " · ур. " + card.level() + state,
                x, y, 0x8BCCF3, false);
        String header = "Оплата " + card.wage() + " / " + (card.wage() + card.bonus())
                + (card.remaining() >= 0 ? " · " + ContractClient.days(card.remaining()) : " · без срока");
        graphics.drawString(font, header, x, y + 11, 0xE4E9ED, false);
        if (paused) {
            graphics.drawString(font, "Причина паузы · " + panel.pauseReason(), x, y + 22, 0xF3D88C, false);
        } else if (card.remaining() < 0) {
            graphics.drawString(font, "Комната на стройке · " + card.bonusLabel(), x, y + 22, 0xE4E9ED, false);
            if (!card.available()) {
                graphics.drawString(font, card.unavailableReason(), x, y + 33, 0xF3D88C, false);
            } else if (card.canParallel() && card.incidentId() == null) {
                graphics.drawString(font, "Можно принять дополнительно", x, y + 33, 0x91DE92, false);
            }
        } else if (!card.available()) {
            graphics.drawString(font, card.unavailableReason(), x, y + 22, 0xF3D88C, false);
        } else if (active) {
            graphics.drawString(font, "Позиций с расхождениями " + card.missing(), x, y + 22, 0xE4E9ED, false);
        }
        if (card.remaining() < 0) {
            return;
        }
        int firstRow = y + 55;
        if (detail == Detail.BONUS) {
            if (card.bonusLabel().isEmpty()) {
                graphics.drawString(font, "В этом заказе нет бонусных условий.", x, firstRow, 0xE4E9ED, false);
                return;
            }
            String label = Component.translatable(card.bonusLabel()).getString();
            String amount = active ? card.bonusFound() + " / " + card.bonusTarget() : "×" + card.bonusTarget();
            graphics.drawString(font, label + " " + amount + " · +" + card.bonus() + " изумр.", x, firstRow,
                    active && card.bonusFound() == card.bonusTarget() ? 0x91DE92 : 0xE4E9ED, false);
            graphics.drawString(font, "Ровно " + card.bonusTarget() + " внутри комнаты", x, firstRow + 11, 0xE4E9ED, false);
            return;
        }
        if (detail == Detail.CONDITIONS) {
            for (int index = detailOffset; index < Math.min(card.conditions().size(), detailOffset + 4); index++) {
                graphics.drawString(font, card.conditions().get(index), x, firstRow + (index - detailOffset) * 10, 0xE4E9ED, false);
            }
            return;
        }
        int limit = Math.min(card.materials().size(), detailOffset + 4);
        for (int index = detailOffset; index < limit; index++) {
            ContractNetwork.Material material = card.materials().get(index);
            int rowY = firstRow + (index - detailOffset) * 10;
            String label = Component.translatable(material.label()).getString();
            String amount = active ? material.placed() + " / " + material.needed() : "×" + material.needed();
            int color = active && material.placed() == material.needed() ? 0x91DE92 : 0xE4E9ED;
            graphics.drawString(font, label + " " + amount + (material.substitutes().isEmpty() ? "" : " ▸"), x, rowY, color, false);
            if (!material.substitutes().isEmpty() && mouseX >= x && mouseX <= x + WIDTH - 24
                    && mouseY >= rowY && mouseY < rowY + 11) {
                List<Component> lines = java.util.Arrays.stream(material.substitutes().split(","))
                        .map(key -> (Component) Component.translatable(key)).toList();
                graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        ContractNetwork.Card card = selectedCard();
        if (!lastCallOpen && card != null && card.remaining() >= 0 && detail != Detail.BONUS) {
            int rows = detail == Detail.MATERIALS ? card.materials().size() : card.conditions().size();
            int maximum = Math.max(0, rows - 4);
            detailOffset = Math.max(0, Math.min(maximum, detailOffset - (int) Math.signum(delta)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
