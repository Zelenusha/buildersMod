package io.github.zelenusha.buildersmod.blueprint;

import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Bounds;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Bonus;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Criterion;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Door;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Requirement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Fixed reference layout for FR-96 and FR-98. */
public final class SampleRoomBlueprint {
    private static final int OUTER_WIDTH = 7;
    private static final int WALL_HEIGHT = 4;
    private static final int WALL_CENTER = OUTER_WIDTH / 2;
    private static final int FAR_WALL = OUTER_WIDTH - 1;

    private SampleRoomBlueprint() {
    }

    /** The outward axis points from the existing lower door block into the new room. */
    public static RoomBlueprint at(BlockPos connectingDoorLower, Direction outward) {
        if (!outward.getAxis().isHorizontal()) {
            throw new IllegalArgumentException("A room must connect through a horizontal doorway");
        }

        Direction right = outward.getClockWise();
        BlockPos origin = connectingDoorLower.relative(outward).below().relative(right.getOpposite(), WALL_CENTER);
        Map<BlockPos, Requirement> requirements = new LinkedHashMap<>();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState oak = Blocks.OAK_PLANKS.defaultBlockState();
        BlockState torch = Blocks.TORCH.defaultBlockState();

        for (int z = 0; z < OUTER_WIDTH; z++) {
            for (int x = 0; x < OUTER_WIDTH; x++) {
                BlockPos floor = position(origin, outward, right, x, 0, z);
                requirements.put(floor, new Requirement(stone, Criterion.FLOOR));
                requirements.put(floor.above(WALL_HEIGHT + 1), new Requirement(oak, Criterion.CEILING));
                if (x == 0 || x == FAR_WALL || z == 0 || z == FAR_WALL) {
                    for (int y = 1; y <= WALL_HEIGHT; y++) {
                        requirements.put(floor.above(y), new Requirement(oak, Criterion.WALLS));
                    }
                }
            }
        }

        List<Door> doors = List.of(
                new Door(position(origin, outward, right, WALL_CENTER, 1, 0), outward.getOpposite()),
                new Door(position(origin, outward, right, WALL_CENTER, 1, FAR_WALL), outward),
                new Door(position(origin, outward, right, 0, 1, WALL_CENTER), right.getOpposite()),
                new Door(position(origin, outward, right, FAR_WALL, 1, WALL_CENTER), right));
        for (Door door : doors) {
            BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
                    .setValue(DoorBlock.FACING, door.outward())
                    .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
            requirements.put(door.lowerPosition(), new Requirement(lower, Criterion.DOORWAYS));
            requirements.put(door.lowerPosition().above(),
                    new Requirement(lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Criterion.DOORWAYS));
        }

        requirements.put(position(origin, outward, right, 1, 1, 1), new Requirement(torch, Criterion.LIGHTING));
        requirements.put(position(origin, outward, right, 5, 1, 5), new Requirement(torch, Criterion.LIGHTING));
        requirements.put(position(origin, outward, right, 1, 1, 5),
                new Requirement(Blocks.CRAFTING_TABLE.defaultBlockState(), Criterion.FUNCTIONAL_BLOCKS));

        Bounds bounds = boundsBetween(origin, position(origin, outward, right, FAR_WALL, WALL_HEIGHT + 1, FAR_WALL));
        Bounds interior = boundsBetween(position(origin, outward, right, 1, 1, 1),
                position(origin, outward, right, FAR_WALL - 1, WALL_HEIGHT, FAR_WALL - 1));
        return new RoomBlueprint(bounds, List.of(interior), requirements, doors, Optional.of(new Bonus(Blocks.CAKE, 3, 5)));
    }

    private static BlockPos position(BlockPos origin, Direction outward, Direction right, int x, int y, int z) {
        return origin.relative(right, x).above(y).relative(outward, z);
    }

    private static Bounds boundsBetween(BlockPos first, BlockPos second) {
        BlockPos min = new BlockPos(Math.min(first.getX(), second.getX()), Math.min(first.getY(), second.getY()),
                Math.min(first.getZ(), second.getZ()));
        BlockPos max = new BlockPos(Math.max(first.getX(), second.getX()), Math.max(first.getY(), second.getY()),
                Math.max(first.getZ(), second.getZ()));
        return new Bounds(min, max);
    }
}
