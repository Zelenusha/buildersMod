package io.github.zelenusha.buildersmod.blueprint;

import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.BlueprintCheck;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Criterion;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Door;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Requirement;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Status;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.WindowDestination;
import io.github.zelenusha.buildersmod.blueprint.RectangularRoomBlueprint.Fixture;
import io.github.zelenusha.buildersmod.blueprint.RectangularRoomBlueprint.Passage;
import io.github.zelenusha.buildersmod.blueprint.RectangularRoomBlueprint.Pattern;
import io.github.zelenusha.buildersmod.blueprint.RectangularRoomBlueprint.Spec;
import io.github.zelenusha.buildersmod.blueprint.RectangularRoomBlueprint.Surface;
import io.github.zelenusha.buildersmod.blueprint.RectangularRoomBlueprint.Wall;
import io.github.zelenusha.buildersmod.blueprint.RectangularRoomBlueprint.Window;
import io.github.zelenusha.buildersmod.site.ConstructionSite;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("buildersmod")
@PrefixGameTestTemplate(false)
public class BlueprintGameTests {
    private static final BlockPos ANCHOR = new BlockPos(108, 72, -95);

    @GameTest(template = "empty")
    public static void laysOutTheSameRoomBehindEachConnectingDoor(GameTestHelper helper) {
        for (Direction outward : Direction.Plane.HORIZONTAL) {
            RoomBlueprint blueprint = SampleRoomBlueprint.at(ANCHOR, outward);

            checkEqual(helper, 7, blueprint.bounds().max().getX() - blueprint.bounds().min().getX() + 1);
            checkEqual(helper, 7, blueprint.bounds().max().getZ() - blueprint.bounds().min().getZ() + 1);
            checkEqual(helper, ANCHOR.getY() - 1, blueprint.bounds().min().getY());
            checkEqual(helper, ANCHOR.getY() + 4, blueprint.bounds().max().getY());
            checkEqual(helper, 5, blueprint.interior().max().getX() - blueprint.interior().min().getX() + 1);
            checkEqual(helper, 5, blueprint.interior().max().getZ() - blueprint.interior().min().getZ() + 1);
            checkEqual(helper, 4, blueprint.interior().max().getY() - blueprint.interior().min().getY() + 1);
            helper.assertTrue(!blueprint.bounds().contains(ANCHOR), "The connecting door is outside the new room");
            checkEqual(helper, 4, blueprint.doors().size());
            checkEqual(helper, new Door(ANCHOR.relative(outward), outward.getOpposite()), blueprint.doors().get(0));
            checkEqual(helper, new Door(ANCHOR.relative(outward, 7), outward), blueprint.doors().get(1));
            checkEqual(helper, 197, blueprint.requirements().size());
            checkEqual(helper, 49L, countRequirements(blueprint, Criterion.FLOOR));
            checkEqual(helper, 88L, countRequirements(blueprint, Criterion.WALLS));
            checkEqual(helper, 49L, countRequirements(blueprint, Criterion.CEILING));
            checkEqual(helper, 8L, countRequirements(blueprint, Criterion.DOORWAYS));
            checkEqual(helper, 2L, countRequirements(blueprint, Criterion.LIGHTING));
            checkEqual(helper, 1L, countRequirements(blueprint, Criterion.FUNCTIONAL_BLOCKS));
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void reportsEachWrongOrMissingCoordinateAndEachCriterionOnce(GameTestHelper helper) {
        RoomBlueprint blueprint = SampleRoomBlueprint.at(ANCHOR, Direction.NORTH);
        Map<BlockPos, BlockState> world = completedRoom(blueprint);
        BlockPos missingFloor = blueprint.bounds().min();
        BlockPos wrongFloor = missingFloor.east();
        BlockPos missingUpperDoor = blueprint.doors().get(0).lowerPosition().above();
        world.remove(missingFloor);
        world.put(wrongFloor, Blocks.DIRT.defaultBlockState());
        world.remove(missingUpperDoor);

        BlueprintCheck result = check(blueprint, world);

        helper.assertTrue(!result.complete(), "Three mismatches must prevent completion");
        checkEqual(helper, 3, result.mismatchCount());
        checkEqual(helper, Set.of(Criterion.FLOOR, Criterion.DOORWAYS), result.violatedCriteria());
        checkEqual(helper, 2, result.violatedCriteriaCount());
        checkEqual(helper, Status.MISSING, result.statuses().get(missingFloor));
        checkEqual(helper, Status.WRONG, result.statuses().get(wrongFloor));
        checkEqual(helper, Status.MISSING, result.statuses().get(missingUpperDoor));
        checkEqual(helper, Status.CORRECT, result.statuses().get(missingUpperDoor.below()));
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void acceptsCompleteOakDoorAlongPassageAxisRegardlessOfOpenState(GameTestHelper helper) {
        RoomBlueprint blueprint = SampleRoomBlueprint.at(ANCHOR, Direction.EAST);
        Map<BlockPos, BlockState> world = completedRoom(blueprint);
        Door door = blueprint.doors().get(0);
        BlockPos lower = door.lowerPosition();
        BlockPos upper = lower.above();
        BlockState open = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, door.outward().getOpposite())
                .setValue(DoorBlock.OPEN, true);
        world.put(lower, open);
        world.put(upper, open.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        helper.assertTrue(check(blueprint, world).complete(), "Open oak door aligned with the passage must count");

        BlockState perpendicular = open.setValue(DoorBlock.FACING, door.outward().getClockWise());
        world.put(lower, perpendicular);
        world.put(upper, perpendicular.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        checkEqual(helper, 2, check(blueprint, world).mismatchCount());

        BlockState spruce = Blocks.SPRUCE_DOOR.defaultBlockState().setValue(DoorBlock.FACING, door.outward());
        world.put(lower, spruce);
        world.put(upper, spruce.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        checkEqual(helper, 2, check(blueprint, world).mismatchCount());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void countsExactlyThreeCakesOnlyInsideTheRoom(GameTestHelper helper) {
        RoomBlueprint blueprint = SampleRoomBlueprint.at(ANCHOR, Direction.SOUTH);
        Map<BlockPos, BlockState> world = completedRoom(blueprint);
        BlockPos corner = blueprint.interior().min();
        BlockPos first = corner.east().south();
        BlockPos second = first.east();
        BlockPos third = second.east();
        BlockPos fourth = first.south();
        BlockState cake = Blocks.CAKE.defaultBlockState();
        world.put(first, cake);
        world.put(second, cake);
        world.put(ANCHOR, cake);
        checkEqual(helper, 2, check(blueprint, world).cakeCount());
        checkEqual(helper, 0, check(blueprint, world).bonusEmeralds());

        world.put(third, cake);
        helper.assertTrue(check(blueprint, world).complete(), "Bonus objects cannot prevent ordinary completion");
        checkEqual(helper, 3, check(blueprint, world).cakeCount());
        checkEqual(helper, 5, check(blueprint, world).bonusEmeralds());

        world.put(fourth, cake);
        checkEqual(helper, 4, check(blueprint, world).cakeCount());
        checkEqual(helper, 0, check(blueprint, world).bonusEmeralds());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void deterministicWeightedBlueprintSurvivesSnapshotRoundTrip(GameTestHelper helper) {
        var first = RoomGenerator.generate(RandomSource.create(6172L), 5, ANCHOR, Direction.WEST, block -> true)
                .orElseThrow();
        var second = RoomGenerator.generate(RandomSource.create(6172L), 5, ANCHOR, Direction.WEST, block -> true)
                .orElseThrow();
        checkEqual(helper, first.spec(), second.spec());
        checkEqual(helper, first.blueprint().requirements(), second.blueprint().requirements());
        RoomBlueprint snapshot = RoomBlueprint.decode(first.blueprint().encode());
        checkEqual(helper, first.blueprint().encode(), snapshot.encode());
        checkEqual(helper, first.blueprint().requirements(), snapshot.requirements());
        checkEqual(helper, first.blueprint().interiorVolumes(), snapshot.interiorVolumes());
        checkEqual(helper, first.blueprint().doors(), snapshot.doors());
        helper.assertTrue(check(snapshot, completedRoom(snapshot)).complete(), "Exact construction must pass after reload");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void filtersUnavailableMaterialsAndRejectsUnavailableSite(GameTestHelper helper) {
        var first = RoomGenerator.generate(RandomSource.create(17L), 1, ANCHOR, Direction.EAST,
                block -> block == Blocks.STONE || block == Blocks.OAK_DOOR || block == Blocks.TORCH)
                .orElseThrow();
        checkEqual(helper, 1, first.spec().heights().size());
        helper.assertTrue(first.spec().lights().stream().allMatch(fixture -> fixture.state().is(Blocks.TORCH)),
                "Level one light must be a torch");
        helper.assertTrue(first.spec().windows().isEmpty(), "Unavailable glass must exclude windows");
        helper.assertTrue(first.spec().furniture().isEmpty(), "Unavailable furniture must be excluded");
        helper.assertTrue(first.blueprint().bonus().isEmpty(), "Unavailable cake must exclude the optional bonus");
        helper.assertTrue(RoomGenerator.generate(RandomSource.create(17L), 1, ANCHOR, Direction.EAST,
                block -> true, generated -> false).isEmpty(), "Cannot publish an unsuitable site");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void reorientsFrozenMultiStoreyRoomWithoutChangingItsDesign(GameTestHelper helper) {
        Surface walls = new Surface(Blocks.BIRCH_PLANKS.defaultBlockState(), Pattern.CHECKERBOARD,
                Optional.of(Blocks.OAK_PLANKS.defaultBlockState()));
        Spec spec = new Spec(7, 9, List.of(4, 5), Surface.solid(Blocks.STONE), walls, Surface.solid(Blocks.OAK_PLANKS),
                Set.of(Wall.OPPOSITE), List.of(), List.of(new Fixture(0, new BlockPos(1, 1, 1),
                Blocks.TORCH.defaultBlockState())), List.of(), Optional.empty(), Passage.LADDER);
        BlockPos nextAnchor = new BlockPos(-31, 42, 56);
        RoomBlueprint old = RectangularRoomBlueprint.at(ANCHOR, Direction.SOUTH, spec);
        RoomBlueprint transformed = old.reorient(ANCHOR, Direction.SOUTH, nextAnchor, Direction.WEST);
        RoomBlueprint next = RectangularRoomBlueprint.at(nextAnchor, Direction.WEST, spec);
        checkEqual(helper, next.encode(), transformed.encode());
        checkEqual(helper, 2, next.interiorVolumes().size());
        BlockPos firstCeiling = next.interiorVolumes().get(0).min().above(4);
        helper.assertTrue(!next.containsInterior(firstCeiling), "An intermediate ceiling is outside the bonus volume");
        helper.assertTrue(next.requirements().values().stream().anyMatch(RoomBlueprint.Requirement::clearance),
                "A multi-storey blueprint requires a clear landing");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void preservesWindowOpeningAndDestinationAcrossSaveAndRotation(GameTestHelper helper) {
        Surface stone = Surface.solid(Blocks.STONE);
        Spec spec = new Spec(7, 9, List.of(4), stone, stone, stone, Set.of(Wall.OPPOSITE),
                List.of(new Window(0, Wall.LEFT, 2, 2, 5, 3, Blocks.GLASS_PANE)),
                List.of(new Fixture(0, new BlockPos(1, 1, 1), Blocks.TORCH.defaultBlockState())),
                List.of(), Optional.empty());
        RoomBlueprint original = RectangularRoomBlueprint.at(ANCHOR, Direction.SOUTH, spec);
        var opening = original.windows().get(0);
        checkEqual(helper, WindowDestination.UNSPECIFIED, opening.destination());
        checkEqual(helper, 5, opening.width());
        checkEqual(helper, 3, opening.height());
        checkEqual(helper, Direction.WEST, opening.outward());
        var classified = original.withWindows(List.of(opening.withDestination(WindowDestination.COMMON_HALL)));
        RoomBlueprint restored = RoomBlueprint.decode(classified.encode());
        checkEqual(helper, classified.windows(), restored.windows());

        BlockPos nextAnchor = ANCHOR.offset(10, 25, -20);
        RoomBlueprint rotated = restored.reorient(ANCHOR, Direction.SOUTH, nextAnchor, Direction.WEST);
        var rebuiltWindow = RectangularRoomBlueprint.at(nextAnchor, Direction.WEST, spec).windows().get(0);
        checkEqual(helper, List.of(rebuiltWindow.withDestination(WindowDestination.COMMON_HALL)), rotated.windows());
        checkEqual(helper, Direction.NORTH, rotated.windows().get(0).outward());

        var legacy = original.encode();
        legacy.putInt("version", 1);
        legacy.remove("windows");
        RoomBlueprint oldSave = RoomBlueprint.decode(legacy);
        checkEqual(helper, List.of(), oldSave.windows());
        checkEqual(helper, original.requirements(), oldSave.requirements());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void preservesAndChecksExplicitMaterialReplacement(GameTestHelper helper) {
        RoomBlueprint sample = SampleRoomBlueprint.at(ANCHOR, Direction.NORTH);
        BlockPos position = sample.bounds().min();
        var requirements = new HashMap<>(sample.requirements());
        requirements.put(position, new Requirement(Blocks.DIRT.defaultBlockState(), Criterion.FLOOR,
                List.of(Blocks.GRASS_BLOCK.defaultBlockState())));
        RoomBlueprint blueprint = RoomBlueprint.of(sample.bounds(), sample.interiorVolumes(), requirements,
                sample.doors(), sample.bonus());
        RoomBlueprint restored = RoomBlueprint.decode(blueprint.encode()).reorient(ANCHOR, Direction.NORTH,
                ANCHOR, Direction.EAST);
        Map<BlockPos, BlockState> blocks = completedRoom(restored);
        BlockPos restoredPosition = restored.requirements().entrySet().stream()
                .filter(entry -> entry.getValue().expectedState().is(Blocks.DIRT))
                .map(Map.Entry::getKey).findFirst().orElseThrow();
        blocks.put(restoredPosition, Blocks.GRASS_BLOCK.defaultBlockState());
        helper.assertTrue(check(restored, blocks).complete(), "An explicit replacement must count after reload and rotation");
        blocks.put(restoredPosition, Blocks.SAND.defaultBlockState());
        checkEqual(helper, Status.WRONG, check(restored, blocks).statuses().get(restoredPosition));
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void checksEachStairAndTheClearPathBetweenStoreys(GameTestHelper helper) {
        Spec spec = new Spec(7, 9, List.of(4, 5), Surface.solid(Blocks.STONE), Surface.solid(Blocks.BIRCH_PLANKS),
                Surface.solid(Blocks.OAK_PLANKS), Set.of(Wall.OPPOSITE), List.of(),
                List.of(new Fixture(0, new BlockPos(1, 1, 1), Blocks.TORCH.defaultBlockState())), List.of(),
                Optional.empty(), Passage.STAIRS);
        RoomBlueprint blueprint = RectangularRoomBlueprint.at(ANCHOR, Direction.NORTH, spec);
        checkEqual(helper, 6L, countRequirements(blueprint, Criterion.STAIRWAYS));
        helper.assertTrue(blueprint.requirements().keySet().stream().allMatch(blueprint.bounds()::contains),
                "Stairs and their landings must remain in the room bounds");
        Map<BlockPos, BlockState> blocks = completedRoom(blueprint);
        helper.assertTrue(check(blueprint, blocks).complete(), "An exact stairs blueprint must pass");
        BlockPos clearance = blueprint.requirements().entrySet().stream()
                .filter(entry -> entry.getValue().clearance() && entry.getKey().getY() > ANCHOR.getY() + 1)
                .map(Map.Entry::getKey).findFirst().orElseThrow();
        blocks.put(clearance, Blocks.DIRT.defaultBlockState());
        BlueprintCheck obstructed = check(blueprint, blocks);
        checkEqual(helper, 1, obstructed.mismatchCount());
        checkEqual(helper, Set.of(Criterion.CLEARANCE), obstructed.violatedCriteria());
        helper.succeed();
    }

    @GameTest(template = "sample_floor")
    public static void checksCompletedSampleAgainstActualServerBlocks(GameTestHelper helper) {
        BlockPos anchor = helper.absolutePos(new BlockPos(4, 2, 0));
        RoomBlueprint room = SampleRoomBlueprint.at(anchor, Direction.SOUTH);
        ServerLevel world = helper.getLevel();
        for (Criterion criterion : List.of(Criterion.FLOOR, Criterion.WALLS, Criterion.CEILING,
                Criterion.DOORWAYS, Criterion.LIGHTING, Criterion.FUNCTIONAL_BLOCKS)) {
            room.requirements().forEach((position, requirement) -> {
                if (requirement.criterion() == criterion) {
                    world.setBlock(position, requirement.expectedState(), Block.UPDATE_ALL);
                }
            });
        }
        BlockPos cake = room.interior().min().east().south();
        world.setBlock(cake, Blocks.CAKE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(cake.east(), Blocks.CAKE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(cake.south(), Blocks.CAKE.defaultBlockState(), Block.UPDATE_ALL);

        BlueprintCheck complete = room.check(world);
        checkEqual(helper, 0, complete.mismatchCount());
        checkEqual(helper, 3, complete.cakeCount());
        checkEqual(helper, 5, complete.bonusEmeralds());

        BlockPos damagedCeiling = room.interior().max().above();
        world.setBlock(damagedCeiling, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        BlueprintCheck incomplete = room.check(world);
        checkEqual(helper, 1, incomplete.mismatchCount());
        checkEqual(helper, Set.of(Criterion.CEILING), incomplete.violatedCriteria());
        checkEqual(helper, Status.MISSING, incomplete.statuses().get(damagedCeiling));
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1000)
    public static void generatesEightDecksAndTheThreeStarterDoors(GameTestHelper helper) {
        ServerLevel site = helper.getLevel().getServer().getLevel(ConstructionSite.SITE_LEVEL);
        helper.assertTrue(site != null, "Construction dimension must load");
        for (int index = 0; index < 8; index++) {
            BlockPos deck = new BlockPos(0, 64 + index * 25, 0);
            checkEqual(helper, Blocks.STONE, site.getBlockState(deck).getBlock());
            helper.assertTrue(site.getBlockState(deck.above()).isAir(), "Every deck starts with empty room above it");
        }
        checkEqual(helper, Blocks.STONE_BRICKS, site.getBlockState(new BlockPos(0, 69, 0)).getBlock());
        for (ConstructionSite.SiteDoor door : ConstructionSite.STARTER_DOORS) {
            BlockState lower = site.getBlockState(door.lower());
            BlockState upper = site.getBlockState(door.lower().above());
            checkEqual(helper, Blocks.OAK_DOOR, lower.getBlock());
            checkEqual(helper, Blocks.OAK_DOOR, upper.getBlock());
            checkEqual(helper, DoubleBlockHalf.LOWER, lower.getValue(DoorBlock.HALF));
            checkEqual(helper, DoubleBlockHalf.UPPER, upper.getValue(DoorBlock.HALF));
            checkEqual(helper, door.outward(), lower.getValue(DoorBlock.FACING));
        }
        helper.succeed();
    }

    private static void checkEqual(GameTestHelper helper, Object expected, Object actual) {
        helper.assertTrue(expected.equals(actual), "Expected " + expected + " but got " + actual);
    }

    private static long countRequirements(RoomBlueprint blueprint, Criterion criterion) {
        return blueprint.requirements().values().stream()
                .filter(requirement -> requirement.criterion() == criterion)
                .count();
    }

    private static Map<BlockPos, BlockState> completedRoom(RoomBlueprint blueprint) {
        Map<BlockPos, BlockState> world = new HashMap<>();
        blueprint.requirements().forEach((position, requirement) -> world.put(position, requirement.expectedState()));
        return world;
    }

    private static BlueprintCheck check(RoomBlueprint blueprint, Map<BlockPos, BlockState> world) {
        return blueprint.check(position -> world.getOrDefault(position, Blocks.AIR.defaultBlockState()));
    }
}
