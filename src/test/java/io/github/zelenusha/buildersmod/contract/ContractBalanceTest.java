package io.github.zelenusha.buildersmod.contract;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ContractBalanceTest {
    @Test
    void earlyBonusRoundsUpAndStopsAfterBuyingTime() {
        assertEquals(0, ContractBalance.earlyBonus(0, false));
        assertEquals(1, ContractBalance.earlyBonus(1, false));
        assertEquals(1, ContractBalance.earlyBonus(24_000, false));
        assertEquals(2, ContractBalance.earlyBonus(30_000, false));
        assertEquals(0, ContractBalance.earlyBonus(30_000, true));
    }

    @Test
    void timerWritesDaysInRussianWithFractionsOfADay() {
        long day = ContractBalance.TICKS_PER_DAY;
        assertEquals("0,25 дня", ContractBalance.daysText(day / 4));
        assertEquals("1 день", ContractBalance.daysText(day));
        assertEquals("3 дня", ContractBalance.daysText(3 * day));
        assertEquals("5 дней", ContractBalance.daysText(5 * day));
        assertEquals("12 дней", ContractBalance.daysText(12 * day));
        assertEquals("21 день", ContractBalance.daysText(21 * day));
        assertEquals("2,99 дня", ContractBalance.daysText(3 * day - 200));
        assertEquals("<0,01 дня", ContractBalance.daysText(1));
        assertEquals("0 дней", ContractBalance.daysText(0));
    }

    @Test
    void fiveLevelsFollowTheDifficultyTable() {
        int[][] table = {{3, 5, 2}, {5, 10, 3}, {8, 18, 4}, {12, 30, 6}, {18, 50, 8}};
        for (ContractBalance.Difficulty level : ContractBalance.Difficulty.values()) {
            int[] row = table[level.ordinal()];
            assertEquals(row[0] * ContractBalance.TICKS_PER_DAY, level.duration());
            assertEquals(row[1], level.emeralds());
            assertEquals(row[2], level.extraDayPrice());
        }
    }

    @Test
    void incompleteFineRoundsPositionsUpAndAddsEachCriterion() {
        assertEquals(0, ContractBalance.incompleteFine(0, 0));
        assertEquals(5, ContractBalance.incompleteFine(10, 2));
        assertEquals(6, ContractBalance.incompleteFine(11, 2));
        assertEquals(14, ContractBalance.incompleteFine(100, 2));
    }
}
