package io.github.zelenusha.buildersmod.blueprint;

import io.github.zelenusha.buildersmod.blueprint.RectangularRoomBlueprint.Fixture;
import io.github.zelenusha.buildersmod.blueprint.RectangularRoomBlueprint.Passage;
import io.github.zelenusha.buildersmod.blueprint.RectangularRoomBlueprint.Pattern;
import io.github.zelenusha.buildersmod.blueprint.RectangularRoomBlueprint.Spec;
import io.github.zelenusha.buildersmod.blueprint.RectangularRoomBlueprint.Surface;
import io.github.zelenusha.buildersmod.blueprint.RectangularRoomBlueprint.Wall;
import io.github.zelenusha.buildersmod.blueprint.RectangularRoomBlueprint.Window;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Criterion;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

public final class RoomGenerator {
    private static final int MAX_ATTEMPTS = 8;
    private static final int[][] SIZE = {
            {50, 30, 10, 5, 2}, {35, 35, 25, 15, 8}, {12, 25, 35, 30, 20}, {3, 9, 20, 30, 35}, {0, 1, 10, 20, 35}
    };
    private static final int[][] STOREYS = {{100, 0, 0}, {85, 13, 2}, {65, 25, 8}, {40, 35, 18}, {20, 35, 25}};
    private static final int[][] FORM = {{55, 40}, {50, 40}, {42, 40}, {33, 40}, {25, 40}};
    private static final int[][] RARITY = {
            {85, 13, 2, 0, 0}, {65, 25, 9, 1, 0}, {45, 30, 18, 6, 1}, {25, 30, 25, 15, 5}, {10, 25, 30, 25, 10}
    };
    private static final int[][] HEIGHT = {
            {65, 25, 10, 0, 0, 0, 0}, {40, 35, 20, 5, 0, 0, 0}, {25, 25, 25, 15, 10, 0, 0},
            {12, 18, 22, 20, 15, 8, 5}, {5, 10, 15, 20, 20, 15, 15}
    };
    private static final int[][] PATTERN = {{85, 5, 5, 5}, {65, 12, 12, 11}, {50, 18, 17, 15}, {35, 25, 21, 19}, {20, 30, 26, 24}};
    private static final int[][] WINDOWS = {{75, 25, 0}, {60, 35, 5}, {45, 40, 15}, {30, 45, 25}, {20, 45, 35}};
    private static final int[][] LIGHT_COUNT = {{30, 65, 5, 0}, {20, 60, 15, 5}, {10, 45, 30, 15}, {5, 30, 40, 25}, {0, 20, 35, 45}};
    private static final int[][] LIGHT_TYPE = {{100, 0, 0}, {70, 30, 0}, {55, 30, 15}, {40, 35, 25}, {25, 35, 40}};
    private static final int[][] FURNITURE_COUNT = {{25, 75, 0}, {20, 65, 15}, {15, 55, 30}, {10, 45, 45}, {5, 35, 60}};
    private static final int[] FURNITURE_TYPE = {35, 25, 40};
    private static final int[][] GLAZING_SIZES = {{1, 1}, {2, 2}, {3, 3}, {5, 3}};

    private RoomGenerator() {
    }

    public enum SizeCategory {
        TINY(5, 5, 0), SMALL(7, 7, 3), MEDIUM(9, 9, 7), LARGE(13, 11, 15), HUGE(17, 15, 25);

        private final int width;
        private final int depth;
        private final int surcharge;

        SizeCategory(int width, int depth, int surcharge) {
            this.width = width;
            this.depth = depth;
            this.surcharge = surcharge;
        }

        public int surcharge() {
            return surcharge;
        }
    }

    public record GeneratedRoom(RoomBlueprint blueprint, SizeCategory sizeCategory, int difficulty, Spec spec) {
    }

    public static Optional<GeneratedRoom> generate(RandomSource random, int difficulty, BlockPos anchorLower,
                                                  Direction outward, Predicate<Block> materialAvailable) {
        return generate(random, difficulty, anchorLower, outward, materialAvailable, room -> true);
    }

    public static Optional<GeneratedRoom> generate(RandomSource random, int difficulty, BlockPos anchorLower,
                                                  Direction outward, Predicate<Block> materialAvailable,
                                                  Predicate<GeneratedRoom> placementAllowed) {
        if (difficulty < 1 || difficulty > 5 || !outward.getAxis().isHorizontal()) {
            throw new IllegalArgumentException("Expected level 1–5 and a horizontal doorway");
        }
        int level = difficulty - 1;
        Predicate<Block> available = block -> materialAvailable.test(block) && (level > 1 || !otherDimension(block));
        if (!available.test(Blocks.OAK_DOOR) || !hasAvailableLight(level, available)) {
            return Optional.empty();
        }
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Optional<GeneratedRoom> candidate = generateCandidate(random, level, anchorLower, outward, available);
            if (candidate.isPresent() && placementAllowed.test(candidate.orElseThrow())) {
                return candidate;
            }
        }
        return Optional.empty();
    }

    private static Optional<GeneratedRoom> generateCandidate(RandomSource random, int level, BlockPos anchor,
                                                            Direction outward, Predicate<Block> available) {
        SizeCategory size = SizeCategory.values()[weighted(random, SIZE[level])];
        boolean square = weighted(random, FORM[level]) == 0;
        int width = square ? Math.min(size.width, size.depth) : size.width;
        int depth = square ? width : size.width == size.depth ? size.depth + 2 : size.depth;
        List<Integer> heights = new ArrayList<>();
        int[] storeyWeights = STOREYS[level].clone();
        if (!available.test(Blocks.LADDER) && !available.test(Blocks.OAK_STAIRS)) {
            storeyWeights[1] = 0;
            storeyWeights[2] = 0;
        }
        int numStoreys = weighted(random, storeyWeights) + 1;
        for (int storey = 0; storey < numStoreys; storey++) {
            heights.add(4 + weighted(random, HEIGHT[level]));
        }

        Optional<Surface> floor = selectSurface(random, level, available);
        Optional<Surface> walls = selectSurface(random, level, available);
        Optional<Surface> ceiling = selectSurface(random, level, available);
        if (floor.isEmpty() || walls.isEmpty() || ceiling.isEmpty()) {
            return Optional.empty();
        }
        Set<Wall> doors = chooseExtraDoors(random);
        List<Window> windows = selectWindows(random, level, width, depth, heights, doors, available);
        Optional<RoomBlueprint.Bonus> bonus = available.test(Blocks.CAKE)
                ? Optional.of(new RoomBlueprint.Bonus(Blocks.CAKE, 3, 5)) : Optional.empty();
        Passage passage = choosePassage(random, width, depth, heights, available);
        if (heights.size() > 1 && passage == Passage.LADDER && !available.test(Blocks.LADDER)) {
            return Optional.empty();
        }

        List<Fixture> basicLight = List.of(new Fixture(0, new BlockPos(1, 1, 1), firstLight(random, level, available)));
        Spec shell = new Spec(width, depth, heights, floor.orElseThrow(), walls.orElseThrow(), ceiling.orElseThrow(),
                doors, windows, basicLight, List.of(), bonus, passage);
        try {
            RoomBlueprint skeleton = RectangularRoomBlueprint.at(anchor, outward, shell);
            List<Fixture> lights = new ArrayList<>(basicLight);
            List<Fixture> furniture = new ArrayList<>();
            List<FixturePosition> inside = supportedFixturePositions(skeleton, anchor, outward, width, depth, heights);
            for (int index = 1, count = weighted(random, LIGHT_COUNT[level]) + 1; index < count; index++) {
                pickLight(random, level, available, inside, lights);
            }
            int furnCount = weighted(random, FURNITURE_COUNT[level]);
            for (int index = 0; index < furnCount; index++) {
                pickFurniture(random, available, inside, furniture);
            }
            Spec spec = new Spec(width, depth, heights, shell.floor(), shell.walls(), shell.ceiling(), doors, windows,
                    lights, furniture, bonus, passage);
            RoomBlueprint blueprint = RectangularRoomBlueprint.at(anchor, outward, spec);
            return Optional.of(new GeneratedRoom(blueprint, size, level + 1, spec));
        } catch (IllegalArgumentException incompatibleGeometry) {
            return Optional.empty();
        }
    }

    private static Passage choosePassage(RandomSource random, int width, int depth, List<Integer> heights,
                                         Predicate<Block> available) {
        boolean stairs = available.test(Blocks.OAK_STAIRS) && (heights.size() < 3 || width >= 6);
        for (int storey = 0; storey + 1 < heights.size(); storey++) {
            stairs &= depth >= heights.get(storey) + 4;
        }
        if (stairs && (!available.test(Blocks.LADDER) || random.nextBoolean())) {
            return Passage.STAIRS;
        }
        return Passage.LADDER;
    }

    private static Set<Wall> chooseExtraDoors(RandomSource random) {
        List<Wall> options = new ArrayList<>(List.of(Wall.RIGHT, Wall.OPPOSITE, Wall.LEFT));
        Set<Wall> doors = EnumSet.noneOf(Wall.class);
        int count = random.nextInt(3) + 1;
        for (int index = 0; index < count; index++) {
            doors.add(options.remove(random.nextInt(options.size())));
        }
        return doors;
    }

    private static Optional<Surface> selectSurface(RandomSource random, int level, Predicate<Block> available) {
        Optional<Block> primary = chooseMaterial(random, level, available);
        if (primary.isEmpty()) {
            return Optional.empty();
        }
        Block block = primary.orElseThrow();
        Predicate<Block> alternate = available.and(candidate -> candidate != block);
        int[] patternWeights = PATTERN[level];
        if (!hasAvailableSurfaceMaterial(level, alternate)) {
            patternWeights = new int[] {1, 0, 0, 0};
        }
        Pattern pattern = Pattern.values()[weighted(random, patternWeights)];
        if (pattern == Pattern.NONE) {
            return Optional.of(Surface.solid(block));
        }
        return chooseMaterial(random, level, alternate).map(candidate -> new Surface(block.defaultBlockState(), pattern,
                Optional.of(candidate.defaultBlockState())));
    }

    private static Optional<Block> chooseMaterial(RandomSource random, int level, Predicate<Block> available) {
        List<List<Block>> byRarity = structureMaterials();
        int[] weights = RARITY[level].clone();
        List<List<Block>> eligible = new ArrayList<>();
        for (int index = 0; index < byRarity.size(); index++) {
            List<Block> materials = byRarity.get(index).stream().filter(available).toList();
            if (materials.isEmpty()) {
                weights[index] = 0;
            }
            eligible.add(materials);
        }
        int tier = weighted(random, weights);
        return tier < 0 ? Optional.empty() : Optional.of(pick(random, eligible.get(tier)));
    }

    private static List<List<Block>> structureMaterials() {
        return List.of(
                List.of(Blocks.STONE, Blocks.COBBLESTONE, Blocks.STONE_BRICKS, Blocks.OAK_PLANKS, Blocks.GRANITE,
                        Blocks.DIORITE, Blocks.ANDESITE, Blocks.SANDSTONE),
                List.of(Blocks.DEEPSLATE_BRICKS, Blocks.CHERRY_PLANKS, Blocks.COPPER_BLOCK, Blocks.CUT_COPPER),
                List.of(Blocks.AMETHYST_BLOCK, Blocks.CALCITE, Blocks.POLISHED_BLACKSTONE, Blocks.QUARTZ_BLOCK,
                        Blocks.OBSIDIAN),
                List.of(Blocks.PURPUR_BLOCK, Blocks.END_STONE_BRICKS, Blocks.CRYING_OBSIDIAN, Blocks.PRISMARINE),
                List.of(Blocks.DIAMOND_BLOCK, Blocks.NETHERITE_BLOCK));
    }

    private static boolean hasAvailableSurfaceMaterial(int level, Predicate<Block> available) {
        List<List<Block>> tiers = structureMaterials();
        for (int tier = 0; tier < tiers.size(); tier++) {
            if (RARITY[level][tier] > 0 && tiers.get(tier).stream().anyMatch(available)) {
                return true;
            }
        }
        return false;
    }

    private static boolean otherDimension(Block block) {
        return block == Blocks.POLISHED_BLACKSTONE || block == Blocks.QUARTZ_BLOCK || block == Blocks.PURPUR_BLOCK
                || block == Blocks.END_STONE_BRICKS || block == Blocks.NETHERITE_BLOCK || block == Blocks.GLOWSTONE;
    }

    private static List<Window> selectWindows(RandomSource random, int level, int width, int depth,
                                               List<Integer> heights, Set<Wall> doors, Predicate<Block> available) {
        List<Block> glass = List.of(Blocks.GLASS, Blocks.GLASS_PANE, Blocks.WHITE_STAINED_GLASS,
                Blocks.WHITE_STAINED_GLASS_PANE, Blocks.BLUE_STAINED_GLASS, Blocks.BLUE_STAINED_GLASS_PANE).stream()
                .filter(available).toList();
        if (glass.isEmpty()) {
            return List.of();
        }
        List<WindowPlace> candidates = new ArrayList<>();
        for (int floor = 0; floor < heights.size(); floor++) {
            int height = heights.get(floor);
            for (Wall wall : Wall.values()) {
                int length = wall == Wall.ENTRANCE || wall == Wall.OPPOSITE ? width : depth;
                int bottom = floor == 0 && (wall == Wall.ENTRANCE || doors.contains(wall)) ? 3 : 2;
                for (int[] size : GLAZING_SIZES) {
                    if (bottom + size[1] - 1 <= height && length >= size[0]) {
                        candidates.add(new WindowPlace(floor, wall, (length - size[0]) / 2 + 1, bottom, size[0], size[1]));
                    }
                }
            }
        }
        long slots = candidates.stream().map(place -> place.storey() + ":" + place.wall().name()).distinct().count();
        int[] weights = WINDOWS[level].clone();
        for (int index = (int) slots + 1; index < weights.length; index++) {
            weights[index] = 0;
        }
        int count = weighted(random, weights);
        List<Window> result = new ArrayList<>();
        for (int index = 0; index < count && !candidates.isEmpty(); index++) {
            List<List<WindowPlace>> bySize = new ArrayList<>();
            for (int[] size : GLAZING_SIZES) {
                List<WindowPlace> matches = candidates.stream()
                        .filter(place -> place.width() == size[0] && place.height() == size[1]).toList();
                if (!matches.isEmpty()) {
                    bySize.add(matches);
                }
            }
            WindowPlace picked = pick(random, pick(random, bySize));
            candidates.removeIf(candidate -> candidate.storey() == picked.storey() && candidate.wall() == picked.wall());
            result.add(new Window(picked.storey(), picked.wall(), picked.offset(), picked.bottom(),
                    picked.width(), picked.height(), pick(random, glass)));
        }
        return result;
    }

    private static List<FixturePosition> supportedFixturePositions(RoomBlueprint shell, BlockPos anchor, Direction outward,
                                                                 int width, int depth, List<Integer> heights) {
        Direction right = outward.getCounterClockWise();
        BlockPos origin = anchor.relative(outward).below().relative(right.getOpposite(), (width + 1) / 2);
        List<FixturePosition> supported = new ArrayList<>();
        int y = 0;
        for (int storey = 0; storey < heights.size(); storey++) {
            for (int x = 1; x <= width; x++) {
                for (int z = 1; z <= depth; z++) {
                    BlockPos ground = origin.relative(right, x).relative(outward, z).above(y);
                    BlockPos aboveGround = ground.above();
                    var support = shell.requirements().get(ground);
                    if (!shell.requirements().containsKey(aboveGround) && support != null
                            && support.criterion() == Criterion.FLOOR) {
                        supported.add(new FixturePosition(storey, new BlockPos(x, 1, z)));
                    }
                }
            }
            y += heights.get(storey) + 2;
        }
        return supported;
    }

    private static BlockState firstLight(RandomSource random, int level, Predicate<Block> available) {
        List<Block> types = lightTypes();
        return types.get(chooseAvailable(LIGHT_TYPE[level], types, available, random)).defaultBlockState();
    }

    private static List<Block> lightTypes() {
        return List.of(Blocks.TORCH, Blocks.LANTERN, Blocks.GLOWSTONE);
    }

    private static boolean hasAvailableLight(int level, Predicate<Block> available) {
        List<Block> types = lightTypes();
        for (int index = 0; index < types.size(); index++) {
            if (LIGHT_TYPE[level][index] > 0 && available.test(types.get(index))) {
                return true;
            }
        }
        return false;
    }

    private static void pickLight(RandomSource random, int level, Predicate<Block> available,
                                  List<FixturePosition> positions, List<Fixture> lights) {
        FixturePosition position = positions.remove(random.nextInt(positions.size()));
        lights.add(fixture(position, firstLight(random, level, available)));
    }

    private static void pickFurniture(RandomSource random, Predicate<Block> available, List<FixturePosition> positions,
                                      List<Fixture> furniture) {
        List<Block> types = List.of(Blocks.FURNACE, Blocks.WHITE_BED, Blocks.CRAFTING_TABLE);
        List<FixturePosition> possibleBeds = positions.stream()
                .filter(position -> positions.contains(position.bedHead())).toList();
        int type = chooseAvailable(FURNITURE_TYPE, types,
                available.and(block -> block != Blocks.WHITE_BED || !possibleBeds.isEmpty()), random);
        if (type < 0) {
            return;
        }

        Block block = types.get(type);
        if (block == Blocks.WHITE_BED) {
            FixturePosition bed = pick(random, possibleBeds);
            positions.remove(bed);
            positions.remove(bed.bedHead());
            furniture.add(fixture(bed, block.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH)));
        } else {
            FixturePosition position = positions.remove(random.nextInt(positions.size()));
            furniture.add(fixture(position, block.defaultBlockState()));
        }
    }

    private static Fixture fixture(FixturePosition position, BlockState state) {
        return new Fixture(position.storey(), position.position(), state);
    }

    private static int chooseAvailable(int[] weights, List<Block> types, Predicate<Block> available,
                                       RandomSource random) {
        int[] eligible = weights.clone();
        for (int index = 0; index < types.size(); index++) {
            if (!available.test(types.get(index))) {
                eligible[index] = 0;
            }
        }
        return weighted(random, eligible);
    }

    private static int weighted(RandomSource random, int[] weights) {
        int total = 0;
        for (int weight : weights) {
            total += weight;
        }
        if (total == 0) {
            return -1;
        }
        int choice = random.nextInt(total);
        for (int index = 0; index < weights.length; index++) {
            choice -= weights[index];
            if (choice < 0) {
                return index;
            }
        }
        throw new IllegalStateException("Weighted selection exceeded total");
    }

    private static <T> T pick(RandomSource random, List<T> choices) {
        return choices.get(random.nextInt(choices.size()));
    }

    private record WindowPlace(int storey, Wall wall, int offset, int bottom, int width, int height) {
    }

    private record FixturePosition(int storey, BlockPos position) {
        FixturePosition bedHead() {
            return new FixturePosition(storey, position.south());
        }
    }
}
