package io.github.zelenusha.buildersmod.blueprint;

import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Bonus;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Bounds;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Criterion;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Door;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Requirement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Places an already selected room design behind an existing door. */
public final class RectangularRoomBlueprint {
    public enum Pattern {
        NONE,
        CHECKERBOARD,
        STRIPES,
        SPECKLES
    }

    public enum Wall {
        ENTRANCE(Direction.NORTH),
        RIGHT(Direction.EAST),
        OPPOSITE(Direction.SOUTH),
        LEFT(Direction.WEST);

        private final Direction outward;

        Wall(Direction outward) {
            this.outward = outward;
        }
    }

    public enum Passage {
        LADDER,
        STAIRS
    }

    public record Surface(BlockState primary, Pattern pattern, Optional<BlockState> secondary) {
        public Surface {
            requireStableStructure(primary);
            Objects.requireNonNull(pattern, "pattern");
            if ((pattern == Pattern.NONE) != secondary.isEmpty()) {
                throw new IllegalArgumentException("A pattern requires exactly two materials");
            }
            secondary.ifPresent(state -> {
                requireStableStructure(state);
                if (state.is(primary.getBlock())) {
                    throw new IllegalArgumentException("Pattern materials must be distinct blocks");
                }
            });
        }

        public static Surface solid(Block block) {
            return new Surface(block.defaultBlockState(), Pattern.NONE, Optional.empty());
        }

        private BlockState stateAt(int u, int v) {
            boolean useSecondary = switch (pattern) {
                case NONE -> false;
                case CHECKERBOARD -> (u + v) % 2 == 1;
                case STRIPES -> u % 2 == 1;
                case SPECKLES -> u % 3 == 1 && v % 3 == 1;
            };
            return useSecondary ? secondary.orElseThrow() : primary;
        }
    }

    public record Window(int storey, Wall wall, int offset, int bottom, int width, int height, Block glass) {
        public Window {
            Objects.requireNonNull(wall, "wall");
            Objects.requireNonNull(glass, "glass");
            boolean supportedSize = width == 1 && height == 1 || width == 2 && height == 2
                    || width == 3 && height == 3 || width == 5 && height == 3;
            if (!supportedSize || offset < 1 || bottom < 1 || storey < 0) {
                throw new IllegalArgumentException("Invalid window geometry");
            }
            if (glass != Blocks.GLASS && glass != Blocks.GLASS_PANE && !(glass instanceof StainedGlassBlock)
                    && !(glass instanceof StainedGlassPaneBlock)) {
                throw new IllegalArgumentException("Window must specify full glass or a glass pane");
            }
        }
    }

    public record Fixture(int storey, BlockPos position, BlockState state) {
        public Fixture {
            position = position.immutable();
            Objects.requireNonNull(state, "state");
            if (storey < 0 || position.getX() < 1 || position.getY() < 1 || position.getZ() < 1) {
                throw new IllegalArgumentException("Fixture position must be inside a room storey");
            }
        }
    }

    public record Spec(int width, int depth, List<Integer> heights, Surface floor, Surface walls, Surface ceiling,
                       Set<Wall> extraDoors, List<Window> windows, List<Fixture> lights, List<Fixture> furniture,
                       Optional<Bonus> bonus, Passage passage) {
        public Spec {
            heights = List.copyOf(heights);
            extraDoors = Set.copyOf(extraDoors);
            windows = List.copyOf(windows);
            lights = List.copyOf(lights);
            furniture = List.copyOf(furniture);
            Objects.requireNonNull(floor, "floor");
            Objects.requireNonNull(walls, "walls");
            Objects.requireNonNull(ceiling, "ceiling");
            Objects.requireNonNull(bonus, "bonus");
            Objects.requireNonNull(passage, "passage");
            if (width < 5 || width > 17 || depth < 5 || depth > 17) {
                throw new IllegalArgumentException("Invalid room footprint");
            }
            if (heights.isEmpty() || heights.size() > 3 || heights.stream().anyMatch(height -> height < 4 || height > 10)) {
                throw new IllegalArgumentException("Expected one to three storeys, each four to ten clear blocks high");
            }
            if (extraDoors.isEmpty() || extraDoors.contains(Wall.ENTRANCE)) {
                throw new IllegalArgumentException("Expected one to three additional doors outside the entrance wall");
            }
            if (windows.size() > 2 || lights.isEmpty() || lights.size() > 4 || furniture.size() > 2) {
                throw new IllegalArgumentException("Invalid number of windows, lights or functional blocks");
            }
        }

        public Spec(int width, int depth, List<Integer> heights, Surface floor, Surface walls, Surface ceiling,
                    Set<Wall> extraDoors, List<Window> windows, List<Fixture> lights, List<Fixture> furniture,
                    Optional<Bonus> bonus) {
            this(width, depth, heights, floor, walls, ceiling, extraDoors, windows, lights, furniture, bonus,
                    Passage.LADDER);
        }
    }

    private RectangularRoomBlueprint() {
    }

    public static RoomBlueprint at(BlockPos connectingDoorLower, Direction outward, Spec spec) {
        if (!outward.getAxis().isHorizontal()) {
            throw new IllegalArgumentException("Room must connect through a horizontal doorway");
        }
        Direction right = outward.getCounterClockWise();
        int entrance = (spec.width() + 1) / 2;
        BlockPos origin = connectingDoorLower.relative(outward).below().relative(right.getOpposite(), entrance);
        Frame frame = new Frame(origin, outward);
        int farX = spec.width() + 1;
        int farZ = spec.depth() + 1;
        int[] levels = new int[spec.heights().size()];
        List<Bounds> interiors = new ArrayList<>();
        Map<BlockPos, Requirement> local = new LinkedHashMap<>();

        int floorY = 0;
        int top = 0;
        for (int storey = 0; storey < levels.length; storey++) {
            levels[storey] = floorY;
            top = floorY + spec.heights().get(storey) + 1;
            addStorey(local, spec, farX, farZ, floorY, top);
            interiors.add(bounds(frame.at(1, floorY + 1, 1), frame.at(farX - 1, top - 1, farZ - 1)));
            floorY = top + 1;
        }

        List<Door> doors = addDoors(local, frame, spec);
        List<RoomBlueprint.Window> openings = new ArrayList<>();
        for (Window window : spec.windows()) {
            openings.add(addWindow(local, window, spec, levels, frame));
        }
        if (levels.length > 1) {
            switch (spec.passage()) {
                case LADDER -> addLadder(local, levels, farX);
                case STAIRS -> addStairs(local, levels, spec);
            }
        }
        reserveDoorAccess(local, spec);
        for (Fixture light : spec.lights()) {
            addLight(local, light, spec, levels);
        }
        for (Fixture fixture : spec.furniture()) {
            addFurniture(local, fixture, spec, levels);
        }

        Map<BlockPos, Requirement> placed = new LinkedHashMap<>();
        local.forEach((position, requirement) -> placed.put(frame.at(position.getX(), position.getY(), position.getZ()),
                new Requirement(requirement.expectedState().rotate(frame.rotation()), requirement.criterion())));
        return new RoomBlueprint(bounds(origin, frame.at(farX, top, farZ)), interiors, placed, doors, spec.bonus(),
                openings);
    }

    private static void addStorey(Map<BlockPos, Requirement> local, Spec spec, int farX, int farZ, int floor, int top) {
        for (int x = 0; x <= farX; x++) {
            for (int z = 0; z <= farZ; z++) {
                local.put(new BlockPos(x, floor, z), requirement(spec.floor().stateAt(x, z), Criterion.FLOOR));
                local.put(new BlockPos(x, top, z), requirement(spec.ceiling().stateAt(x, z), Criterion.CEILING));
                if (x == 0 || x == farX || z == 0 || z == farZ) {
                    int u = perimeterIndex(x, z, farX, farZ);
                    for (int y = floor + 1; y < top; y++) {
                        local.put(new BlockPos(x, y, z), requirement(spec.walls().stateAt(u, y - floor - 1), Criterion.WALLS));
                    }
                }
            }
        }
    }

    private static int perimeterIndex(int x, int z, int farX, int farZ) {
        if (z == 0) {
            return x;
        }
        if (x == farX) {
            return farX + z;
        }
        if (z == farZ) {
            return farX + farZ + farX - x;
        }
        return 2 * (farX + farZ) - z;
    }

    private static List<Door> addDoors(Map<BlockPos, Requirement> local, Frame frame, Spec spec) {
        List<Door> doors = new ArrayList<>();
        for (Wall wall : Wall.values()) {
            if (wall == Wall.ENTRANCE || spec.extraDoors().contains(wall)) {
                BlockPos position = doorPosition(spec, wall);
                BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, wall.outward);
                local.put(position, requirement(lower, Criterion.DOORWAYS));
                local.put(position.above(), requirement(lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Criterion.DOORWAYS));
                doors.add(new Door(frame.at(position.getX(), position.getY(), position.getZ()),
                        frame.rotation().rotate(wall.outward)));
            }
        }
        return doors;
    }

    private static RoomBlueprint.Window addWindow(Map<BlockPos, Requirement> local, Window window, Spec spec,
                                                  int[] levels, Frame frame) {
        int floor = storeyLevel(levels, window.storey());
        if (window.offset() + window.width() - 1 > wallLength(spec, window.wall())
                || window.bottom() + window.height() - 1 > spec.heights().get(window.storey())) {
            throw new IllegalArgumentException("Window does not fit within its wall");
        }
        for (int offset = 0; offset < window.width(); offset++) {
            for (int height = 0; height < window.height(); height++) {
                BlockPos position = wallPosition(spec, window.wall(), window.offset() + offset,
                        floor + window.bottom() + height);
                if (local.get(position).criterion() != Criterion.WALLS) {
                    throw new IllegalArgumentException("Window overlaps a door or another window");
                }
                local.put(position, requirement(window.glass().defaultBlockState(), Criterion.WINDOWS));
            }
        }
        BlockPos first = wallPosition(spec, window.wall(), window.offset(), floor + window.bottom());
        BlockPos last = wallPosition(spec, window.wall(), window.offset() + window.width() - 1,
                floor + window.bottom() + window.height() - 1);
        return new RoomBlueprint.Window(bounds(frame.at(first), frame.at(last)),
                frame.rotation().rotate(window.wall().outward), RoomBlueprint.WindowDestination.UNSPECIFIED);
    }

    private static int wallLength(Spec spec, Wall wall) {
        return wall == Wall.ENTRANCE || wall == Wall.OPPOSITE ? spec.width() : spec.depth();
    }

    private static BlockPos doorPosition(Spec spec, Wall wall) {
        return wallPosition(spec, wall, (wallLength(spec, wall) + 1) / 2, 1);
    }

    private static BlockPos wallPosition(Spec spec, Wall wall, int offset, int y) {
        return switch (wall) {
            case ENTRANCE -> new BlockPos(offset, y, 0);
            case RIGHT -> new BlockPos(spec.width() + 1, y, offset);
            case OPPOSITE -> new BlockPos(offset, y, spec.depth() + 1);
            case LEFT -> new BlockPos(0, y, offset);
        };
    }

    private static void addLadder(Map<BlockPos, Requirement> local, int[] levels, int farX) {
        int lastFloor = levels[levels.length - 1];
        BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST);
        // The ladder climbs against the wall and its upper landing opens on the last floor.
        for (int y = 1; y <= lastFloor + 2; y++) {
            BlockPos support = new BlockPos(farX, y, 1);
            if (local.get(support).criterion() == Criterion.WINDOWS) {
                throw new IllegalArgumentException("Window overlaps the ladder support");
            }
            local.put(support.west(), requirement(ladder, Criterion.STAIRWAYS));
        }
        for (int index = 1; index < levels.length; index++) {
            int y = levels[index];
            BlockPos landing = new BlockPos(farX - 2, y + 1, 1);
            local.put(landing, requirement(Blocks.AIR.defaultBlockState(), Criterion.CLEARANCE));
            local.put(landing.above(), requirement(Blocks.AIR.defaultBlockState(), Criterion.CLEARANCE));
        }
    }

    private static void addStairs(Map<BlockPos, Requirement> local, int[] levels, Spec spec) {
        for (int storey = 0; storey + 1 < levels.length; storey++) {
            int rise = levels[storey + 1] - levels[storey];
            if (spec.depth() < rise + 2 || levels.length > 2 && spec.width() < 6) {
                throw new IllegalArgumentException("Room does not have space for a straight stair rise and landings");
            }
        }
        for (int storey = 0; storey + 1 < levels.length; storey++) {
            int bottom = levels[storey];
            int top = levels[storey + 1];
            int rise = top - bottom;
            boolean forward = storey % 2 == 0;
            int x = forward ? spec.width() - 1 : 2;
            int start = forward ? 2 : spec.depth() - 1;
            int step = forward ? 1 : -1;
            Direction facing = forward ? Direction.SOUTH : Direction.NORTH;
            BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
            BlockPos approach = new BlockPos(x, bottom + 1, start - step);
            putClearance(local, approach);
            putClearance(local, approach.above());
            for (int index = 0; index < rise; index++) {
                BlockPos position = new BlockPos(x, bottom + index + 1, start + step * index);
                Requirement previous = local.get(position);
                if (previous != null && previous.criterion() != Criterion.FLOOR && previous.criterion() != Criterion.CEILING) {
                    throw new IllegalArgumentException("Stair tread overlaps another required position: " + position);
                }
                local.put(position, requirement(stairs, Criterion.STAIRWAYS));
                putClearance(local, position.above());
                putClearance(local, position.above(2));
            }
            BlockPos landing = new BlockPos(x, top + 1, start + step * rise);
            putClearance(local, landing);
            putClearance(local, landing.above());
        }
    }

    private static void reserveDoorAccess(Map<BlockPos, Requirement> local, Spec spec) {
        for (Wall wall : Wall.values()) {
            if (wall == Wall.ENTRANCE || spec.extraDoors().contains(wall)) {
                BlockPos approach = doorPosition(spec, wall).relative(wall.outward.getOpposite());
                putClearance(local, approach);
                putClearance(local, approach.above());
            }
        }
    }

    private static void putClearance(Map<BlockPos, Requirement> local, BlockPos position) {
        Requirement existing = local.get(position);
        if (existing != null && (existing.criterion() == Criterion.STAIRWAYS
                || existing.criterion() == Criterion.DOORWAYS || existing.criterion() == Criterion.WINDOWS)) {
            throw new IllegalArgumentException("Clear passage overlaps another required position: " + position);
        }
        local.put(position, requirement(Blocks.AIR.defaultBlockState(), Criterion.CLEARANCE));
    }

    private static void addLight(Map<BlockPos, Requirement> local, Fixture fixture, Spec spec, int[] levels) {
        BlockPos position = fixturePosition(fixture, spec, levels);
        BlockState state = fixture.state();
        if (state.is(Blocks.TORCH) || state.is(Blocks.LANTERN)) {
            if (fixture.position().getY() != 1 || !local.containsKey(position.below())
                    || local.get(position.below()).criterion() != Criterion.FLOOR
                    || state.is(Blocks.LANTERN) && state.getValue(BlockStateProperties.HANGING)) {
                throw new IllegalArgumentException("Torch or lantern requires a floor support");
            }
        } else if (!state.is(Blocks.GLOWSTONE)) {
            throw new IllegalArgumentException("Unsupported room light source");
        }
        putFixture(local, position, state, Criterion.LIGHTING);
    }

    private static void addFurniture(Map<BlockPos, Requirement> local, Fixture fixture, Spec spec, int[] levels) {
        BlockPos position = fixturePosition(fixture, spec, levels);
        BlockState state = fixture.state();
        if (fixture.position().getY() != 1 || !hasFloor(local, position.below())) {
            throw new IllegalArgumentException("Functional block requires a floor position");
        }
        if (state.getBlock() instanceof BedBlock) {
            BlockPos head = position.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING));
            if (head.getX() < 1 || head.getX() > spec.width() || head.getZ() < 1 || head.getZ() > spec.depth()
                    || !hasFloor(local, head.below())) {
                throw new IllegalArgumentException("Bed head must fit on the same floor");
            }
            putFixture(local, position, state.setValue(BlockStateProperties.BED_PART, BedPart.FOOT), Criterion.FUNCTIONAL_BLOCKS);
            putFixture(local, head, state.setValue(BlockStateProperties.BED_PART, BedPart.HEAD), Criterion.FUNCTIONAL_BLOCKS);
        } else if (state.is(Blocks.FURNACE) || state.is(Blocks.CRAFTING_TABLE)) {
            putFixture(local, position, state, Criterion.FUNCTIONAL_BLOCKS);
        } else {
            throw new IllegalArgumentException("Unsupported functional block");
        }
    }

    private static boolean hasFloor(Map<BlockPos, Requirement> local, BlockPos position) {
        Requirement support = local.get(position);
        return support != null && support.criterion() == Criterion.FLOOR;
    }

    private static BlockPos fixturePosition(Fixture fixture, Spec spec, int[] levels) {
        int floor = storeyLevel(levels, fixture.storey());
        BlockPos position = fixture.position();
        if (position.getX() > spec.width() || position.getZ() > spec.depth()
                || position.getY() > spec.heights().get(fixture.storey())) {
            throw new IllegalArgumentException("Fixture does not fit in its room");
        }
        return position.above(floor);
    }

    private static int storeyLevel(int[] levels, int storey) {
        if (storey >= levels.length) {
            throw new IllegalArgumentException("Unknown fixture or window storey");
        }
        return levels[storey];
    }

    private static void putFixture(Map<BlockPos, Requirement> local, BlockPos position, BlockState state, Criterion criterion) {
        if (local.putIfAbsent(position, requirement(state, criterion)) != null) {
            throw new IllegalArgumentException("Fixture position is occupied: " + position);
        }
    }

    private static Requirement requirement(BlockState state, Criterion criterion) {
        return new Requirement(state, criterion);
    }

    private static void requireStableStructure(BlockState state) {
        Objects.requireNonNull(state, "structure state");
        if (state.isAir() || state.is(Blocks.BEDROCK) || state.getBlock() instanceof FallingBlock
                || !Block.isShapeFullBlock(state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO))) {
            throw new IllegalArgumentException("Structure requires a full, stable building block");
        }
    }

    private static Bounds bounds(BlockPos first, BlockPos second) {
        return new Bounds(new BlockPos(Math.min(first.getX(), second.getX()), Math.min(first.getY(), second.getY()),
                Math.min(first.getZ(), second.getZ())), new BlockPos(Math.max(first.getX(), second.getX()),
                Math.max(first.getY(), second.getY()), Math.max(first.getZ(), second.getZ())));
    }

    private record Frame(BlockPos origin, Direction forward) {
        BlockPos at(BlockPos local) {
            return at(local.getX(), local.getY(), local.getZ());
        }

        BlockPos at(int x, int y, int z) {
            return origin.relative(forward.getCounterClockWise(), x).above(y).relative(forward, z);
        }

        Rotation rotation() {
            return switch (forward) {
                case SOUTH -> Rotation.NONE;
                case WEST -> Rotation.CLOCKWISE_90;
                case NORTH -> Rotation.CLOCKWISE_180;
                case EAST -> Rotation.COUNTERCLOCKWISE_90;
                default -> throw new IllegalStateException("Vertical room direction");
            };
        }
    }
}
