package io.github.zelenusha.buildersmod.contract;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FineLedgerTest {
    @Test
    void withholdsOldestFirstAcrossPaymentsAndReload() {
        FineLedger ledger = new FineLedger();
        UUID older = ledger.issue(10, "Incomplete room", 1).id();
        UUID newer = ledger.issue(4, "Absurd fine", 5).id();

        assertEquals(0, ledger.apply(6));
        assertEquals(4, ledger.find(older).outstanding());
        assertEquals(4, ledger.find(newer).outstanding());

        FineLedger restored = new FineLedger(ledger.entries());
        assertEquals(0, restored.apply(7));
        assertEquals(10, restored.find(older).withheld());
        assertEquals(3, restored.find(newer).withheld());
        assertEquals(2, restored.apply(3));
    }

    @Test
    void winningCancelsRemainderAndReturnsSeparateAwardsOnce() {
        FineLedger ledger = new FineLedger();
        UUID older = ledger.issue(10, "Incomplete room", 1).id();
        UUID newer = ledger.issue(4, "Absurd fine", 5).id();
        ledger.apply(6);

        assertTrue(ledger.beginAttempt(older));
        assertEquals(new FineLedger.AppealAward(6, 14), ledger.appealResolved(older, 4).orElseThrow());
        assertEquals(0, ledger.find(older).outstanding());
        assertEquals(4, ledger.find(newer).outstanding());
        assertEquals(3, ledger.apply(7));
        assertFalse(ledger.beginAttempt(older));
        assertTrue(ledger.appealResolved(older, 4).isEmpty());

        FineLedger restored = new FineLedger(ledger.entries());
        assertTrue(restored.find(older).overturned());
        assertFalse(restored.beginAttempt(older));
        assertTrue(restored.appealResolved(older, 4).isEmpty());
    }

    @Test
    void permitsTwoAttemptsAndPersistsSpentAttempts() {
        FineLedger ledger = new FineLedger();
        UUID fine = ledger.issue(5, "Incomplete room", 1).id();
        assertTrue(ledger.appealResolved(fine, 3).isEmpty());
        assertTrue(ledger.beginAttempt(fine));
        assertFalse(ledger.beginAttempt(fine));

        FineLedger restored = new FineLedger(ledger.entries());
        assertTrue(restored.appealResolved(fine, 3).isEmpty());
        assertTrue(restored.beginAttempt(fine));
        restored.expireAttempt(fine);
        assertFalse(restored.beginAttempt(fine));
        assertTrue(restored.appealResolved(fine, 3).isEmpty());
        assertEquals(5, restored.find(fine).outstanding());
    }

    @Test
    void paidFineCanBeAppealedAndReissuedIdDoesNotResetIt() {
        FineLedger ledger = new FineLedger();
        UUID id = UUID.randomUUID();
        ledger.issue(id, 5, "Absurd fine", 5);
        ledger.apply(5);
        assertEquals(5, ledger.issue(id, 5, "Absurd fine", 5).withheld());
        assertEquals(1, ledger.entries().size());

        assertTrue(ledger.beginAttempt(id));
        ledger.expireAttempt(id);
        assertTrue(ledger.beginAttempt(id));
        assertEquals(new FineLedger.AppealAward(5, 5), ledger.appealResolved(id, 0).orElseThrow());
    }
}
