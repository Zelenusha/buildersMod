package io.github.zelenusha.buildersmod.site;

import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Criterion;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Requirement;
import io.github.zelenusha.buildersmod.site.SiteStairBlueprint.Layout;
import io.github.zelenusha.buildersmod.site.SiteStairBlueprint.Materials;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("buildersmod")
@PrefixGameTestTemplate(false)
public class SiteStairGameTests {
    private static final int LOWER = 64;
    private static final int UPPER = 89;

    private static Materials testMaterials() {
        return new Materials(Blocks.COBBLESTONE_STAIRS.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(),
                Blocks.STONE.defaultBlockState(), Blocks.STONE_BRICKS.defaultBlockState(), Blocks.OAK_PLANKS.defaultBlockState());
    }

    @GameTest(template = "empty")
    public static void keepsEveryFlightAndLandingClearAndProtectsBothDirectionsOfTravel(GameTestHelper helper) {
        Materials materials = testMaterials();
        for (Layout layout : List.of(Layout.NORTH_FIRST, Layout.SOUTH_FIRST)) {
            RoomBlueprint blueprint = SiteStairBlueprint.create(LOWER, layout, materials);
            List<BlockPos> route = stairRoute(blueprint);
            checkEqual(helper, 25L, route.stream().filter(pos -> isStair(blueprint, pos)).count());
            checkEqual(helper, LOWER, route.get(0).getY());
            checkEqual(helper, UPPER, route.get(route.size() - 1).getY());
            checkEqual(helper, 0, route.get(0).getX());
            checkEqual(helper, 2, Math.abs(route.get(route.size() - 1).getZ()));
            for (int index = 0; index < route.size(); index++) {
                BlockPos pos = route.get(index);
                helper.assertTrue(blueprint.requirements().get(pos.above()).clearance(), "First headroom block must be clear");
                helper.assertTrue(blueprint.requirements().get(pos.above(2)).clearance(), "Second headroom block must be clear");
                if (index > 0) {
                    BlockPos previous = route.get(index - 1);
                    checkEqual(helper, 1, Math.abs(pos.getX() - previous.getX()) + Math.abs(pos.getZ() - previous.getZ()));
                    checkEqual(helper, isStair(blueprint, pos) ? 1 : 0, pos.getY() - previous.getY());
                }
            }
            helper.assertTrue(blueprint.requirements().keySet().stream().noneMatch(ConstructionSite::isReservedSiteBlock),
                    "Stairs overlap the arrival or portal");
            helper.assertTrue(ConstructionSite.STARTER_DOORS.stream().noneMatch(door ->
                    blueprint.requirements().containsKey(door.lower()) || blueprint.requirements().containsKey(door.lower().above())),
                    "Stairs overlap a lower room door");
            checkEqual(helper, List.of(new BlockPos(0, UPPER + 1, -4), new BlockPos(4, UPPER + 1, 0),
                    new BlockPos(0, UPPER + 1, 4)), blueprint.doors().stream().map(RoomBlueprint.Door::lowerPosition).toList());
            checkEqual(helper, new BlockPos(-3, UPPER + 1, -3), blueprint.interior().min());
            checkEqual(helper, new BlockPos(3, UPPER + 4, 3), blueprint.interior().max());
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void checksShaftCutoutsAndMissingStepsAfterPersistence(GameTestHelper helper) {
        Materials materials = testMaterials();
        RoomBlueprint blueprint = RoomBlueprint.decode(SiteStairBlueprint.create(LOWER, Layout.NORTH_FIRST, materials).encode());
        Map<BlockPos, BlockState> world = new HashMap<>();
        blueprint.requirements().forEach((pos, requirement) -> world.put(pos, requirement.expectedState()));
        helper.assertTrue(blueprint.check(world::get).complete(), "The completed blueprint must pass after a round trip through NBT");

        BlockPos requiredStep = new BlockPos(-3, LOWER + 1, 2);
        BlockPos shaft = new BlockPos(1, UPPER, 0);
        checkEqual(helper, materials.roomWalls(), world.get(new BlockPos(-4, UPPER + 2, 0)));
        checkEqual(helper, materials.roomCeiling(), world.get(new BlockPos(0, UPPER + 5, 0)));
        helper.assertTrue(world.get(requiredStep).is(Blocks.COBBLESTONE_STAIRS), "The first tread must use the staircase material");
        helper.assertTrue(world.get(shaft).isAir(), "The shaft must pass through the upper deck");
        world.put(shaft, Blocks.STONE.defaultBlockState());
        world.put(requiredStep, Blocks.AIR.defaultBlockState());
        RoomBlueprint.BlueprintCheck check = blueprint.check(world::get);
        checkEqual(helper, 2, check.mismatchCount());
        checkEqual(helper, Set.of(Criterion.STAIRWAYS, Criterion.CLEARANCE), check.violatedCriteria());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void triesMirroredRouteWhenTheStraightClimbAndFirstFlightAreObstructed(GameTestHelper helper) {
        Materials materials = testMaterials();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState lowerCeiling = Blocks.STONE_BRICKS.defaultBlockState();
        BlockPos blockedPreferred = new BlockPos(-2, LOWER + 1, 3);
        BlockPos blockedShaft = new BlockPos(-3, LOWER + 6, 0);
        RoomBlueprint alternative = SiteStairBlueprint.createFitting(LOWER, materials, stone, lowerCeiling, pos -> {
            if (pos.equals(blockedPreferred) || pos.equals(blockedShaft)) {
                return Blocks.CHEST.defaultBlockState();
            }
            if (pos.getY() == LOWER || pos.getY() == UPPER) {
                return stone;
            }
            return pos.getY() == LOWER + 5 ? lowerCeiling : Blocks.AIR.defaultBlockState();
        }).orElseThrow();

        Requirement firstSouthboundStep = alternative.requirements().get(new BlockPos(-3, LOWER + 1, -2));
        helper.assertTrue(firstSouthboundStep != null, "The alternate layout starts toward the south");
        checkEqual(helper, Direction.SOUTH, firstSouthboundStep.expectedState().getValue(StairBlock.FACING));
        helper.assertTrue(!alternative.requirements().containsKey(blockedPreferred), "The alternate route must avoid the chest");
        helper.assertTrue(!alternative.requirements().containsKey(blockedShaft), "The alternate route avoids the blocked straight shaft");
        helper.assertTrue(SiteStairBlueprint.createFitting(239, materials, stone, lowerCeiling,
                pos -> Blocks.AIR.defaultBlockState()).isEmpty(), "The eighth floor cannot offer another staircase");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void straightClimbHasSurvivingRungsAndClearLandingsOnTheConstructionSite(GameTestHelper helper) {
        ServerLevel site = helper.getLevel().getServer().getLevel(ConstructionSite.SITE_LEVEL);
        helper.assertTrue(site != null, "The test needs the construction dimension");
        for (int x : new int[] {-4, 4}) {
            for (int z : new int[] {-4, 4}) {
                site.getChunkAt(new BlockPos(x, LOWER, z));
            }
        }
        Materials materials = testMaterials();
        RoomBlueprint plan = SiteStairBlueprint.createFitting(LOWER, materials, Blocks.STONE.defaultBlockState(),
                Blocks.STONE_BRICKS.defaultBlockState(), site::getBlockState).orElseThrow();
        List<BlockPos> rungs = plan.requirements().entrySet().stream().filter(entry -> entry.getValue().expectedState().is(Blocks.LADDER))
                .map(Map.Entry::getKey).toList();
        checkEqual(helper, 27, rungs.size());
        checkEqual(helper, new BlockPos(-3, LOWER + 1, 0), rungs.get(0));
        checkEqual(helper, new BlockPos(-3, UPPER + 2, 0), rungs.get(rungs.size() - 1));
        helper.assertTrue(plan.requirements().get(new BlockPos(-3, UPPER, -1)).clearance(),
                "The upper deck exposes an explicit cutout next to the ladder");

        Map<BlockPos, BlockState> previous = new HashMap<>();
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        try {
            plan.requirements().forEach((pos, requirement) -> {
                previous.put(pos, site.getBlockState(pos));
                site.setBlock(pos, requirement.expectedState(), flags);
            });
            for (BlockPos rung : rungs) {
                BlockState placed = site.getBlockState(rung);
                helper.assertTrue(placed.getValue(LadderBlock.FACING) == Direction.EAST && placed.canSurvive(site, rung),
                        "Every rung is usable on its real western support");
            }
            for (int floor : new int[] {LOWER, UPPER}) {
                BlockPos landing = new BlockPos(-2, floor, 0);
                helper.assertTrue(site.isEmptyBlock(landing.above()) && site.isEmptyBlock(landing.above(2))
                        && site.getBlockState(landing).isFaceSturdy(site, landing, Direction.UP),
                        "A clear, supported east landing connects to the starter room at each end");
            }
            helper.assertTrue(plan.check(site).complete(), "The physically supported straight route satisfies the contract");
            site.setBlock(rungs.get(7), Blocks.AIR.defaultBlockState(), flags);
            helper.assertTrue(!plan.check(site).complete(), "A missing rung breaks contract readiness");
        } finally {
            previous.forEach((pos, block) -> site.setBlock(pos, block, flags));
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void completedApproachAllowsBuildingAgainOnTheNextDeck(GameTestHelper helper) {
        Materials materials = testMaterials();
        RoomBlueprint first = SiteStairBlueprint.create(LOWER, Layout.NORTH_FIRST, materials);
        RoomBlueprint second = SiteStairBlueprint.createFitting(UPPER, materials, materials.roomFloor(),
                materials.roomCeiling(), pos -> {
                    Requirement existing = first.requirements().get(pos);
                    if (existing != null) {
                        return existing.expectedState();
                    }
                    return pos.getY() == UPPER + 25 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
                }).orElseThrow();
        checkEqual(helper, UPPER, second.bounds().min().getY());
        checkEqual(helper, UPPER + 30, second.bounds().max().getY());
        helper.succeed();
    }

    private static void checkEqual(GameTestHelper helper, Object expected, Object actual) {
        helper.assertTrue(expected.equals(actual), "Expected " + expected + " but got " + actual);
    }

    private static List<BlockPos> stairRoute(RoomBlueprint blueprint) {
        List<BlockPos> route = new ArrayList<>();
        blueprint.requirements().forEach((pos, requirement) -> {
            if (requirement.criterion() == Criterion.STAIRWAYS) {
                route.add(pos);
            }
        });
        return route;
    }

    private static boolean isStair(RoomBlueprint blueprint, BlockPos pos) {
        return blueprint.requirements().get(pos).expectedState().getBlock() instanceof StairBlock;
    }
}
