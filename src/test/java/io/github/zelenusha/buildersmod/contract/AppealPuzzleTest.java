package io.github.zelenusha.buildersmod.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.zelenusha.buildersmod.contract.AppealPuzzle.Status;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AppealPuzzleTest {
    private static final String HOROSCOPE = "Гороскоп велел всё переделать";
    private static final List<String> HOROSCOPE_ANSWER = List.of("Основание претензии — гороскоп.",
            "Гороскоп не входит в договор.", "Сдачу определяют условия чертежа.",
            "Обязательные условия выполнены.", "Прошу отменить штраф.");
    private static final long START = 80L;

    @Test
    void randomizesSevenUniqueCardsAndShortensTheSequenceByAudacity() {
        for (int audacity = 1; audacity <= 5; audacity++) {
            FineLedger.Fine fine = fine(audacity);
            AppealPuzzle first = AppealPuzzle.start(fine, new Random(37)::nextInt, START);
            AppealPuzzle sameSeed = AppealPuzzle.start(fine, new Random(37)::nextInt, START);
            AppealPuzzle differentSeed = AppealPuzzle.start(fine, new Random(77)::nextInt, START);

            assertEquals(fine.id(), first.fineId());
            assertNotEquals(first.attemptId(), sameSeed.attemptId());
            assertEquals(6 - audacity, first.requiredCount());
            assertEquals(7, first.cards().size());
            assertEquals(7L, first.cards().stream().map(AppealPuzzle.Card::text).distinct().count());
            assertEquals(first.cards(), sameSeed.cards());
            assertNotEquals(first.cards(), differentSeed.cards());
            assertTrue(first.hint().contains(HOROSCOPE));
            assertEquals(first.requiredCount(), first.cards().stream()
                    .map(AppealPuzzle.Card::text).filter(HOROSCOPE_ANSWER::contains).count());
        }
    }

    @Test
    void verifiesOrderAndIgnoresOutOfRangeCardIds() {
        AppealPuzzle puzzle = AppealPuzzle.start(fine(2), new Random(8)::nextInt, START);
        assertEquals(Status.IN_PROGRESS, puzzle.choose(-1, START));
        assertEquals(Status.IN_PROGRESS, puzzle.choose(7, START));
        assertEquals(0, puzzle.progress());
        assertEquals(Status.IN_PROGRESS, puzzle.choose(id(puzzle, HOROSCOPE_ANSWER.get(0)), START));
        assertEquals(Status.WRONG_ORDER, puzzle.choose(id(puzzle, HOROSCOPE_ANSWER.get(2)), START));
        assertEquals(Status.WRONG_ORDER, puzzle.choose(id(puzzle, HOROSCOPE_ANSWER.get(1)), START));
        assertEquals(1, puzzle.progress());
    }

    @Test
    void freezesWinTimeAndLedgerAppliesTheAwardOnce() {
        FineLedger ledger = new FineLedger();
        FineLedger.Fine fine = ledger.issue(10, HOROSCOPE, 2);
        assertEquals(0, ledger.apply(6));
        assertTrue(ledger.beginAttempt(fine.id()));
        AppealPuzzle puzzle = AppealPuzzle.start(fine, new Random(4)::nextInt, START);
        long atWin = START + TimeUnit.MILLISECONDS.toNanos(10_300);

        for (String text : HOROSCOPE_ANSWER.subList(0, puzzle.requiredCount())) {
            puzzle.choose(id(puzzle, text), atWin);
        }

        assertEquals(Status.WON, puzzle.status(START + AppealPuzzle.DURATION_NANOS));
        assertEquals(4, puzzle.remainingFullSeconds(atWin + TimeUnit.SECONDS.toNanos(8)));
        assertEquals(new FineLedger.AppealAward(6, 14),
                ledger.appealResolved(fine.id(), puzzle.remainingFullSeconds(atWin)).orElseThrow());
        assertEquals(0, ledger.find(fine.id()).outstanding());
        assertTrue(ledger.appealResolved(fine.id(), 4).isEmpty());
        assertFalse(ledger.beginAttempt(fine.id()));
    }

    @Test
    void enforcesTheExclusiveDeadlineEvenWhenMonotonicTimeWraps() {
        long start = Long.MAX_VALUE - 100L;
        AppealPuzzle justInTime = AppealPuzzle.start(fine(5), new Random(9)::nextInt, start);
        long deadline = start + AppealPuzzle.DURATION_NANOS;
        assertEquals(Status.WON, justInTime.choose(id(justInTime, HOROSCOPE_ANSWER.get(0)), deadline - 1));
        assertEquals(0, justInTime.remainingFullSeconds(deadline - 1));

        AppealPuzzle tooLate = AppealPuzzle.start(fine(5), new Random(9)::nextInt, start);
        assertEquals(Status.EXPIRED, tooLate.choose(id(tooLate, HOROSCOPE_ANSWER.get(0)), deadline));
        assertEquals(0, tooLate.progress());
        assertEquals(0, tooLate.remainingNanos(deadline));
    }

    private static FineLedger.Fine fine(int audacity) {
        return new FineLedger.Fine(new UUID(11, 31), 5 * audacity, 0, HOROSCOPE, audacity, 0, false);
    }

    private static int id(AppealPuzzle puzzle, String text) {
        return puzzle.cards().stream().filter(card -> card.text().equals(text)).findFirst().orElseThrow().id();
    }
}
