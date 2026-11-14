package io.github.zelenusha.buildersmod.contract;

import com.mojang.authlib.GameProfile;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint;
import io.github.zelenusha.buildersmod.blueprint.SampleRoomBlueprint;
import io.github.zelenusha.buildersmod.site.ConstructionSite;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("buildersmod")
@PrefixGameTestTemplate(false)
public class DemolitionGameTests {
    @GameTest(template = "empty")
    public static void restoresTheReplacedDeckWithoutClosingClearance(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        BlockPos anchor = new BlockPos(origin.getX(), ConstructionSite.START_FLOOR_Y + 1, origin.getZ());
        RoomBlueprint room = SampleRoomBlueprint.at(anchor, Direction.NORTH);
        BlockPos floor = room.bounds().min();
        BlockPos cutout = floor.above(2);
        BlockPos wall = floor.above();
        level.setBlock(floor, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(cutout, Blocks.STONE_BRICKS.defaultBlockState(), Block.UPDATE_ALL);
        DemolitionWork work = new DemolitionWork(order(anchor, room, DemolitionWork.snapshot(level, room)));

        level.setBlock(floor, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
        level.removeBlock(cutout, false);
        level.setBlock(wall, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
        helper.assertTrue(!work.readyToDeliver(level), "A floor replacement and wall must prevent delivery");
        level.removeBlock(wall, false);
        level.removeBlock(floor, false);
        helper.assertTrue(!work.readyToDeliver(level), "Air cannot replace the original deck block");
        level.setBlock(floor, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        helper.assertTrue(work.readyToDeliver(level), "Restoring the original deck should allow delivery with an open cutout");
        helper.assertTrue(level.getBlockState(cutout).isAir(), "Delivery validation must preserve the open cutout");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void recordsActualLootAndAcceptsReplacementInventory(GameTestHelper helper) {
        ServerLevel site = helper.getLevel().getServer().getLevel(ConstructionSite.SITE_LEVEL);
        BlockPos anchor = new BlockPos(263, ConstructionSite.START_FLOOR_Y + 1, 259);
        RoomBlueprint room = SampleRoomBlueprint.at(anchor, Direction.NORTH);
        var player = FakePlayerFactory.get(site, new GameProfile(UUID.randomUUID(), "demolition-test"));
        var account = ContractData.get(site.getServer()).account(player.getUUID());
        BlockPos wall = room.bounds().min().above();
        BlockPos window = wall.above();
        DemolitionWork work = new DemolitionWork(order(anchor, room, DemolitionWork.snapshot(site, room)));
        account.failed.add(work);
        try {
            site.setBlock(wall, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
            site.setBlock(window, Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL);
            helper.assertTrue(player.gameMode.destroyBlock(wall), "Breaking planks must succeed");
            helper.assertTrue(player.gameMode.destroyBlock(window), "Breaking glass without silk touch must succeed");
            helper.assertTrue(work.readyToDeliver(site), "Both room blocks have been dismantled");
            helper.assertTrue(work.materials().equals(Map.of(Items.OAK_PLANKS, 1)), "Only the spawned plank is owed");

            DemolitionWork restored = DemolitionWork.load(work.order(), work.save());
            helper.assertTrue(restored.id().equals(work.id()), "The task ID survives reload");
            var inventory = player.getInventory();
            inventory.setItem(0, new ItemStack(Items.BIRCH_PLANKS, 2));
            helper.assertTrue(!restored.deliver(inventory), "Different item types are rejected");
            inventory.setItem(1, new ItemStack(Items.OAK_PLANKS, 2));
            helper.assertTrue(restored.deliver(inventory), "Equivalent planks from inventory satisfy the debt");
            helper.assertTrue(inventory.getItem(0).getCount() == 2 && inventory.getItem(1).getCount() == 1,
                    "Delivery consumes only the owed quantity and type");
        } finally {
            account.failed.remove(work);
        }
        helper.succeed();
    }

    private static ContractData.Accepted order(BlockPos anchor, RoomBlueprint blueprint, Map<BlockPos, BlockState> originals) {
        return new ContractData.Accepted(anchor, Direction.NORTH, ContractBalance.Difficulty.ONE, 0, false,
                blueprint, 5, ContractData.Kind.ROOM, 0, 0, originals);
    }
}
