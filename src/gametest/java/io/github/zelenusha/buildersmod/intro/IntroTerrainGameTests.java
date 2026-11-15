package io.github.zelenusha.buildersmod.intro;

import io.github.zelenusha.buildersmod.BuildersMod;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(BuildersMod.MOD_ID)
@PrefixGameTestTemplate(false)
public class IntroTerrainGameTests {
    @GameTest(template = "empty")
    public static void findsDryUnoccupiedPlotsAndLevelsSlopedApproaches(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos testOrigin = helper.absolutePos(BlockPos.ZERO);
        BlockPos ground = new BlockPos(testOrigin.getX() + 256, level.getMaxBuildHeight() - 20, testOrigin.getZ());
        int radius = 20;
        // The empty GameTest template does not reset this elevated fixture on subsequent server runs.
        for (BlockPos pos : BlockPos.betweenClosed(ground.offset(-radius, -4, -radius), ground.offset(radius, 9, radius))) {
            if (!level.isEmptyBlock(pos)) {
                set(level, pos, Blocks.AIR);
            }
        }
        for (int x = -radius; x <= radius; x++) {
            int slope = Math.floorMod(x + 1, 3) - 1;
            for (int z = -radius; z <= radius; z++) {
                BlockPos surface = ground.offset(x, slope, z);
                set(level, surface.below(2), Blocks.DIRT);
                set(level, surface.below(), Blocks.DIRT);
                set(level, surface, Blocks.GRASS_BLOCK);
            }
        }

        BlockPos westPool = ground.west(4);
        BlockPos eastPool = ground.east(4).above();
        for (BlockPos pool : List.of(westPool, eastPool)) {
            for (int z = -1; z <= 1; z++) {
                set(level, pool.south(z), Blocks.DIRT);
                set(level, pool.above().south(z), Blocks.WATER);
            }
        }
        BlockPos chestPos = ground.north(4).above();
        set(level, chestPos, Blocks.CHEST);
        if (!(level.getBlockEntity(chestPos) instanceof ChestBlockEntity chest)) {
            helper.fail("The occupied plot needs a real chest block entity");
            return;
        }
        ItemStack stored = new ItemStack(Items.DIAMOND, 7);
        chest.setItem(3, stored.copy());

        var sites = IntroStructures.findSites(level, ground.above(), new IntroData());
        if (sites == null) {
            helper.fail("The occupied and wet plots have dry, workable neighbors");
            return;
        }
        BlockPos dugout = sites.dugout();
        BlockPos office = sites.office();
        for (BlockPos obstruction : List.of(westPool.above(), eastPool.above(), chestPos)) {
            helper.assertTrue(!insideFootprint(dugout, 5, 5, obstruction) && !insideFootprint(office, 13, 11, obstruction),
                    "The chosen plots and approaches avoid the occupied and wet terrain");
        }
        helper.assertTrue(office.offset(6, 0, 5).distSqr(ground) > dugout.offset(2, 0, 2).distSqr(ground),
                "The office occupies a farther plot");
        List<BlockPos> fills = new ArrayList<>();
        List<BlockPos> cuts = new ArrayList<>();
        captureApproachChanges(level, dugout.offset(2, 1, -2), fills, cuts, true);
        captureApproachChanges(level, office.offset(6, 0, -2), fills, cuts, false);
        helper.assertTrue(!fills.isEmpty() || !cuts.isEmpty(), "An approach crosses uneven natural terrain");

        IntroStructures.build(level, sites);

        helper.assertTrue(fills.stream().allMatch(pos -> level.getBlockState(pos).is(Blocks.GRASS_BLOCK))
                        && cuts.stream().allMatch(level::isEmptyBlock),
                "Terrain preparation fills the low shoulder and cuts the high shoulder");
        checkApproach(helper, dugout.offset(2, 1, 0), dugout.getY() + 1, true);
        checkStandingPlayerPassesDugoutDoor(helper, dugout.offset(2, 1, 0));
        checkApproach(helper, office.offset(6, 1, 0), office.getY(), false);
        checkFoundation(helper, dugout, 5, 5, Blocks.DIRT);
        checkFoundation(helper, office, 13, 11, Blocks.COBBLESTONE);
        helper.assertTrue(level.getFluidState(westPool.above()).is(FluidTags.WATER)
                && level.getFluidState(eastPool.above()).is(FluidTags.WATER), "The pools stay in place");
        helper.assertTrue(level.getBlockEntity(chestPos) instanceof ChestBlockEntity preserved
                && ItemStack.matches(preserved.getItem(3), stored), "The occupied plot's chest and contents stay intact");
        checkLegacyDugoutRepair(helper, dugout);
        helper.succeed();
    }

    private static boolean insideFootprint(BlockPos origin, int width, int depth, BlockPos pos) {
        int x = pos.getX() - origin.getX();
        int z = pos.getZ() - origin.getZ();
        return x >= 0 && x < width && (z >= 0 && z < depth || z >= -2 && z < 0 && Math.abs(x - width / 2) <= 1);
    }

    private static void captureApproachChanges(ServerLevel level, BlockPos path, List<BlockPos> fills, List<BlockPos> cuts,
                                               boolean hasStair) {
        for (int x = -1; x <= 1; x++) {
            BlockPos surface = path.east(x);
            if ((x != 0 || !hasStair) && level.isEmptyBlock(surface)) {
                fills.add(surface);
            }
            if (!level.isEmptyBlock(surface.above())) {
                cuts.add(surface.above());
            }
        }
    }

    private static void checkApproach(GameTestHelper helper, BlockPos door, int groundY, boolean dugout) {
        ServerLevel level = helper.getLevel();
        var lower = level.getBlockState(door);
        var upper = level.getBlockState(door.above());
        helper.assertTrue(lower.is(Blocks.OAK_DOOR) && lower.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                && upper.is(Blocks.OAK_DOOR) && upper.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER,
                "The approach ends at a complete oak door");
        for (int step = 1; step <= 2; step++) {
            boolean lowerLanding = dugout && step == 1;
            BlockPos path = new BlockPos(door.getX(), groundY - (lowerLanding ? 1 : 0), door.getZ() - step);
            var surface = level.getBlockState(path);
            boolean entranceStair = dugout && step == 2;
            helper.assertTrue(entranceStair
                            ? surface.is(Blocks.COBBLESTONE_STAIRS) && surface.getValue(StairBlock.FACING) == Direction.NORTH
                            : surface.isFaceSturdy(level, path, Direction.UP),
                    "The leveled approach has a walkable surface at " + path.toShortString());
            helper.assertTrue(level.isEmptyBlock(path.above()) && level.isEmptyBlock(path.above(2)),
                    "The leveled approach has two clear blocks of headroom");
        }
        BlockPos outside = door.north(3);
        int outsideY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, outside.getX(), outside.getZ()) - 1;
        helper.assertTrue(Math.abs(outsideY - groundY) <= 1, "The prepared approach connects to the surrounding terrain");
    }

    private static void checkStandingPlayerPassesDugoutDoor(GameTestHelper helper, BlockPos door) {
        ServerLevel level = helper.getLevel();
        var lower = level.getBlockState(door);
        var upper = level.getBlockState(door.above());
        level.setBlock(door, lower.setValue(DoorBlock.OPEN, true), Block.UPDATE_CLIENTS);
        level.setBlock(door.above(), upper.setValue(DoorBlock.OPEN, true), Block.UPDATE_CLIENTS);
        for (int tenth = -6; tenth <= 5; tenth++) {
            helper.assertTrue(level.noCollision(EntityType.PLAYER.getDimensions().makeBoundingBox(
                            door.getX() + 0.5, door.getY(), door.getZ() + tenth / 10.0)),
                    "A standing player has a clear path from the landing under the open dugout door's lintel");
        }
        level.setBlock(door, lower, Block.UPDATE_CLIENTS);
        level.setBlock(door.above(), upper, Block.UPDATE_CLIENTS);
    }

    private static void checkLegacyDugoutRepair(GameTestHelper helper, BlockPos dugout) {
        ServerLevel level = helper.getLevel();
        BlockPos door = dugout.offset(2, 1, 0);
        BlockPos oldStair = door.north();
        BlockPos newStair = door.north(2);
        var stair = level.getBlockState(newStair);
        level.setBlock(oldStair, stair, Block.UPDATE_CLIENTS);
        set(level, newStair, Blocks.GRASS_BLOCK);
        IntroData data = new IntroData();
        data.protect(newStair);
        IntroStructures.repairLegacyDugoutEntrance(level, dugout, data);
        helper.assertTrue(level.getBlockState(oldStair) == stair && level.getBlockState(newStair).is(Blocks.GRASS_BLOCK),
                "Legacy repairs leave the protected approach alone");
        data.unprotect(newStair);
        IntroStructures.repairLegacyDugoutEntrance(level, dugout, data);
        helper.assertTrue(level.isEmptyBlock(oldStair) && level.getBlockState(newStair) == stair,
                "The old untouched approach receives a lower landing");
        checkStandingPlayerPassesDugoutDoor(helper, door);
    }

    private static void checkFoundation(GameTestHelper helper, BlockPos origin, int width, int depth, Block floor) {
        ServerLevel level = helper.getLevel();
        for (BlockPos pos : BlockPos.betweenClosed(origin, origin.offset(width - 1, 0, depth - 1))) {
            helper.assertTrue(level.getBlockState(pos).is(floor)
                            && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP),
                    "The complete floor sits on solid, leveled ground");
        }
    }

    private static void set(ServerLevel level, BlockPos pos, Block block) {
        level.setBlock(pos, block.defaultBlockState(), Block.UPDATE_CLIENTS);
    }
}
