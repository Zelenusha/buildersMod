package io.github.zelenusha.buildersmod.maintenance;

import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint;
import io.github.zelenusha.buildersmod.blueprint.SampleRoomBlueprint;
import io.github.zelenusha.buildersmod.contract.ContractBalance.Difficulty;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("buildersmod")
@PrefixGameTestTemplate(false)
public class FloodWorkGameTests {
    @GameTest(template = "empty")
    public static void floodRequiresDrainingWaterloggedBlocksAndRemovingRecordedFill(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RoomBlueprint room = SampleRoomBlueprint.at(helper.absolutePos(new BlockPos(0, 5, 0)), Direction.EAST);
        for (BlockPos pos : room.bounds().positions()) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        BlockPos source = room.interior().min().east().south();
        BlockPos waterlogged = source.east();
        BlockPos fill = source.south();
        Difficulty difficulty = Difficulty.TWO;
        IncidentState flood = IncidentState.flood(UUID.randomUUID(), difficulty, false, room.bounds().min(), List.of(source), 42L);

        helper.assertTrue(IncidentWorld.applyBlocks(level, room, flood), "Flood should place its water source in the empty room");
        helper.assertTrue(level.getFluidState(source).is(FluidTags.WATER) && level.getFluidState(source).isSource(),
                "The incident places an actual water source");
        helper.assertTrue(!IncidentWorld.roomHasNoWater(level, room), "Visible water prevents submission");

        level.setBlock(source, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(waterlogged, Blocks.STONE_BRICK_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, true), Block.UPDATE_CLIENTS);
        helper.assertTrue(!IncidentWorld.roomHasNoWater(level, room), "Waterlogged blocks must also be drained");

        level.setBlock(waterlogged, Blocks.STONE_BRICK_SLAB.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(fill, Blocks.DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);
        helper.assertTrue(flood.recordFloodFill(fill), "Temporary fill is recorded for the incident");
        flood = IncidentState.load(flood.save());
        helper.assertTrue(IncidentWorld.roomHasNoWater(level, room), "The room has no water after drainage");
        helper.assertTrue(flood.submitFlood(key -> IncidentWorld.roomHasNoWater(level, room),
                pos -> IncidentWorld.floodFillRemoved(level, pos)).isEmpty(), "Recorded fill prevents submission after drainage");

        level.setBlock(fill, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        helper.assertTrue(flood.submitFlood(key -> IncidentWorld.roomHasNoWater(level, room),
                pos -> IncidentWorld.floodFillRemoved(level, pos)).orElseThrow() == difficulty.emeralds(),
                "A drained and cleared room pays the configured wage");
        helper.assertTrue(flood.submitFlood(key -> true, pos -> true).isEmpty(), "The same flood cannot pay a second wage");
        helper.succeed();
    }
}
