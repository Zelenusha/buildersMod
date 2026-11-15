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
        Spec spec = spec(windows);
        return ContractBalance.wage(ContractBalance.Difficulty.TWO, new GeneratedRoom(
                RectangularRoomBlueprint.at(ANCHOR, Direction.NORTH, spec), SizeCategory.MEDIUM, 2, spec,
                Category.LIVING, Style.ECLECTIC));
    }
}
