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
import net.minecraft.nbt.CompoundTag;
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

    @GameTest(template = "empty")
    public static void deliversTheSecondDemolitionBeforeTheFirst(GameTestHelper helper) {
        ServerLevel site = helper.getLevel().getServer().getLevel(ConstructionSite.SITE_LEVEL);
        BlockPos firstAnchor = new BlockPos(327, ConstructionSite.START_FLOOR_Y + 1, 259);
        BlockPos secondAnchor = firstAnchor.east(32);
        RoomBlueprint firstRoom = SampleRoomBlueprint.at(firstAnchor, Direction.NORTH);
        RoomBlueprint secondRoom = SampleRoomBlueprint.at(secondAnchor, Direction.NORTH);
        var player = FakePlayerFactory.get(site, new GameProfile(UUID.randomUUID(), "demolition-order"));
        ContractData data = ContractData.get(site.getServer());
        var account = data.account(player.getUUID());
        var first = new DemolitionWork(order(firstAnchor, firstRoom, DemolitionWork.snapshot(site, firstRoom)));
        var second = new DemolitionWork(order(secondAnchor, secondRoom, DemolitionWork.snapshot(site, secondRoom)));
        account.failed.add(first);
        account.failed.add(second);
        BlockPos firstWall = firstRoom.bounds().min().above();
        BlockPos glass = firstWall.above();
        BlockPos secondWall = secondRoom.bounds().min().above();
        try {
            site.setBlock(firstWall, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
            site.setBlock(glass, Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL);
            site.setBlock(secondWall, Blocks.BIRCH_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
            helper.assertTrue(player.gameMode.destroyBlock(firstWall), "The first task's wood must drop");
            helper.assertTrue(player.gameMode.destroyBlock(secondWall), "The second task's wood must drop");
            helper.assertTrue(second.readyToDeliver(site), "The second demolition can be submitted while the first has glass");
            helper.assertTrue(first.materials().equals(Map.of(Items.OAK_PLANKS, 1)), "The first task owes one oak plank");
            helper.assertTrue(second.materials().equals(Map.of(Items.BIRCH_PLANKS, 1)), "The second task owes one birch plank");

            var inventory = player.getInventory();
            inventory.setItem(0, new ItemStack(Items.OAK_PLANKS));
            inventory.setItem(1, new ItemStack(Items.BIRCH_PLANKS, 2));
            ContractEvents.deliverDemolition(player, data, account, second.id());
            helper.assertTrue(account.failed.size() == 1 && account.failed.get(0).id().equals(first.id()),
                    "Submitting the second task leaves the first active");
            helper.assertTrue(inventory.getItem(0).getCount() == 1 && inventory.getItem(1).getCount() == 1,
                    "Submitting the second task consumes its material only");
            ContractEvents.deliverDemolition(player, data, account, second.id());
            helper.assertTrue(inventory.getItem(1).getCount() == 1, "Repeating the second submission consumes nothing");

            helper.assertTrue(player.gameMode.destroyBlock(glass), "The first room must be fully dismantled before delivery");
            ContractEvents.deliverDemolition(player, data, account, first.id());
            helper.assertTrue(account.failed.isEmpty() && inventory.getItem(0).isEmpty(), "The first task can then be completed");
        } finally {
            account.failed.clear();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void expirationAndCancellationCreateIndependentDemolitions(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        ServerLevel site = server.getLevel(ConstructionSite.SITE_LEVEL);
        var player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "deadline-cancel"));
        var data = new ContractData();
        var account = data.account(player.getUUID());
        long now = data.time(server);
        var expired = placeRoom(site, new BlockPos(413, ConstructionSite.START_FLOOR_Y + 1, 295), now, 0);
        var cancelled = placeRoom(site, expired.anchor().east(32), now + ContractBalance.TICKS_PER_DAY, 1);
        helper.assertTrue(expired.blueprint().check(site).complete() && cancelled.blueprint().check(site).complete(),
                "The test starts with two complete physical rooms");
        expired.blueprint().requirements().entrySet().stream()
                .filter(entry -> entry.getValue().criterion() == RoomBlueprint.Criterion.WALLS)
                .filter(entry -> entry.getKey().getY() > expired.anchor().getY())
                .limit(11).forEach(entry -> site.removeBlock(entry.getKey(), false));
        account.offers[0] = expired;
        account.offers[1] = cancelled;
        account.active = expired;

        helper.assertTrue(ContractEvents.checkDeadline(player, data, account), "The deadline checks the room from the Overworld");
        helper.assertTrue(account.active == null && account.offers[0] == null && account.offers[1] == cancelled,
                "Failure releases the building slot and only removes the failed offer");
        helper.assertTrue(account.failed.size() == 1 && account.failed.get(0).order().equals(expired),
                "The incomplete room becomes a demolition task");
        helper.assertTrue(account.fines.entries().size() == 1 && account.fines.entries().get(0).amount() == 4,
                "Eleven missing positions in one criterion incur four emeralds");
        helper.assertTrue(!ContractEvents.checkDeadline(player, data, account), "Repeating the expiry cannot process the old order");

        account.active = cancelled;
        ContractEvents.cancel(player, data, account);
        ContractEvents.cancel(player, data, account);
        helper.assertTrue(account.active == null && account.failed.size() == 2 && account.fines.entries().size() == 2,
                "Cancellation adds one demolition and one fine alongside the earlier ones");
        helper.assertTrue(account.failed.get(1).order().equals(cancelled) && account.fines.entries().get(1).amount() == 3,
                "Cancelling a finished room before its deadline incurs the fixed fine");
        helper.assertTrue(account.completed == 0 && account.unpaid == 0, "Neither failure creates a successful completion or salary");

        var restored = ContractData.load(data.save(new CompoundTag())).account(player.getUUID());
        helper.assertTrue(restored.failed.stream().map(DemolitionWork::id).toList()
                        .equals(account.failed.stream().map(DemolitionWork::id).toList()),
                "Both demolition assignments keep their independent IDs across reload");
        helper.assertTrue(restored.fines.entries().equals(account.fines.entries()), "The two separate fines survive reload");
        helper.succeed();
    }

    private static ContractData.Accepted placeRoom(ServerLevel site, BlockPos anchor, long deadline, int slot) {
        RoomBlueprint blueprint = SampleRoomBlueprint.at(anchor, Direction.NORTH);
        Map<BlockPos, BlockState> baseline = DemolitionWork.snapshot(site, blueprint);
        blueprint.requirements().forEach((position, requirement) ->
                site.setBlock(position, requirement.expectedState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE));
        return new ContractData.Accepted(anchor, Direction.NORTH, ContractBalance.Difficulty.ONE, deadline, false,
                blueprint, 5, ContractData.Kind.ROOM, slot, 0, baseline);
    }

    private static ContractData.Accepted order(BlockPos anchor, RoomBlueprint blueprint, Map<BlockPos, BlockState> originals) {
        return new ContractData.Accepted(anchor, Direction.NORTH, ContractBalance.Difficulty.ONE, 0, false,
                blueprint, 5, ContractData.Kind.ROOM, 0, 0, originals);
    }
}
