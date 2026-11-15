package io.github.zelenusha.buildersmod.contract;

import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Criterion;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Status;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Window;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.WindowDestination;
import io.github.zelenusha.buildersmod.blueprint.SampleRoomBlueprint;
import io.github.zelenusha.buildersmod.site.ConstructionSite;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("buildersmod")
@PrefixGameTestTemplate(false)
public final class OfferPlacementGameTests {
    private static final int FIXTURE_UPDATE = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    @GameTest(template = "empty")
    public static void checksThatGlazingOpensOntoSpace(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos window = helper.absolutePos(new BlockPos(5, 28, 0));
        BlockPos outside = window.east();
        BlockPos beyond = outside.east();
        var bounds = new RoomBlueprint.Bounds(window.offset(-4, -2, -2), window.offset(0, 2, 2));
        var inside = new RoomBlueprint.Bounds(window.offset(-3, -1, -1), window.offset(-1, 1, 1));
        var blueprint = RoomBlueprint.of(bounds, List.of(inside), Map.of(window, new RoomBlueprint.Requirement(
                Blocks.GLASS.defaultBlockState(), Criterion.WINDOWS)),
                List.of(new RoomBlueprint.Door(window.west(4), Direction.WEST)), Optional.empty());
        BlockState formerOutside = level.getBlockState(outside);
        BlockState formerBeyond = level.getBlockState(beyond);
        try {
            level.setBlock(outside, Blocks.STONE.defaultBlockState(), FIXTURE_UPDATE);
            helper.assertTrue(!OfferFactory.windowsHaveSpace(level, blueprint), "A wall against the glass rejects the design");
            level.setBlock(outside, Blocks.AIR.defaultBlockState(), FIXTURE_UPDATE);
            helper.assertTrue(OfferFactory.windowsHaveSpace(level, blueprint), "The unobstructed side opens onto the worksite");
            level.setBlock(outside, Blocks.GLASS.defaultBlockState(), FIXTURE_UPDATE);
            level.setBlock(beyond, Blocks.STONE.defaultBlockState(), FIXTURE_UPDATE);
            helper.assertTrue(!OfferFactory.windowsHaveSpace(level, blueprint), "Two glass faces cannot hide an immediate obstruction");
            level.setBlock(beyond, Blocks.AIR.defaultBlockState(), FIXTURE_UPDATE);
            helper.assertTrue(OfferFactory.windowsHaveSpace(level, blueprint), "Aligned glass can open into the adjacent space");
        } finally {
            level.setBlock(outside, formerOutside, FIXTURE_UPDATE);
            level.setBlock(beyond, formerBeyond, FIXTURE_UPDATE);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void pricesWindowsByTheVisibleSpaceBeyondEachOpening(GameTestHelper helper) {
        ServerLevel site = helper.getLevel().getServer().getLevel(ConstructionSite.SITE_LEVEL);
        int y = ConstructionSite.START_FLOOR_Y + 2;
        BlockPos small = new BlockPos(9, y, 0);
        var street = new Window(new RoomBlueprint.Bounds(small, small), Direction.EAST, WindowDestination.STREET);
        var hall = new Window(new RoomBlueprint.Bounds(new BlockPos(5, y, -2), new BlockPos(5, y + 2, 2)),
                Direction.WEST, WindowDestination.COMMON_HALL);
        var area = new RoomBlueprint.Bounds(new BlockPos(3, y - 1, -2), new BlockPos(11, y + 2, 2));
        var bounds = new RoomBlueprint.Bounds(new BlockPos(5, y - 1, -3), new BlockPos(9, y + 3, 3));
        var interior = new RoomBlueprint.Bounds(new BlockPos(6, y, -2), new BlockPos(8, y + 2, 2));
        var glass = new RoomBlueprint.Requirement(Blocks.GLASS.defaultBlockState(), Criterion.WINDOWS);
        Map<BlockPos, RoomBlueprint.Requirement> requirements = new HashMap<>();
        requirements.put(small, glass);
        hall.opening().positions().forEach(pane -> requirements.put(pane, glass));
        var candidate = RoomBlueprint.of(bounds, List.of(interior), requirements,
                List.of(new RoomBlueprint.Door(new BlockPos(7, y, -3), Direction.NORTH)), Optional.empty(), List.of(street, hall));
        var account = new ContractData.Account();
        ContractEvents.withLoadedArea(site, area, () -> {
            Map<BlockPos, BlockState> original = new HashMap<>();
            area.positions().forEach(pos -> original.put(pos, site.getBlockState(pos)));
            try {
                for (BlockPos pane : hall.opening().positions()) {
                    site.setBlock(pane.west(), Blocks.GLASS.defaultBlockState(), FIXTURE_UPDATE);
                    site.setBlock(pane.west(2), Blocks.AIR.defaultBlockState(), FIXTURE_UPDATE);
                }
                site.setBlock(small.east(), Blocks.AIR.defaultBlockState(), FIXTURE_UPDATE);
                helper.assertTrue(OfferFactory.windowDestination(site, account, street, List.of()) == WindowDestination.STREET
                                && OfferFactory.windowDestination(site, account, hall, List.of()) == WindowDestination.COMMON_HALL,
                        "An open street window and aligned glazing into the starter hall classify differently");
                helper.assertTrue(ContractBalance.windowWage(candidate) == 8, "A small street and panoramic hall window add 1 + 7");

                var neighbor = small.east(2);
                var adjacentBounds = new RoomBlueprint.Bounds(small.east().below(), neighbor.above());
                var adjacent = RoomBlueprint.of(adjacentBounds, List.of(new RoomBlueprint.Bounds(neighbor, neighbor)),
                        Map.of(small.east(), glass), List.of(new RoomBlueprint.Door(neighbor, Direction.EAST)), Optional.empty());
                account.rooms.add(order(neighbor, Direction.EAST, adjacent));
                site.setBlock(small.east(), Blocks.GLASS.defaultBlockState(), FIXTURE_UPDATE);
                site.setBlock(neighbor, Blocks.AIR.defaultBlockState(), FIXTURE_UPDATE);
                var actual = OfferFactory.windowDestination(site, account, street, List.of(adjacentBounds));
                helper.assertTrue(actual == WindowDestination.INTERIOR
                                && ContractBalance.windowWage(candidate.withWindows(List.of(street.withDestination(actual), hall))) == 9,
                        "An ordinary room behind the glass changes the small window's rate to two");
                site.setBlock(hall.opening().max().west(2), Blocks.STONE.defaultBlockState(), FIXTURE_UPDATE);
                helper.assertTrue(OfferFactory.windowDestination(site, account, hall, List.of()) == null,
                        "One covered panoramic tile invalidates the entire opening");
            } finally {
                original.forEach((pos, state) -> site.setBlock(pos, state, FIXTURE_UPDATE));
            }
            return null;
        });
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void marksRemovableObstaclesAndRejectsContainersAndBedrock(GameTestHelper helper) {
        ServerLevel site = helper.getLevel().getServer().getLevel(ConstructionSite.SITE_LEVEL);
        BlockPos anchor = new BlockPos(520, ConstructionSite.START_FLOOR_Y + 1, 525);
        RoomBlueprint room = SampleRoomBlueprint.at(anchor, Direction.NORTH);
        BlockPos sourceAnchor = anchor.south(7);
        RoomBlueprint source = SampleRoomBlueprint.at(sourceAnchor, Direction.NORTH);
        var account = new ContractData.Account();
        account.rooms.add(order(sourceAnchor, Direction.NORTH, source));
        for (var starter : ConstructionSite.STARTER_DOORS) {
            var occupied = SampleRoomBlueprint.at(starter.lower(), starter.outward());
            account.failed.add(new DemolitionWork(order(starter.lower(), starter.outward(), occupied)));
        }

        BlockPos sign = new BlockPos(521, anchor.getY(), 522);
        BlockPos column = new BlockPos(519, anchor.getY() + 1, 520);
        BlockPos wall = room.bounds().min().above();
        RoomBlueprint.Bounds fixture = new RoomBlueprint.Bounds(room.bounds().min(),
                new BlockPos(room.bounds().max().getX(), room.bounds().max().getY(), anchor.getZ()));
        ContractEvents.withLoadedArea(site, fixture, () -> {
            Map<BlockPos, BlockState> original = new HashMap<>();
            room.bounds().positions().forEach(pos -> original.put(pos, site.getBlockState(pos)));
            original.put(anchor, site.getBlockState(anchor));
            original.put(anchor.above(), site.getBlockState(anchor.above()));
            try {
                for (BlockPos doorHalf : new BlockPos[] {anchor, anchor.above()}) {
                    site.setBlock(doorHalf, source.requirements().get(doorHalf).expectedState(), FIXTURE_UPDATE);
                }
                helper.assertTrue(ConstructionSite.isOriginalColumnPosition(column)
                                && site.getBlockState(column).is(Blocks.STONE_BRICKS),
                        "The test overlaps a generated frame column");
                site.setBlock(sign, Blocks.OAK_SIGN.defaultBlockState(), FIXTURE_UPDATE);
                site.setBlock(wall, Blocks.BIRCH_PLANKS.defaultBlockState(), FIXTURE_UPDATE);

                var offer = order(anchor, Direction.NORTH, room);
                var bound = OfferFactory.bind(site, account, offer);
                helper.assertTrue(bound != null && bound.anchor().equals(anchor), "The removable obstacles allow the only free exit");
                var placed = bound.blueprint();
                helper.assertTrue(placed.requirements().get(column).clearance() && placed.requirements().get(sign).clearance(),
                        "An interior column and non-container sign mark clearance positions");
                helper.assertTrue(placed.requirements().get(wall).criterion() == Criterion.WALLS,
                        "A block already in the wall keeps the blueprint's wall criterion");
                helper.assertTrue(placed.checkPosition(site, column) == Status.WRONG && placed.checkPosition(site, sign) == Status.WRONG
                                && placed.checkPosition(site, wall) == Status.WRONG,
                        "Placement preserves the obstacles for the player to clear or replace");

                site.setBlock(sign, Blocks.CHEST.defaultBlockState(), FIXTURE_UPDATE);
                helper.assertTrue(site.getBlockEntity(sign) instanceof ChestBlockEntity chest && chest.isEmpty(),
                        "The blocked scenario has a real, empty container");
                helper.assertTrue(OfferFactory.bind(site, account, offer) == null, "Even an empty chest rejects the candidate");
                site.setBlock(sign, Blocks.BEDROCK.defaultBlockState(), FIXTURE_UPDATE);
                helper.assertTrue(OfferFactory.bind(site, account, offer) == null, "An unbreakable obstruction rejects the candidate");

                placed.requirements().forEach((pos, requirement) -> site.setBlock(pos, requirement.expectedState(), FIXTURE_UPDATE));
                helper.assertTrue(placed.check(site).complete(), "The same room passes after manual clearance and installation");
            } finally {
                original.forEach((pos, state) -> site.setBlock(pos, state, FIXTURE_UPDATE));
            }
            return null;
        });
        helper.succeed();
    }

    private static ContractData.Accepted order(BlockPos anchor, Direction outward, RoomBlueprint blueprint) {
        return new ContractData.Accepted(anchor, outward, ContractBalance.Difficulty.ONE, 0, false,
                blueprint, 5, ContractData.Kind.ROOM, 0, 0, Map.of());
    }
}
