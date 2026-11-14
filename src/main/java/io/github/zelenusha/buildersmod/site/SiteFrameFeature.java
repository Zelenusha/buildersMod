package io.github.zelenusha.buildersmod.site;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

final class SiteFrameFeature extends Feature<NoneFeatureConfiguration> {
    private static final int CORNER = 4;
    private static final int FLOOR_Y = ConstructionSite.START_FLOOR_Y;
    private static final int CEILING_Y = FLOOR_Y + 5;
    private static final int TOP_DECK_Y = FLOOR_Y + ConstructionSite.FLOOR_STEP * (ConstructionSite.FLOOR_COUNT - 1);
    private static final int COLUMN_SPACING = 16;
    private static final int COLUMN_OFFSET = 7;
    private static final int COLUMN_WIDTH = 2;
    private static final BlockState BRICKS = Blocks.STONE_BRICKS.defaultBlockState();

    SiteFrameFeature() {
        super(NoneFeatureConfiguration.CODEC);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        BlockPos origin = context.origin();
        placeColumns(level, origin);
        if (origin.getX() >= -16 && origin.getX() <= 0 && origin.getZ() >= -16 && origin.getZ() <= 0) {
            placeStarterRoom(level, origin);
        }
        return true;
    }

    static boolean isColumnPosition(int x, int y, int z) {
        return y > FLOOR_Y && y < TOP_DECK_Y
                && Math.floorMod(y - FLOOR_Y, ConstructionSite.FLOOR_STEP) != 0
                && isColumnAxis(x) && isColumnAxis(z);
    }

    private static boolean isColumnAxis(int position) {
        return Math.floorMod(position - COLUMN_OFFSET, COLUMN_SPACING) < COLUMN_WIDTH;
    }

    private static void placeColumns(WorldGenLevel level, BlockPos origin) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = origin.getX() + COLUMN_OFFSET; x < origin.getX() + COLUMN_OFFSET + COLUMN_WIDTH; x++) {
            for (int z = origin.getZ() + COLUMN_OFFSET; z < origin.getZ() + COLUMN_OFFSET + COLUMN_WIDTH; z++) {
                for (int y = FLOOR_Y + 1; y < TOP_DECK_Y; y++) {
                    if (isColumnPosition(x, y, z)) {
                        level.setBlock(pos.set(x, y, z), BRICKS, Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
    }

    private static void placeStarterRoom(WorldGenLevel level, BlockPos origin) {
        for (int x = Math.max(-CORNER, origin.getX()); x <= Math.min(CORNER, origin.getX() + 15); x++) {
            for (int z = Math.max(-CORNER, origin.getZ()); z <= Math.min(CORNER, origin.getZ() + 15); z++) {
                level.setBlock(new BlockPos(x, CEILING_Y, z), BRICKS, Block.UPDATE_CLIENTS);
                if (Math.abs(x) == CORNER || Math.abs(z) == CORNER) {
                    for (int y = FLOOR_Y + 1; y < CEILING_Y; y++) {
                        level.setBlock(new BlockPos(x, y, z), BRICKS, Block.UPDATE_CLIENTS);
                    }
                }
            }
        }

        for (ConstructionSite.SiteDoor door : ConstructionSite.STARTER_DOORS) {
            if (inChunk(origin, door.lower())) {
                BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, door.outward());
                level.setBlock(door.lower(), lower, Block.UPDATE_CLIENTS);
                level.setBlock(door.lower().above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_CLIENTS);
            }
        }

        placeTorch(level, origin, new BlockPos(0, FLOOR_Y + 3, -3), Direction.SOUTH);
        placeTorch(level, origin, new BlockPos(3, FLOOR_Y + 3, 2), Direction.WEST);
        if (inChunk(origin, ConstructionSite.RETURN_PORTAL.lowerLeft())) {
            ConstructionSite.placePortal(level, ConstructionSite.RETURN_PORTAL);
        }
    }

    private static void placeTorch(WorldGenLevel level, BlockPos origin, BlockPos pos, Direction facing) {
        if (inChunk(origin, pos)) {
            level.setBlock(pos, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, facing), Block.UPDATE_CLIENTS);
        }
    }

    private static boolean inChunk(BlockPos origin, BlockPos pos) {
        return (pos.getX() & ~15) == origin.getX() && (pos.getZ() & ~15) == origin.getZ();
    }
}
