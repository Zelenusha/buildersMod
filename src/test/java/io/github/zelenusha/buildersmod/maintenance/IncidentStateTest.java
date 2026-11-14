package io.github.zelenusha.buildersmod.maintenance;

import io.github.zelenusha.buildersmod.contract.ContractBalance;
import io.github.zelenusha.buildersmod.contract.ContractBalance.Difficulty;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IncidentStateTest {
    private static final BlockPos ROOM = new BlockPos(4, 65, 0);

    @Test
    void selectsDistinctEligiblePositionsConsistentlyAtEveryDifficulty() {
        List<BlockPos> positions = IntStream.range(0, 8).mapToObj(ROOM::east).toList();
        List<BlockPos> reversed = IntStream.iterate(7, index -> index >= 0, index -> index - 1)
                .mapToObj(positions::get).toList();

        int[] limits = {2, 2, 4, 4, 6};
        for (Difficulty difficulty : Difficulty.values()) {
            var cleanup = IncidentState.cleanup(UUID.randomUUID(), difficulty, false, ROOM, positions, 1, 43);
            var repeated = IncidentState.cleanup(UUID.randomUUID(), difficulty, true, ROOM, reversed, 1, 43);
            assertEquals(limits[difficulty.ordinal()], cleanup.initialBlocks().size());
            assertEquals(cleanup.initialBlocks(), repeated.initialBlocks());
            assertEquals(limits[difficulty.ordinal()] / 2,
                    IncidentState.flood(UUID.randomUUID(), difficulty, true, ROOM, positions, 43).initialBlocks().size());
        }
        assertEquals(List.of(ROOM), IncidentState.flood(UUID.randomUUID(), Difficulty.FIVE, true, ROOM,
                List.of(ROOM, ROOM), 43).initialBlocks());
    }

    @Test
    void partialGraffitiPersistsAndSubmissionPaysOnce() {
        var cleanup = IncidentState.cleanup(UUID.randomUUID(), Difficulty.THREE, true, ROOM, List.of(ROOM), 3, 3);
        assertTrue(cleanup.erasePatch(1));

        var restored = IncidentState.load(cleanup.save());
        assertEquals(cleanup.id(), restored.id());
        assertTrue(restored.automatic());
        assertTrue(restored.erasedPatches().get(1));
        assertFalse(restored.erasePatch(1));
        assertTrue(restored.submitCleanup(pos -> true).isEmpty());
        assertTrue(restored.erasePatch(0));
        assertTrue(restored.erasePatch(2));
        assertTrue(restored.submitCleanup(pos -> false).isEmpty());
        assertEquals(OptionalInt.of(18), restored.submitCleanup(pos -> true));
        assertTrue(IncidentState.load(restored.save()).submitCleanup(pos -> true).isEmpty());
    }

    @Test
    void evictionFailurePreservesSurvivorsAndCanNeverPay() {
        UUID killed = UUID.randomUUID();
        UUID survivor = UUID.randomUUID();
        var eviction = IncidentState.eviction(UUID.randomUUID(), Difficulty.FIVE, false, ROOM,
                List.of(killed, survivor), 43);
        assertTrue(eviction.recordTargetDeath(killed));
        assertFalse(eviction.recordTargetDeath(killed));
        assertTrue(eviction.submitEviction().isEmpty());
        assertTrue(eviction.recordPlayerDeath());

        var restored = IncidentState.load(eviction.save());
        assertEquals(List.of(survivor), restored.remainingEvictionTargets());
        assertEquals(IncidentState.Status.FAILED, restored.status());
        assertFalse(restored.recordTargetDeath(survivor));
        assertTrue(restored.submitEviction().isEmpty());
    }

    @Test
    void submissionRequiresAllAffectedFireRoomsAndRemovingFloodFill() {
        BlockPos neighbor = ROOM.east(4);
        var fire = IncidentState.fire(UUID.randomUUID(), Difficulty.TWO, true, ROOM, ROOM.above());
        assertTrue(fire.addFireRoom(neighbor));
        assertFalse(fire.addFireRoom(neighbor));
        var restoredFire = IncidentState.load(fire.save());
        assertTrue(restoredFire.submitFire(pos -> !pos.equals(neighbor), pos -> true).isEmpty());
        assertTrue(restoredFire.submitFire(pos -> true, pos -> !pos.equals(neighbor)).isEmpty());
        assertEquals(OptionalInt.of(10), restoredFire.submitFire(pos -> true, pos -> true));

        var flood = IncidentState.flood(UUID.randomUUID(), Difficulty.ONE, false, ROOM, List.of(ROOM.above()), 43);
        assertTrue(flood.recordFloodFill(ROOM.above()));
        var restoredFlood = IncidentState.load(flood.save());
        assertTrue(restoredFlood.submitFlood(pos -> true, pos -> false).isEmpty());
        assertTrue(restoredFlood.submitFlood(pos -> false, pos -> true).isEmpty());
        assertEquals(OptionalInt.of(5), restoredFlood.submitFlood(pos -> true, ROOM.above()::equals));
    }

    @Test
    void weeklySchedulePersistsOnePendingAssignmentAndDeliversOnlyOnce() {
        long day = ContractBalance.TICKS_PER_DAY;
        long start = 42 * day;
        var schedule = new IncidentState.Schedule(start);
        var event = IncidentState.fire(UUID.randomUUID(), Difficulty.ONE, true, ROOM, ROOM.above());
        RandomSource guaranteed = RandomSource.create(2L);
        assertFalse(schedule.roll(start + 6 * day, false, guaranteed));
        assertTrue(schedule.roll(start + 7 * day, false, guaranteed));
        assertFalse(schedule.roll(start + 7 * day, false, guaranteed));
        assertTrue(schedule.queue(event));
        assertFalse(schedule.queue(event));

        var restored = IncidentState.Schedule.load(schedule.save());
        assertFalse(restored.roll(start + 14 * day, false, guaranteed));
        assertTrue(restored.takePending(candidate -> false, false).isEmpty());
        assertEquals(event.id(), restored.takePending(candidate -> true, false).orElseThrow().id());
        assertTrue(restored.takePending(candidate -> true, false).isEmpty());
    }
}
