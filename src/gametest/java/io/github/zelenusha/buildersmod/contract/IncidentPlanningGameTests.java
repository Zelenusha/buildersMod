package io.github.zelenusha.buildersmod.contract;

import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint;
import io.github.zelenusha.buildersmod.maintenance.IncidentState;
import io.github.zelenusha.buildersmod.site.ConstructionSite;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("buildersmod")
@PrefixGameTestTemplate(false)
public final class IncidentPlanningGameTests {
    @GameTest(template = "empty")
    public static void waitsForUnloadedRoomsAndSelectsOnlyFeasibleIncidents(GameTestHelper helper) {
        var level = helper.getLevel();
        var site = level.getServer().getLevel(ConstructionSite.SITE_LEVEL);
        var data = new ContractData();
        UUID owner = UUID.randomUUID();
        var account = data.account(owner);
        long seed = 1873;
        account.pendingIncidentSeed = seed;
        BlockPos wet = helper.absolutePos(new BlockPos(0, 11, 0));
        var far = makeRoom(new BlockPos(1_000_000, ConstructionSite.START_FLOOR_Y + 2, 1_000_000));
        account.rooms.add(far);
        var restored = ContractData.load(data.save(new CompoundTag())).account(owner);
        helper.assertTrue(restored.pendingIncidentSeed != null && restored.pendingIncidentSeed == seed,
                "The single pending weekly roll survives reload");
        var waiting = IncidentWork.chooseWeekly(site, restored, RandomSource.create(seed));
        helper.assertTrue(waiting.offer() == null && waiting.waitingForChunks(), "Unloaded rooms defer rather than consume an assignment");
        helper.assertTrue(site.getChunkSource().getChunkNow(far.anchor().getX() >> 4, far.anchor().getZ() >> 4) == null,
                "Weekly planning does not load the worksite itself");

        var local = makeRoom(wet);
        restored.rooms.add(local);
        var blueprint = local.blueprint();
        for (BlockPos position : blueprint.bounds().positions()) {
            level.setBlock(position, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        level.setBlock(wet.east(2), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(wet, Blocks.WATER.defaultBlockState(), Block.UPDATE_CLIENTS);
        var chosen = IncidentWork.chooseWeekly(level, restored, RandomSource.create(seed)).offer();
        helper.assertTrue(chosen != null && chosen.kind() == IncidentState.Kind.CLEANUP
                        && chosen.room().equals(blueprint.bounds().min()), "An unsuitable fire, flood or spawn spot cannot consume the roll");
        var manual = IncidentWork.chooseManual(level, data.account(UUID.randomUUID()), RandomSource.create(seed), 3);
        helper.assertTrue(manual == null, "No manual work is offered without an eligible room");
        // An existing manual offer occupies this room, leaving the other flexible slot available for construction.
        var manualAccount = new ContractData().account(owner);
        manualAccount.rooms.add(local);
        manualAccount.incidentOffers[0] = IncidentWork.chooseManual(level, manualAccount, RandomSource.create(seed), 3);
        helper.assertTrue(manualAccount.incidentOffers[0] != null
                && IncidentWork.chooseManual(level, manualAccount, RandomSource.create(seed), 4) == null,
                "The same room is not advertised twice in the flexible slots");
        helper.succeed();
    }

    private static ContractData.Accepted makeRoom(BlockPos wet) {
        BlockPos wall = wet.east(2);
        var bounds = new RoomBlueprint.Bounds(wet.below(), wall.above());
        var interior = new RoomBlueprint.Bounds(wet, wet.east());
        var blueprint = RoomBlueprint.of(bounds, List.of(interior), Map.of(wall,
                new RoomBlueprint.Requirement(Blocks.STONE.defaultBlockState(), RoomBlueprint.Criterion.WALLS)),
                List.of(new RoomBlueprint.Door(wet, Direction.WEST)), Optional.empty());
        return new ContractData.Accepted(wet, Direction.WEST, ContractBalance.Difficulty.ONE, 0, false, blueprint,
                5, ContractData.Kind.ROOM, 0, 0, Map.of());
    }
}
