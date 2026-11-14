package io.github.zelenusha.buildersmod.contract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.IntUnaryOperator;

/** Server-owned sequence and timer for one attempt to appeal a fine. */
public final class AppealPuzzle {
    public static final long DURATION_NANOS = TimeUnit.SECONDS.toNanos(15);
    private static final int CARD_COUNT = 7;

    public enum Status {
        IN_PROGRESS,
        WON,
        WRONG_ORDER,
        EXPIRED
    }

    public record Card(int id, String text) {}

    private final UUID fineId;
    private final UUID attemptId = UUID.randomUUID();
    private final long startedNanos;
    private final List<Card> cards;
    private final List<Card> sequence;
    private final String hint;
    private int progress;
    private Status status = Status.IN_PROGRESS;
    private long wonRemainingNanos;

    private AppealPuzzle(FineLedger.Fine fine, IntUnaryOperator nextInt, long nowNanos) {
        fineId = fine.id();
        startedNanos = nowNanos;
        Argument argument = argumentFor(fine.reason());
        int requiredCount = 6 - fine.audacity();
        hint = "Штраф: «" + fine.reason() + "». " + argument.hint()
                + " Выберите первые " + requiredCount + " из пяти частей аргумента по порядку.";

        List<String> wrong = new ArrayList<>(List.of("Сперва поспорим о погоде.", "Запишем новый штраф на всякий случай.",
                "Давайте разыграем зарплату в кости.", "Пусть торговец выберет цвет крыши.",
                "Перенесём офис на крышу.", "Вместо договора прочитаем меню столовой.",
                "Предлагаю осмотреть чужую постройку.", "Сначала купим ещё один верстак."));
        shuffle(wrong, nextInt);
        List<String> ordered = argument.sequence().subList(0, requiredCount);
        List<String> choices = new ArrayList<>(ordered);
        choices.addAll(wrong.subList(0, CARD_COUNT - requiredCount));
        shuffle(choices, nextInt);

        List<Card> presented = new ArrayList<>(CARD_COUNT);
        for (int i = 0; i < choices.size(); i++) {
            presented.add(new Card(i, choices.get(i)));
        }
        cards = List.copyOf(presented);
        sequence = ordered.stream().map(text -> cards.get(choices.indexOf(text))).toList();
    }

    public static AppealPuzzle start(FineLedger.Fine fine, IntUnaryOperator nextInt, long nowNanos) {
        return new AppealPuzzle(Objects.requireNonNull(fine), Objects.requireNonNull(nextInt), nowNanos);
    }

    public UUID fineId() {
        return fineId;
    }

    public UUID attemptId() {
        return attemptId;
    }

    public List<Card> cards() {
        return cards;
    }

    public String hint() {
        return hint;
    }

    public int requiredCount() {
        return sequence.size();
    }

    public int progress() {
        return progress;
    }

    public long remainingNanos(long nowNanos) {
        return switch (status) {
            case WON -> wonRemainingNanos;
            case IN_PROGRESS -> Math.max(0, DURATION_NANOS - elapsedNanos(nowNanos));
            case WRONG_ORDER, EXPIRED -> 0;
        };
    }

    public int remainingFullSeconds(long nowNanos) {
        return (int) TimeUnit.NANOSECONDS.toSeconds(remainingNanos(nowNanos));
    }

    public Status status(long nowNanos) {
        if (status == Status.IN_PROGRESS && elapsedNanos(nowNanos) >= DURATION_NANOS) {
            status = Status.EXPIRED;
        }
        return status;
    }

    public Status choose(int cardId, long nowNanos) {
        if (status(nowNanos) != Status.IN_PROGRESS || cardId < 0 || cardId >= cards.size()) {
            return status;
        }
        if (sequence.get(progress).id() != cardId) {
            status = Status.WRONG_ORDER;
        } else if (++progress == sequence.size()) {
            wonRemainingNanos = remainingNanos(nowNanos);
            status = Status.WON;
        }
        return status;
    }

    private long elapsedNanos(long nowNanos) {
        // nanoTime is monotonic even if its signed long wraps. Differences remain valid for this short duration.
        return Math.max(0, nowNanos - startedNanos);
    }

    private static <T> void shuffle(List<T> items, IntUnaryOperator nextInt) {
        for (int i = items.size() - 1; i > 0; i--) {
            Collections.swap(items, i, nextInt.applyAsInt(i + 1));
        }
    }

    private static Argument argumentFor(String reason) {
        return switch (reason) {
            case "Гороскоп велел всё переделать" -> new Argument(
                    "Соберите ответ о гороскопе. Порядок частей: источник претензии, его статус, критерий сдачи, итог проверки, просьба.",
                    List.of("Основание претензии — гороскоп.", "Гороскоп не входит в договор.",
                            "Сдачу определяют условия чертежа.", "Обязательные условия выполнены.", "Прошу отменить штраф."));
            case "Стена кривая, если смотреть лёжа на боку" -> new Argument(
                    "Соберите ответ о стене. Порядок частей: предмет претензии, способ осмотра, способ проверки, итог проверки, просьба.",
                    List.of("Прораб оспаривает ровность стены.", "Он смотрел на стену лёжа на боку.",
                            "Положение блоков сверяется с чертежом.", "Блоки стоят на заданных координатах.", "Прошу отменить штраф."));
            case "У потолка недостаточно бодрый цвет" -> new Argument(
                    "Соберите ответ о потолке. Порядок частей: предмет претензии, мнение прораба, условие договора, результат, просьба.",
                    List.of("Претензия касается цвета потолка.", "Бодрость цвета — мнение прораба.",
                            "Договор требует материал из чертежа.", "На потолке использован требуемый материал.", "Прошу отменить штраф."));
            default -> new Argument(
                    "Попросите перепроверить начисление. Порядок частей: запись, сумма, основание, расчёт, просьба.",
                    List.of("Прошу открыть запись о штрафе.", "Сначала сверим назначенную сумму.",
                            "Затем сверим основание с условиями заказа.", "По условиям заказа проверим расчёт.", "Прошу пересмотреть штраф."));
        };
    }

    private record Argument(String hint, List<String> sequence) {}
}
