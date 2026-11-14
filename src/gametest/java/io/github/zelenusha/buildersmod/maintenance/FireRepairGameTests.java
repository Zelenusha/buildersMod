package io.github.zelenusha.buildersmod.maintenance;

import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint;
import io.github.zelenusha.buildersmod.blueprint.SampleRoomBlueprint;
import io.github.zelenusha.buildersmod.contract.ContractBalance.Difficulty;
import io.github.zelenusha.buildersmod.site.ConstructionSite;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("buildersmod")
@PrefixGameTestTemplate(false)
public class FireRepairGameTests {
    @GameTest(template = "empty")
    public static void fireRequiresRepairOfCompletedRoomButOnlyExtinguishesUnfinishedRoom(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos anchor = helper.absolutePos(new BlockPos(0, 5, 0));
        RoomBlueprint completed = SampleRoomBlueprint.at(anchor, Direction.EAST);
        RoomBlueprint unfinished = SampleRoomBlueprint.at(completed.doors().get(1).lowerPosition(), Direction.EAST);
        completed.requirements().forEach((pos, requirement) -> place(level, pos, requirement.expectedState()));
        BlockPos initialFlame = unfinished.bounds().min().relative(Direction.WEST).above(2).south();
        BlockPos spread = initialFlame.east();
        // The empty test template does not guarantee disposable blocks below the upper parts of either room.
        BlockPos support = spread.below();
        BlockState originalSupport = level.getBlockState(support);
        BlockState originalAtSpread = level.getBlockState(spread);
        place(level, support, Blocks.STONE.defaultBlockState());
        BlockState originalAtIgnition = level.getBlockState(initialFlame);
        BlockPos damaged = completed.bounds().min().above(4);
        FireRepair repair = new FireRepair();
        IncidentState fire = IncidentState.fire(UUID.randomUUID(), Difficulty.TWO, false,
                completed.bounds().min(), initialFlame);

        repair.observe(level, fire, List.of(completed), unfinished);
        place(level, initialFlame, Blocks.FIRE.defaultBlockState());
        helper.assertTrue(level.getBlockState(initialFlame).is(BlockTags.FIRE), "Origin flame has support");
        repair.observe(level, fire, List.of(completed), unfinished);
        place(level, spread, Blocks.FIRE.defaultBlockState());
        helper.assertTrue(level.getBlockState(spread).is(BlockTags.FIRE), "Spread flame has support inside the next room");
        repair.observe(level, fire, List.of(completed), unfinished);
        helper.assertTrue(fire.affectedRooms().contains(unfinished.bounds().min()), "Fire reaches the next unfinished room");
        helper.assertTrue(repair.submit(level, fire, List.of(completed), unfinished).isEmpty(), "Live flames prevent submission");

        repair = FireRepair.load(repair.save());
        fire = IncidentState.load(fire.save());
        place(level, initialFlame, originalAtIgnition);
        place(level, spread, originalAtSpread);
        place(level, support, originalSupport);
        place(level, damaged, Blocks.AIR.defaultBlockState());
        helper.assertTrue(repair.submit(level, fire, List.of(completed), null).isEmpty(), "Extinguished fire still requires completed-room repair");
        place(level, damaged, completed.requirements().get(damaged).expectedState());
        helper.assertTrue(repair.submit(level, fire, List.of(completed), null).orElseThrow() == Difficulty.TWO.emeralds(),
                "The unloaded order reference is unnecessary for a previously tracked unfinished room");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void starterRepairUsesActualPreFireBlocksAfterReload(GameTestHelper helper) {
        ServerLevel site = helper.getLevel().getServer().getLevel(ConstructionSite.SITE_LEVEL);
        helper.assertTrue(site != null, "The construction dimension must be available");
        for (BlockPos corner : List.of(new BlockPos(-4, 64, -4), new BlockPos(-4, 64, 4),
                new BlockPos(4, 64, -4), new BlockPos(4, 64, 4))) {
            site.getChunkAt(corner);
        }
        BlockPos ornament = new BlockPos(2, 65, 2);
        BlockPos ignition = ornament.north();
        BlockState original = site.getBlockState(ornament);
        BlockState originalAtIgnition = site.getBlockState(ignition);
        place(site, ornament, Blocks.BIRCH_PLANKS.defaultBlockState());
        FireRepair repair = new FireRepair();
        IncidentState fire = IncidentState.fire(UUID.randomUUID(), Difficulty.ONE, false,
                FireRepair.starterBounds().min(), ignition);
        repair.observe(site, fire, List.of(), null);
        place(site, ignition, Blocks.FIRE.defaultBlockState());
        repair.observe(site, fire, List.of(), null);
        repair = FireRepair.load(repair.save());
        fire = IncidentState.load(fire.save());
        place(site, ignition, originalAtIgnition);
        place(site, ornament, Blocks.OAK_PLANKS.defaultBlockState());
        helper.assertTrue(repair.submit(site, fire, List.of(), null).isEmpty(), "A template block cannot replace a changed pre-fire block");
        place(site, ornament, Blocks.BIRCH_PLANKS.defaultBlockState());
        helper.assertTrue(repair.submit(site, fire, List.of(), null).isPresent(), "Restoring the actual pre-fire block passes after persistence");
        place(site, ornament, original);
        helper.succeed();
    }

    private static void place(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
    }
}
