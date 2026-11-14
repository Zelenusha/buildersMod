package io.github.zelenusha.buildersmod.site;

import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Bounds;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Criterion;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Door;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Requirement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;

/** A sequence of stair flights along the west side of the starter room and a shell on the next deck. */
public final class SiteStairBlueprint {
    private static final int OUTER_EDGE = 4;
    private static final int INNER_EDGE = OUTER_EDGE - 1;
    private static final int WEST_LANE = -3;
    private static final int ROOM_CEILING_OFFSET = 5;
    private static final int FLIGHTS = 5;
    private static final int STEPS_PER_FLIGHT = ConstructionSite.FLOOR_STEP / FLIGHTS;

    public enum Layout {
        NORTH_FIRST(Direction.NORTH),
        SOUTH_FIRST(Direction.SOUTH);

        private final Direction start;

        Layout(Direction start) {
            this.start = start;
        }
    }

    public record Materials(BlockState stairs, BlockState landings, BlockState roomFloor,
                            BlockState roomWalls, BlockState roomCeiling) {
        public Materials {
            if (!(stairs.getBlock() instanceof StairBlock)) {
                throw new IllegalArgumentException("Stairs must use a stair block");
            }
            for (BlockState state : List.of(landings, roomFloor, roomWalls, roomCeiling)) {
                if (!state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
                    throw new IllegalArgumentException("Landings and the upper shell require full blocks");
                }
            }
        }
    }

    private SiteStairBlueprint() {}

    public static RoomBlueprint create(int lowerFloorY, Layout layout, Materials materials) {
        return create(lowerFloorY, layout, materials, Blocks.STONE.defaultBlockState());
    }

    public static RoomBlueprint create(int lowerFloorY, Layout layout, Materials materials, BlockState lowerFooting) {
        checkLowerDeck(lowerFloorY);
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(materials, "materials");
        if (!lowerFooting.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
            throw new IllegalArgumentException("The lower-room approach requires full blocks");
        }
        int upperFloorY = lowerFloorY + ConstructionSite.FLOOR_STEP;
        List<ConstructionSite.SiteDoor> upperDoors = upperDoors(upperFloorY);
        Map<BlockPos, Requirement> requirements = new LinkedHashMap<>();
        addStairs(requirements, lowerFloorY, layout.start, materials, lowerFooting);
        addUpperShell(requirements, upperFloorY, upperDoors, materials);

        Bounds bounds = new Bounds(new BlockPos(-OUTER_EDGE, lowerFloorY, -OUTER_EDGE),
                new BlockPos(OUTER_EDGE, upperFloorY + ROOM_CEILING_OFFSET, OUTER_EDGE));
        Bounds interior = new Bounds(new BlockPos(-INNER_EDGE, upperFloorY + 1, -INNER_EDGE),
                new BlockPos(INNER_EDGE, upperFloorY + 4, INNER_EDGE));
        return RoomBlueprint.of(bounds, List.of(interior), requirements,
                upperDoors.stream().map(door -> new Door(door.lower(), door.outward())).toList(), Optional.empty());
    }

    public static Optional<RoomBlueprint> createFitting(int lowerFloorY, Materials materials, BlockState lowerFooting,
                                                       BlockState lowerCeiling,
                                                       Function<BlockPos, BlockState> blocks) {
        if (!hasUpperDeck(lowerFloorY)) {
            return Optional.empty();
        }
        for (Layout layout : Layout.values()) {
            RoomBlueprint candidate = create(lowerFloorY, layout, materials, lowerFooting);
            if (candidate.requirements().entrySet().stream().allMatch(entry ->
                    matchesOrCanReplace(lowerFloorY, entry, lowerCeiling, blocks))) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static boolean matchesOrCanReplace(int lowerFloorY, Map.Entry<BlockPos, Requirement> entry,
                                               BlockState lowerCeiling,
                                               Function<BlockPos, BlockState> blocks) {
        BlockPos pos = entry.getKey();
        BlockState actual = blocks.apply(pos);
        if (ConstructionSite.isReservedSiteBlock(pos)) {
            return false;
        }
        if (actual.isAir() || actual.equals(entry.getValue().expectedState())
                || mayReplaceOriginal(lowerFloorY, pos, actual, lowerCeiling)) {
            return true;
        }
        if (entry.getValue().criterion() != Criterion.DOORWAYS || !actual.is(entry.getValue().expectedState().getBlock())) {
            return false;
        }
        BlockState expected = entry.getValue().expectedState();
        return actual.getValue(DoorBlock.FACING).getAxis() == expected.getValue(DoorBlock.FACING).getAxis()
                && actual.getValue(DoorBlock.HALF) == expected.getValue(DoorBlock.HALF);
    }

    public static List<ConstructionSite.SiteDoor> upperDoors(int upperFloorY) {
        return ConstructionSite.STARTER_DOORS.stream()
                .map(door -> new ConstructionSite.SiteDoor(
                        door.lower().above(upperFloorY - ConstructionSite.START_FLOOR_Y), door.outward()))
                .toList();
    }

    /** Existing deck tiles and the previously built roof can be cut out as marked by the blueprint. */
    public static boolean mayReplaceOriginal(int lowerFloorY, BlockPos pos, BlockState actual,
                                             BlockState lowerCeiling) {
        if (pos.getY() == lowerFloorY + ConstructionSite.FLOOR_STEP) {
            return actual.is(Blocks.STONE);
        }
        if (pos.getY() == lowerFloorY + ROOM_CEILING_OFFSET) {
            return actual.equals(lowerCeiling);
        }
        return lowerFloorY == ConstructionSite.START_FLOOR_Y && pos.equals(new BlockPos(-3, lowerFloorY + 3, -1))
                && actual.equals(Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.EAST));
    }

    private static void checkLowerDeck(int lowerFloorY) {
        if (!hasUpperDeck(lowerFloorY)) {
            throw new IllegalArgumentException("No upper deck for lower-floor Y " + lowerFloorY);
        }
    }

    private static boolean hasUpperDeck(int lowerFloorY) {
        int offset = lowerFloorY - ConstructionSite.START_FLOOR_Y;
        int maxOffset = (ConstructionSite.FLOOR_COUNT - 1) * ConstructionSite.FLOOR_STEP;
        return offset >= 0 && offset < maxOffset && offset % ConstructionSite.FLOOR_STEP == 0;
    }

    private static void addUpperShell(Map<BlockPos, Requirement> requirements, int y,
                                      List<ConstructionSite.SiteDoor> doors, Materials materials) {
        for (int x = -OUTER_EDGE; x <= OUTER_EDGE; x++) {
            for (int z = -OUTER_EDGE; z <= OUTER_EDGE; z++) {
                requirements.putIfAbsent(new BlockPos(x, y, z), new Requirement(materials.roomFloor(), Criterion.FLOOR));
                requirements.put(new BlockPos(x, y + ROOM_CEILING_OFFSET, z),
                        new Requirement(materials.roomCeiling(), Criterion.CEILING));
                if (Math.abs(x) == OUTER_EDGE || Math.abs(z) == OUTER_EDGE) {
                    for (int height = 1; height < ROOM_CEILING_OFFSET; height++) {
                        requirements.put(new BlockPos(x, y + height, z),
                                new Requirement(materials.roomWalls(), Criterion.WALLS));
                    }
                }
            }
        }
        for (ConstructionSite.SiteDoor door : doors) {
            BlockState state = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, door.outward());
            requirements.put(door.lower(), new Requirement(state, Criterion.DOORWAYS));
            requirements.put(door.lower().above(),
                    new Requirement(state.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Criterion.DOORWAYS));
        }
    }

    private static void addStairs(Map<BlockPos, Requirement> requirements, int lowerFloorY, Direction start,
                                  Materials materials, BlockState lowerFooting) {
        // Each flight climbs five bottom-half stairs and meets a full-block landing at the final tread's level.
        int z = -INNER_EDGE * start.getStepZ();
        BlockPos approach = new BlockPos(WEST_LANE + 1, lowerFloorY, z);
        addTread(requirements, approach, lowerFooting);
        clearHeadroom(requirements, approach);
        BlockPos tread = approach.west();
        addTread(requirements, tread, lowerFooting);
        clearHeadroom(requirements, tread);

        Direction flight = start;
        for (int run = 0; run < FLIGHTS; run++) {
            BlockState stair = materials.stairs().setValue(StairBlock.FACING, flight)
                    .setValue(StairBlock.HALF, Half.BOTTOM).setValue(StairBlock.SHAPE, StairsShape.STRAIGHT)
                    .setValue(StairBlock.WATERLOGGED, false);
            for (int step = 0; step < STEPS_PER_FLIGHT; step++) {
                tread = tread.relative(flight).above();
                addTread(requirements, tread, stair);
                clearHeadroom(requirements, tread);
            }

            tread = tread.relative(flight);
            addTread(requirements, tread, run == FLIGHTS - 1 ? materials.roomFloor() : materials.landings());
            clearHeadroom(requirements, tread);
            if (run < FLIGHTS - 1) {
                tread = tread.east();
                addTread(requirements, tread, materials.landings());
                clearHeadroom(requirements, tread);
                flight = flight.getOpposite();
            }
        }
    }

    private static void addTread(Map<BlockPos, Requirement> requirements, BlockPos pos, BlockState state) {
        requirements.put(pos, new Requirement(state, Criterion.STAIRWAYS));
    }

    private static void clearHeadroom(Map<BlockPos, Requirement> requirements, BlockPos tread) {
        Requirement clear = new Requirement(Blocks.AIR.defaultBlockState(), Criterion.CLEARANCE);
        requirements.put(tread.above(), clear);
        requirements.put(tread.above(2), clear);
    }
}
