package io.github.zelenusha.buildersmod.contract;

import io.github.zelenusha.buildersmod.blueprint.RectangularRoomBlueprint;
import io.github.zelenusha.buildersmod.blueprint.RectangularRoomBlueprint.Fixture;
import io.github.zelenusha.buildersmod.blueprint.RectangularRoomBlueprint.Outlook;
import io.github.zelenusha.buildersmod.blueprint.RectangularRoomBlueprint.Spec;
import io.github.zelenusha.buildersmod.blueprint.RectangularRoomBlueprint.Surface;
import io.github.zelenusha.buildersmod.blueprint.RectangularRoomBlueprint.Wall;
import io.github.zelenusha.buildersmod.blueprint.RectangularRoomBlueprint.Window;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Bounds;
import io.github.zelenusha.buildersmod.blueprint.RoomGenerator;
import io.github.zelenusha.buildersmod.blueprint.RoomGenerator.Category;
import io.github.zelenusha.buildersmod.blueprint.RoomGenerator.GeneratedRoom;
import io.github.zelenusha.buildersmod.blueprint.RoomGenerator.SizeCategory;
import io.github.zelenusha.buildersmod.blueprint.RoomGenerator.Style;
import io.github.zelenusha.buildersmod.site.ConstructionSite;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Generator rules added for FR-65, FR-68, FR-88 and FR-89. */
@GameTestHolder("buildersmod")
@PrefixGameTestTemplate(false)
public class GeneratorRulesGameTests {
    private static final BlockPos ANCHOR = new BlockPos(300, ConstructionSite.START_FLOOR_Y + 1, 300);

    @GameTest(template = "empty")
    public static void compactRoomIsOfferedWhenWeightedDesignsDoNotFit(GameTestHelper helper) {
        for (long seed = 0; seed < 20; seed++) {
            // Only the smallest footprint of the level fits behind this door.
            Optional<GeneratedRoom> tight = RoomGenerator.generate(RandomSource.create(seed), 4, ANCHOR, Direction.NORTH,
                    block -> true, room -> room.spec().width() <= 5 && room.spec().depth() <= 5);
            helper.assertTrue(tight.isPresent(), "A 5×5 plot always receives a level 4 offer");
            Optional<GeneratedRoom> noTiny = RoomGenerator.generate(RandomSource.create(seed), 5, ANCHOR, Direction.NORTH,
                    block -> true, room -> room.spec().width() <= 7 && room.spec().depth() <= 7);
            helper.assertTrue(noTiny.isPresent() && noTiny.orElseThrow().sizeCategory() == SizeCategory.SMALL,
                    "Level 5 has no tiny rooms, so its compact room is the small one");
            Optional<GeneratedRoom> none = RoomGenerator.generate(RandomSource.create(seed), 1, ANCHOR, Direction.NORTH,
                    block -> true, room -> false);
            helper.assertTrue(none.isEmpty(), "A plot that fits nothing rejects the compact room as well");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void categoryAndStyleAreDrawnIndependentlyAndNameTheRoom(GameTestHelper helper) {
        Map<Category, Set<Style>> styles = new EnumMap<>(Category.class);
        Set<String> titles = new HashSet<>();
        for (long seed = 0; seed < 300; seed++) {
            RoomGenerator.generate(RandomSource.create(seed), 3, ANCHOR, Direction.NORTH, block -> true).ifPresent(room -> {
                styles.computeIfAbsent(room.category(), ignored -> EnumSet.noneOf(Style.class)).add(room.style());
                titles.add(room.title());
                helper.assertTrue(room.title().contains(" · ") && room.conditions().get(0).startsWith("Внутри "
                        + room.spec().width() + "×" + room.spec().depth()), "The card names the room and states its size");
                helper.assertTrue(room.blueprint().check(pos -> Blocks.AIR.defaultBlockState()).mismatchCount() > 0,
                        "Every combination still produces a checkable blueprint");
            });
        }
        helper.assertTrue(styles.keySet().size() == Category.values().length, "Every category is generated");
        helper.assertTrue(styles.values().stream().allMatch(set -> set.size() == Style.values().length),
                "Each category appears in every style");
        helper.assertTrue(titles.size() > 20, "Titles vary with purpose, furniture and style");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void windowSurchargeAddsSizeAndDirectionRates(GameTestHelper helper) {
        Window small = new Window(0, Wall.LEFT, 4, 2, 1, 1, Blocks.GLASS);
        Window panoramic = new Window(0, Wall.RIGHT, 3, 2, 5, 3, Blocks.GLASS, Outlook.HALL);
        int plain = wage(List.of());
        helper.assertTrue(wage(List.of(small)) - plain == 1, "A small window to the street adds 0 + 1");
        helper.assertTrue(wage(List.of(panoramic)) - plain == 7, "A panoramic window to the common hall adds 4 + 3");
        helper.assertTrue(wage(List.of(small, panoramic)) - plain == 8, "Both windows add 8");
        helper.assertTrue(wage(List.of(new Window(0, Wall.LEFT, 4, 2, 2, 2, Blocks.GLASS, Outlook.INTERIOR))) - plain == 3,
                "A regular window into another room adds 1 + 2");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void windowsMustLookAtOpenSpaceNowAndLater(GameTestHelper helper) {
        ServerLevel site = helper.getLevel().getServer().getLevel(ConstructionSite.SITE_LEVEL);
        RoomBlueprint room = RectangularRoomBlueprint.at(ANCHOR, Direction.NORTH, spec(List.of(
                new Window(0, Wall.LEFT, 4, 2, 2, 2, Blocks.GLASS))));
        List<BlockPos> outlooks = OfferFactory.windowOutlooks(room);
        helper.assertTrue(outlooks.size() == 4 && outlooks.stream().noneMatch(room.bounds()::contains),
                "Four panes look at four positions just outside the wall");
        Bounds area = new Bounds(room.bounds().min().offset(-2, 0, -2), room.bounds().max().offset(2, 0, 2));
        ContractEvents.withLoadedArea(site, area, () -> {
            helper.assertTrue(OfferFactory.scan(site, room, List.of(), 1) != null, "An open deck accepts the room");
            BlockPos view = outlooks.get(0);
            helper.assertTrue(OfferFactory.scan(site, room, List.of(new Bounds(view, view.above(3))), 1) == null,
                    "A window that would face another room rejects the placement");
            site.setBlock(view, Blocks.STONE_BRICKS.defaultBlockState(), Block.UPDATE_CLIENTS);
            try {
                helper.assertTrue(OfferFactory.scan(site, room, List.of(), 1) == null,
                        "A block behind the pane rejects the placement");
            } finally {
                site.setBlock(view, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            return null;
        });

        ContractData data = new ContractData();
        UUID owner = UUID.randomUUID();
        var account = data.account(owner);
        account.rooms.add(new ContractData.Accepted(ANCHOR, Direction.NORTH, ContractBalance.Difficulty.ONE, 0, false, room,
                6, ContractData.Kind.ROOM, 0, 0, Map.of(), new ContractData.Brief("Кухня · каменный стиль",
                List.of("Внутри 9×9, квадратная"))));
        List<Bounds> occupied = OfferFactory.occupied(account);
        helper.assertTrue(outlooks.stream().allMatch(pos -> occupied.stream().anyMatch(bounds -> bounds.contains(pos))),
                "A finished room keeps the view from its windows reserved");
        var reloaded = ContractData.load(data.save(new CompoundTag())).account(owner).rooms.get(0);
        helper.assertTrue(reloaded.brief().title().equals("Кухня · каменный стиль")
                && reloaded.brief().conditions().equals(List.of("Внутри 9×9, квадратная")),
                "The room's name and conditions survive a reload");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void levelOddsStepWithEveryFifthRoomUntilTheHundredth(GameTestHelper helper) {
        int draws = 400_000;
        double[] start = {0.85, 0.12, 0.025, 0.0045, 0.0005};
        double[] end = {0.10, 0.15, 0.25, 0.30, 0.20};
        for (int completed : new int[] {0, 4, 5, 99, 100, 101}) {
            int step = Math.min(20, completed / 5);
            int[] seen = new int[5];
            RandomSource random = RandomSource.create(completed);
            for (int draw = 0; draw < draws; draw++) {
                seen[ContractBalance.selectDifficulty(random, completed).ordinal()]++;
            }
            for (int level = 0; level < 5; level++) {
                double expected = ((20 - step) * start[level] + step * end[level]) / 20;
                helper.assertTrue(Math.abs(seen[level] / (double) draws - expected) < 0.004,
                        "Level " + (level + 1) + " odds after " + completed + " rooms");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void zeroWeightsExcludeStoreysOnLevelOneAndTinyRoomsOnLevelFive(GameTestHelper helper) {
        for (long seed = 0; seed < 150; seed++) {
            RoomGenerator.generate(RandomSource.create(seed), 1, ANCHOR, Direction.NORTH, block -> true).ifPresent(room ->
                    helper.assertTrue(room.spec().heights().size() == 1, "Level 1 rooms have one storey"));
            RoomGenerator.generate(RandomSource.create(seed), 5, ANCHOR, Direction.NORTH, block -> true).ifPresent(room ->
                    helper.assertTrue(room.sizeCategory() != SizeCategory.TINY && room.spec().heights().size() <= 3
                            && room.spec().heights().stream().allMatch(height -> height >= 4 && height <= 10),
                            "Level 5 has no tiny rooms, at most three storeys and clear heights of 4 to 10"));
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void rarityWageCountsFullTenthsPerTierAndIsCapped(GameTestHelper helper) {
        helper.assertTrue(ContractBalance.rarityWage(10, strip(90, 5, 5, 0)) == 2,
                "Two second-tier materials of 5% each form one full tenth worth 2");
        helper.assertTrue(ContractBalance.rarityWage(10, strip(85, 8, 7, 0)) == 2, "15% of the second tier is still one step");
        helper.assertTrue(ContractBalance.rarityWage(10, strip(80, 10, 10, 0)) == 4, "20% of the second tier is two steps");
        helper.assertTrue(ContractBalance.rarityWage(5, strip(50, 0, 0, 50)) == 2, "The surcharge is capped at half the base wage");
        helper.assertTrue(ContractBalance.rarityWage(50, strip(50, 0, 0, 50)) == 25, "Five tenths of the fifth tier reach the cap");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void surchargesAddUpForSizeStoreysPatternsAndFurniture(GameTestHelper helper) {
        Surface stone = Surface.solid(Blocks.STONE);
        List<Fixture> light = List.of(new Fixture(0, new BlockPos(2, 1, 2), Blocks.TORCH.defaultBlockState()));
        int plain = wage(spec(List.of()), SizeCategory.TINY);
        int[] bySize = {0, 3, 7, 15, 25};
        for (SizeCategory size : SizeCategory.values()) {
            helper.assertTrue(wage(spec(List.of()), size) - plain == bySize[size.ordinal()], "Size surcharge of " + size);
        }
        Spec two = new Spec(9, 9, List.of(4, 4), stone, stone, stone, Set.of(Wall.OPPOSITE), List.of(), light, List.of(),
                Optional.empty());
        Spec three = new Spec(9, 9, List.of(4, 7, 10), stone, stone, stone, Set.of(Wall.OPPOSITE), List.of(), light, List.of(),
                Optional.empty());
        helper.assertTrue(wage(two, SizeCategory.TINY) - plain == 8 && wage(three, SizeCategory.TINY) - plain == 18,
                "Two and three own storeys add 8 and 18");
        Surface checkered = new Surface(Blocks.STONE.defaultBlockState(), RectangularRoomBlueprint.Pattern.CHECKERBOARD,
                Optional.of(Blocks.COBBLESTONE.defaultBlockState()));
        Surface striped = new Surface(Blocks.STONE.defaultBlockState(), RectangularRoomBlueprint.Pattern.STRIPES,
                Optional.of(Blocks.ANDESITE.defaultBlockState()));
        Spec patterned = new Spec(9, 9, List.of(4), striped, checkered, stone, Set.of(Wall.OPPOSITE), List.of(), light, List.of(),
                Optional.empty());
        helper.assertTrue(wage(patterned, SizeCategory.TINY) - plain == 5, "Checkered walls and a striped floor add 3 + 2");
        Fixture furnace = new Fixture(0, new BlockPos(4, 1, 4), Blocks.FURNACE.defaultBlockState());
        Fixture secondFurnace = new Fixture(0, new BlockPos(6, 1, 4), Blocks.FURNACE.defaultBlockState());
        Fixture bed = new Fixture(0, new BlockPos(6, 1, 6), Blocks.WHITE_BED.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
        helper.assertTrue(wage(furnished(furnace, secondFurnace), SizeCategory.TINY) - plain == 2
                && wage(furnished(furnace, bed), SizeCategory.TINY) - plain == 3,
                "A furnace adds 1 each and a bed 2 although it takes two blocks");
        helper.assertTrue(rejects(() -> new Spec(9, 9, List.of(4, 4, 4, 4), stone, stone, stone, Set.of(Wall.OPPOSITE), List.of(),
                light, List.of(), Optional.empty())), "Four own storeys are rejected");
        helper.assertTrue(rejects(() -> new Spec(9, 9, List.of(3), stone, stone, stone, Set.of(Wall.OPPOSITE), List.of(), light,
                List.of(), Optional.empty())) && rejects(() -> new Spec(9, 9, List.of(4, 11), stone, stone, stone,
                Set.of(Wall.OPPOSITE), List.of(), light, List.of(), Optional.empty())), "A storey must be 4 to 10 blocks high");
        helper.assertTrue(rejects(() -> new Spec(9, 9, List.of(4), stone, stone, stone, Set.of(), List.of(), light, List.of(),
                Optional.empty())), "A room needs at least one extra doorway");

        int[][] square = {{5, 5}, {7, 7}, {9, 9}, {11, 11}, {15, 15}};
        int[][] oblong = {{5, 7}, {7, 9}, {9, 11}, {13, 11}, {17, 15}};
        for (int level = 1; level <= 5; level++) {
            for (long seed = 0; seed < 40; seed++) {
                RoomGenerator.generate(RandomSource.create(seed), level, ANCHOR, Direction.NORTH, block -> true).ifPresent(room -> {
                    int size = room.sizeCategory().ordinal();
                    int width = room.spec().width();
                    int depth = room.spec().depth();
                    helper.assertTrue(width == square[size][0] && depth == square[size][1]
                            || width == oblong[size][0] && depth == oblong[size][1], "Footprint of " + room.sizeCategory());
                    helper.assertTrue(!room.spec().extraDoors().isEmpty() && room.spec().extraDoors().size() <= 3
                            && !room.spec().lights().isEmpty(), "Every room has one to three extra doorways and light");
                });
            }
        }
        helper.succeed();
    }

    private static Spec furnished(Fixture... furniture) {
        Surface stone = Surface.solid(Blocks.STONE);
        return new Spec(9, 9, List.of(4), stone, stone, stone, Set.of(Wall.OPPOSITE), List.of(),
                List.of(new Fixture(0, new BlockPos(2, 1, 2), Blocks.TORCH.defaultBlockState())), List.of(furniture),
                Optional.empty());
    }

    private static boolean rejects(Runnable construction) {
        try {
            construction.run();
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void tallRoomCrossesOnlyAnOpenAndFreeUpperDeck(GameTestHelper helper) {
        ServerLevel site = helper.getLevel().getServer().getLevel(ConstructionSite.SITE_LEVEL);
        Surface stone = Surface.solid(Blocks.STONE);
        RoomBlueprint tall = RectangularRoomBlueprint.at(ANCHOR, Direction.NORTH, new Spec(9, 9, List.of(10, 10, 10), stone, stone,
                stone, Set.of(Wall.OPPOSITE), List.of(), List.of(new Fixture(0, new BlockPos(2, 1, 2),
                Blocks.TORCH.defaultBlockState())), List.of(), Optional.empty()));
        int upperDeck = ConstructionSite.START_FLOOR_Y + ConstructionSite.FLOOR_STEP;
        BlockPos inside = new BlockPos(ANCHOR.getX(), upperDeck, ANCHOR.getZ() - 5);
        helper.assertTrue(tall.containsInterior(inside) && tall.bounds().max().getY() > upperDeck,
                "The third storey passes through the deck of the second floor");
        ContractEvents.withLoadedArea(site, tall.bounds(), () -> {
            helper.assertTrue(OfferFactory.scan(site, tall, List.of(), 1) == null, "A closed floor rejects the tall room");
            var placement = OfferFactory.scan(site, tall, List.of(), 2);
            helper.assertTrue(placement != null && placement.blueprint().requirements().get(inside).clearance()
                    && placement.originals().get(inside).is(Blocks.STONE), "On an open floor the deck inside is marked for clearing");
            helper.assertTrue(placement.blueprint().checkPosition(site, inside) == RoomBlueprint.Status.WRONG,
                    "A deck block left inside fails the check");
            helper.assertTrue(OfferFactory.scan(site, tall, List.of(new Bounds(inside.above(), inside.above(3))), 2) == null,
                    "A plot taken on the upper floor rejects the tall room");
            return null;
        });
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void columnsAndPlayerBlocksAreClearedByHandAndContainersReject(GameTestHelper helper) {
        ServerLevel site = helper.getLevel().getServer().getLevel(ConstructionSite.SITE_LEVEL);
        RoomBlueprint room = RectangularRoomBlueprint.at(ANCHOR, Direction.NORTH, spec(List.of()));
        BlockPos column = new BlockPos(296, ConstructionSite.START_FLOOR_Y + 2, 296);
        BlockPos columnInWall = new BlockPos(295, ConstructionSite.START_FLOOR_Y + 2, 296);
        BlockPos placed = new BlockPos(300, ConstructionSite.START_FLOOR_Y + 1, 294);
        ContractEvents.withLoadedArea(site, room.bounds(), () -> {
            helper.assertTrue(site.getBlockState(column).is(Blocks.STONE_BRICKS) && room.containsInterior(column)
                    && room.requirements().get(columnInWall).criterion() == RoomBlueprint.Criterion.WALLS,
                    "A frame column stands inside the future room and in its wall");
            site.setBlock(placed, Blocks.COBBLESTONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            try {
                var placement = OfferFactory.scan(site, room, List.of(), 1);
                helper.assertTrue(placement != null, "The column and the player's block do not reject the room");
                helper.assertTrue(placement.blueprint().requirements().get(column).clearance()
                        && placement.blueprint().requirements().get(placed).clearance(),
                        "Blocks inside the room are marked for manual clearing");
                helper.assertTrue(placement.blueprint().checkPosition(site, columnInWall) == RoomBlueprint.Status.WRONG
                        && placement.originals().containsKey(columnInWall), "A column block in the wall is a mismatch to replace");
                site.setBlock(placed, Blocks.CHEST.defaultBlockState(), Block.UPDATE_CLIENTS);
                helper.assertTrue(OfferFactory.scan(site, room, List.of(), 1) == null, "A container, even empty, rejects the room");
            } finally {
                site.setBlock(placed, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            return null;
        });
        helper.succeed();
    }

    /** A row of 100 mandatory blocks: stone, two second-tier materials and diamond. */
    private static RoomBlueprint strip(int stone, int deepslate, int cherry, int diamond) {
        Map<BlockPos, RoomBlueprint.Requirement> blocks = new java.util.LinkedHashMap<>();
        Block[] materials = {Blocks.STONE, Blocks.DEEPSLATE_BRICKS, Blocks.CHERRY_PLANKS, Blocks.DIAMOND_BLOCK};
        int[] counts = {stone, deepslate, cherry, diamond};
        for (int material = 0; material < materials.length; material++) {
            for (int index = 0; index < counts[material]; index++) {
                blocks.put(new BlockPos(blocks.size(), 0, 0), new RoomBlueprint.Requirement(
                        materials[material].defaultBlockState(), RoomBlueprint.Criterion.WALLS));
            }
        }
        Bounds bounds = new Bounds(BlockPos.ZERO, new BlockPos(99, 1, 0));
        return RoomBlueprint.of(bounds, List.of(bounds), blocks, List.of(new RoomBlueprint.Door(BlockPos.ZERO, Direction.NORTH)),
                Optional.empty());
    }

    private static Spec spec(List<Window> windows) {
        Surface stone = Surface.solid(Blocks.STONE);
        return new Spec(9, 9, List.of(4), stone, stone, stone, Set.of(Wall.OPPOSITE), windows,
                List.of(new Fixture(0, new BlockPos(2, 1, 2), Blocks.TORCH.defaultBlockState())), List.of(), Optional.empty());
    }

    private static int wage(List<Window> windows) {
        return wage(spec(windows), SizeCategory.MEDIUM);
    }

    private static int wage(Spec spec, SizeCategory size) {
        return ContractBalance.wage(ContractBalance.Difficulty.TWO, new GeneratedRoom(
                RectangularRoomBlueprint.at(ANCHOR, Direction.NORTH, spec), size, 2, spec, Category.LIVING, Style.ECLECTIC));
    }
}
