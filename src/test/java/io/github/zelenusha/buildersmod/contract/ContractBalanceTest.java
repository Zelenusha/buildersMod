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
    void incompleteFineRoundsPositionsUpAndAddsEachCriterion() {
        assertEquals(0, ContractBalance.incompleteFine(0, 0));
        assertEquals(5, ContractBalance.incompleteFine(10, 2));
        assertEquals(6, ContractBalance.incompleteFine(11, 2));
        assertEquals(14, ContractBalance.incompleteFine(100, 2));
    }
}
