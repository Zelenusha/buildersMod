package io.github.zelenusha.buildersmod.intro;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class IntroStructures {
    private static final int NEARBY_RADIUS = 36;
    private static final int MAX_SLOPE = 3;

    private IntroStructures() {
    }

    static Sites findSites(ServerLevel level, BlockPos player, IntroData data) {
        List<BlockPos> centers = new ArrayList<>();
        for (int x = -NEARBY_RADIUS; x <= NEARBY_RADIUS; x++) {
            for (int z = -NEARBY_RADIUS; z <= NEARBY_RADIUS; z++) {
                if (x * x + z * z <= NEARBY_RADIUS * NEARBY_RADIUS) {
                    centers.add(player.offset(x, 0, z));
                }
            }
        }
        centers.sort(Comparator.comparingDouble(position -> position.distSqr(player)));
        Map<BlockPos, Optional<BlockPos>> checkedOffices = new HashMap<>();
        for (BlockPos center : centers) {
            if (center.distSqr(player) < 16) {
                continue;
            }
            BlockPos dugout = origin(level, center, 5, 5, 1);
            if (!canPrepare(level, dugout, 5, 5, 4, true, data)) {
                continue;
            }
            BoundingBox dugoutArea = footprint(dugout, 5, 5, 4);
            for (BlockPos officeCenter : centers) {
                if (officeCenter.distSqr(player) < 100 || officeCenter.distSqr(player) <= center.distSqr(player)) {
                    continue;
                }
                Optional<BlockPos> office = checkedOffices.computeIfAbsent(officeCenter, candidate -> {
                    BlockPos position = origin(level, candidate, 13, 11, 0);
                    return canPrepare(level, position, 13, 11, 6, false, data) ? Optional.of(position) : Optional.empty();
                });
                if (office.isPresent()) {
                    BoundingBox officeArea = footprint(office.get(), 13, 11, 6);
                    if (!officeArea.intersects(dugoutArea.minX(), dugoutArea.minZ(), dugoutArea.maxX(), dugoutArea.maxZ())) {
                        return new Sites(dugout, office.get());
                    }
                }
            }
        }
        return null;
    }

    private static BlockPos origin(ServerLevel level, BlockPos center, int width, int depth, int sink) {
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, center.getX(), center.getZ()) - 1;
        return new BlockPos(center.getX() - width / 2, surface - sink, center.getZ() - depth / 2);
    }

    private static BoundingBox footprint(BlockPos origin, int width, int depth, int height) {
        return new BoundingBox(origin.getX(), origin.getY() - MAX_SLOPE, origin.getZ() - 2,
                origin.getX() + width - 1, origin.getY() + height + MAX_SLOPE, origin.getZ() + depth - 1);
    }

    private static boolean canPrepare(ServerLevel level, BlockPos origin, int width, int depth, int height,
                                      boolean dugout, IntroData data) {
        BoundingBox box = footprint(origin, width, depth, height);
        if (level.isOutsideBuildHeight(box.minY()) || level.isOutsideBuildHeight(box.maxY())
                || !level.getWorldBorder().isWithinBounds(new BlockPos(box.minX(), origin.getY(), box.minZ()))
                || !level.getWorldBorder().isWithinBounds(new BlockPos(box.maxX(), origin.getY(), box.maxZ()))) {
            return false;
        }
        int ground = origin.getY() + (dugout ? 1 : 0);
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                if (!isBuildingOrApproach(origin, width, x, z)) {
                    continue;
                }
                int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                if (Math.abs(surface - ground) > MAX_SLOPE) {
                    return false;
                }
                BlockPos groundPosition = new BlockPos(x, surface, z);
                if (!level.getBlockState(groundPosition).isFaceSturdy(level, groundPosition, Direction.UP)) {
                    return false;
                }
                boolean approach = z < origin.getZ();
                BlockPos foundation = new BlockPos(x, origin.getY() - 1, z);
                int lowestChange = approach
                        ? approachGround(ground, width, x - origin.getX(), z - origin.getZ(), dugout) : origin.getY();
                if (!approach && !supportsFloor(level, foundation)) {
                    lowestChange = foundation.getY();
                }
                for (int y = Math.min(lowestChange, surface + 1); y <= box.maxY(); y++) {
                    BlockPos position = new BlockPos(x, y, z);
                    BlockState current = level.getBlockState(position);
                    if (data.isProtected(position) || current.hasBlockEntity()
                            || !current.getFluidState().isEmpty() || !naturalTerrain(current)) {
                        return false;
                    }
                }
            }
        }

        BlockPos front = origin.offset(width / 2, 0, -3);
        int frontY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, front.getX(), front.getZ()) - 1;
        BlockPos landing = new BlockPos(front.getX(), frontY, front.getZ());
        if (Math.abs(frontY - ground) > 1
                || !level.getBlockState(landing).isFaceSturdy(level, landing, Direction.UP)
                || !level.getFluidState(landing.above()).isEmpty()
                || !level.getFluidState(landing.above(2)).isEmpty()
                || !level.getBlockState(landing.above()).getCollisionShape(level, landing.above()).isEmpty()
                || !level.getBlockState(landing.above(2)).getCollisionShape(level, landing.above(2)).isEmpty()) {
            return false;
        }
        for (int x = box.minX() >> 4; x <= box.maxX() >> 4; x++) {
            for (int z = box.minZ() >> 4; z <= box.maxZ() >> 4; z++) {
                if (level.structureManager().startsForStructure(new ChunkPos(x, z), structure -> true)
                        .stream().anyMatch(start -> start.getBoundingBox().intersects(box))) {
                    return false;
                }
            }
        }
        AABB building = new AABB(origin.offset(0, -1, -2), origin.offset(width, height, depth));
        return level.players().stream().noneMatch(player -> player.getBoundingBox().intersects(building));
    }

    private static boolean isBuildingOrApproach(BlockPos origin, int width, int x, int z) {
        return z >= origin.getZ() || Math.abs(x - (origin.getX() + width / 2)) <= 1;
    }

    private static int approachGround(int ground, int width, int x, int z, boolean dugout) {
        // Reach the interior floor level before getting to the low lintel.
        return dugout && x == width / 2 && z == -1 ? ground - 1 : ground;
    }

    private static boolean naturalTerrain(BlockState state) {
        return state.isAir() || state.canBeReplaced() || state.is(BlockTags.DIRT)
                || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.SAND)
                || state.is(Blocks.GRAVEL) || state.is(Blocks.SNOW_BLOCK)
                || state.is(Blocks.SNOW) || state.is(Blocks.PODZOL) || state.is(Blocks.MYCELIUM);
    }

    static void build(ServerLevel level, Sites sites) {
        buildDugout(level, sites.dugout());
        buildOffice(level, sites.office());
    }

    private static void prepare(ServerLevel level, BlockPos origin, int width, int depth, int height, boolean dugout) {
        int ground = origin.getY() + (dugout ? 1 : 0);
        for (int x = 0; x < width; x++) {
            for (int z = -2; z < depth; z++) {
                if (!isBuildingOrApproach(origin, width, origin.getX() + x, origin.getZ() + z)) {
                    continue;
                }
                BlockPos column = origin.offset(x, 0, z);
                int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ()) - 1;
                int target = z < 0 ? approachGround(ground, width, x, z, dugout) : origin.getY() - 1;
                BlockPos foundation = new BlockPos(column.getX(), target, column.getZ());
                int fillFrom = z < 0 || supportsFloor(level, foundation) ? surface + 1 : Math.min(surface + 1, target);
                for (int y = fillFrom; y <= target; y++) {
                    level.setBlock(new BlockPos(column.getX(), y, column.getZ()), Blocks.DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
                for (int y = target + 1; y <= origin.getY() + height + MAX_SLOPE; y++) {
                    level.setBlock(new BlockPos(column.getX(), y, column.getZ()), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
                if (z < 0) {
                    level.setBlock(new BlockPos(column.getX(), target, column.getZ()), Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    private static boolean supportsFloor(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getFluidState().isEmpty() && state.isFaceSturdy(level, pos, Direction.UP);
    }

    private static void buildDugout(ServerLevel level, BlockPos origin) {
        prepare(level, origin, 5, 5, 4, true);
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                place(level, origin.offset(x, 0, z), Blocks.DIRT.defaultBlockState());
                place(level, origin.offset(x, 3, z), Blocks.GRASS_BLOCK.defaultBlockState());
                if (x == 0 || x == 4 || z == 0 || z == 4) {
                    if (x != 2 || z != 0) {
                        place(level, origin.offset(x, 1, z), Blocks.DIRT.defaultBlockState());
                        place(level, origin.offset(x, 2, z), Blocks.DIRT.defaultBlockState());
                    }
                }
            }
        }
        placeDoor(level, origin.offset(2, 1, 0));
        place(level, origin.offset(2, 1, -2), dugoutEntranceStair());
        BlockState bed = Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH);
        place(level, origin.offset(1, 1, 2), bed.setValue(BedBlock.PART, BedPart.FOOT));
        place(level, origin.offset(1, 1, 3), bed.setValue(BedBlock.PART, BedPart.HEAD));
        place(level, origin.offset(3, 1, 3), Blocks.TORCH.defaultBlockState());
    }

    static void repairLegacyDugoutEntrance(ServerLevel level, BlockPos origin, IntroData data) {
        if (origin == null) {
            return;
        }
        BlockPos door = origin.offset(2, 1, 0);
        BlockPos oldStair = door.north();
        BlockPos newStair = door.north(2);
        if (!level.isLoaded(door) || !level.isLoaded(newStair) || !level.isLoaded(oldStair)
                || level.getBlockState(oldStair) != dugoutEntranceStair()
                || !level.getBlockState(newStair).is(Blocks.GRASS_BLOCK)
                || !level.getBlockState(door).is(Blocks.OAK_DOOR) || !level.getBlockState(door.above()).is(Blocks.OAK_DOOR)
                || !level.getBlockState(door.below()).is(Blocks.DIRT)
                || !supportsFloor(level, oldStair.below())
                || !level.isEmptyBlock(oldStair.above()) || !level.isEmptyBlock(newStair.above())
                || data.isProtected(oldStair) || data.isProtected(newStair)) {
            return;
        }
        place(level, newStair, dugoutEntranceStair());
        place(level, oldStair, Blocks.AIR.defaultBlockState());
    }

    private static BlockState dugoutEntranceStair() {
        return Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH);
    }

    private static void buildOffice(ServerLevel level, BlockPos origin) {
        prepare(level, origin, 13, 11, 6, false);
        for (int x = 0; x < 13; x++) {
            for (int z = 0; z < 11; z++) {
                place(level, origin.offset(x, 0, z), Blocks.COBBLESTONE.defaultBlockState());
                place(level, origin.offset(x, 5, z), Blocks.OAK_PLANKS.defaultBlockState());
                boolean boundary = x == 0 || x == 12 || z == 0 || z == 10;
                boolean divider = z == 6 && (x == 1 || x == 2 || x == 5 || x == 6 || x == 9 || x == 11);
                if (boundary || divider) {
                    for (int y = 1; y <= 4; y++) {
                        boolean door = x == 6 && z == 0 && y <= 2;
                        boolean post = (x == 0 || x == 12) && (z == 0 || z == 10);
                        boolean window = y == 2 && ((x == 0 || x == 12) && (z == 3 || z == 7)
                                || (z == 0 || z == 10) && (x == 3 || x == 9));
                        if (!door) {
                            BlockState state = window ? Blocks.GLASS.defaultBlockState()
                                    : (post ? Blocks.OAK_LOG : Blocks.OAK_PLANKS).defaultBlockState();
                            place(level, origin.offset(x, y, z), state);
                        }
                    }
                }
            }
        }
        placeDoor(level, origin.offset(6, 1, 0));
        for (int x = 4; x <= 6; x++) {
            place(level, origin.offset(x, 1, 4), Blocks.OAK_PLANKS.defaultBlockState());
        }
        place(level, origin.offset(2, 1, 2), Blocks.TORCH.defaultBlockState());
        place(level, origin.offset(10, 1, 2), Blocks.TORCH.defaultBlockState());
        place(level, origin.offset(2, 1, 8), Blocks.TORCH.defaultBlockState());
        place(level, origin.offset(10, 1, 9), Blocks.TORCH.defaultBlockState());
    }

    private static void placeDoor(ServerLevel level, BlockPos pos) {
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
        place(level, pos, lower);
        place(level, pos.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    private static void place(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    static BlockPos officePortalFrame(BlockPos office) {
        return office.offset(7, 0, 8);
    }

    static BlockPos officeArrival(BlockPos office) {
        return office.offset(8, 1, 5);
    }

    record Sites(BlockPos dugout, BlockPos office) {
    }
}
