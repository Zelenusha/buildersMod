package io.github.zelenusha.buildersmod.contract;

import com.mojang.authlib.GameProfile;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Bounds;
import io.github.zelenusha.buildersmod.blueprint.SampleRoomBlueprint;
import io.github.zelenusha.buildersmod.intro.IntroFixtures;
import io.github.zelenusha.buildersmod.site.ConstructionSite;
import io.github.zelenusha.buildersmod.site.ConstructionSite.SiteDoor;
import io.netty.buffer.Unpooled;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("buildersmod")
@PrefixGameTestTemplate(false)
public class ConstructionFlowGameTests {
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void acceptsChecksAndPaysOneRoomWithoutReplacingOtherOffers(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        ServerLevel site = server.getLevel(ConstructionSite.SITE_LEVEL);
        helper.assertTrue(site != null, "The construction dimension is available");
        FakePlayer player = new FakePlayer(server.overworld(), new GameProfile(UUID.randomUUID(), "contract-test"));
        long now = Math.floorDiv(server.overworld().getDayTime(), ContractBalance.TICKS_PER_DAY);
        BlockPos office = helper.absolutePos(BlockPos.ZERO);
        IntroFixtures.setEmployment(player, office, now + 1);
        ContractData data = new ContractData();
        ContractData.Account account = data.account(player.getUUID());
        SiteDoor start = ConstructionSite.STARTER_DOORS.get(0);
        RoomBlueprint suggested = SampleRoomBlueprint.at(start.lower(), start.outward());
        for (int slot = 0; slot < account.offers.length; slot++) {
            account.offers[slot] = new ContractData.Accepted(start.lower(), start.outward(), ContractBalance.Difficulty.ONE, 0,
                    false, suggested, 6 + slot, ContractData.Kind.SAMPLE, slot, 0, Map.of());
        }
        var displayed = account.offers.clone();

        ContractEvents.accept(player, data, account, 0);
        helper.assertTrue(account.active == null, "Employment does not expose proposals before the first offer day");
        IntroFixtures.setEmployment(player, office, now);
        Bounds bounds = suggested.bounds();
        ContractEvents.withLoadedArea(site, new Bounds(bounds.min().offset(-2, 0, -2), bounds.max().offset(2, 0, 2)), () -> {
            verifyConstruction(helper, site, player, data, account, displayed, suggested);
            return null;
        });
        helper.succeed();
    }

    private static void verifyConstruction(GameTestHelper helper, ServerLevel site, FakePlayer player, ContractData data,
                                           ContractData.Account account, ContractData.Accepted[] displayed,
                                           RoomBlueprint suggested) {
        ContractEvents.accept(player, data, account, 0);
        ContractData.Accepted accepted = account.active;
        helper.assertTrue(accepted != null && accepted.slot() == 0 && accepted.blueprint().bounds().equals(suggested.bounds()),
                "The first offer binds to its compatible starter-room doorway");
        helper.assertTrue(accepted.deadline() - data.time(player.server) == ContractBalance.Difficulty.ONE.duration(),
                "Acceptance starts the full difficulty-specific deadline");
        ContractEvents.accept(player, data, account, 1);
        helper.assertTrue(account.active == accepted && account.offers[1] == displayed[1],
                "Another construction cannot replace the active order or its own offer");
        ContractEvents.submit(player, data, account);
        helper.assertTrue(account.active == accepted && account.unpaid == 0 && account.completed == 0,
                "Incomplete construction cannot be submitted or paid");

        Map<BlockPos, BlockState> previous = new HashMap<>();
        RoomBlueprint blueprint = accepted.blueprint();
        try {
            blueprint.requirements().forEach((pos, requirement) -> {
                previous.put(pos, site.getBlockState(pos));
                site.setBlock(pos, requirement.expectedState(), Block.UPDATE_CLIENTS);
            });
            BlockPos base = blueprint.interior().min().east().south();
            for (BlockPos pos : new BlockPos[] {base, base.east(), base.south()}) {
                previous.putIfAbsent(pos, site.getBlockState(pos));
                site.setBlock(pos, Blocks.CAKE.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            helper.assertTrue(blueprint.check(site).complete() && blueprint.check(site).bonusEmeralds() == 5,
                    "The reference room is complete with the three-cake bonus");
            verifyForemanDetails(helper, accepted, blueprint.check(site));

            // Seed the actual player RNG so the foreman's independent 10% fine roll does not alter this wage scenario.
            helper.assertTrue(RandomSource.create(1L).nextInt(10) != 0, "This seed must leave the wage untaxed");
            player.getRandom().setSeed(1L);
            ContractEvents.submit(player, data, account);
            int expectedWage = 6 + 5 + 3;
            helper.assertTrue(account.active == null && account.completed == 1 && account.rooms.size() == 1,
                    "Successful submission records the completed room exactly once");
            helper.assertTrue(account.unpaid == expectedWage && account.fines.entries().isEmpty(),
                    "Outstanding wage is six base, five for cakes and three for early submission");
            helper.assertTrue(account.offers[0] == null, "Only the completed order's slot is invalidated");
            for (int slot = 1; slot < account.offers.length; slot++) {
                helper.assertTrue(account.offers[slot] == displayed[slot], "Other proposals must retain their original cards");
            }

            ContractData restored = ContractData.load(data.save(new CompoundTag()));
            ContractData.Account afterReload = restored.account(player.getUUID());
            helper.assertTrue(afterReload.unpaid == expectedWage, "The wage survives saving before collection");
            ContractEvents.claim(player, restored, afterReload);
            helper.assertTrue(afterReload.unpaid == 0 && carriedEmeralds(player) == expectedWage, "Claim transfers the exact wage");
            ContractEvents.submit(player, restored, afterReload);
            ContractEvents.claim(player, restored, afterReload);
            helper.assertTrue(afterReload.completed == 1 && afterReload.unpaid == 0 && carriedEmeralds(player) == expectedWage,
                    "Repeated submission and collection cannot pay the same room a second time");
        } finally {
            previous.forEach((pos, state) -> site.setBlock(pos, state, Block.UPDATE_CLIENTS));
        }
    }

    private static void verifyForemanDetails(GameTestHelper helper, ContractData.Accepted order, RoomBlueprint.BlueprintCheck check) {
        var offer = ContractEvents.card(order, null, order.difficulty().duration(), false, false, "Нет свободного подходящего проёма");
        var active = ContractEvents.card(order, check, order.difficulty().duration(), true, true, "");
        helper.assertTrue(offer.conditions().contains("Внутренние размеры 5×5 блоков")
                        && offer.conditions().contains("Дверных проёмов 4")
                        && offer.conditions().contains("Источников света 2"),
                "The foreman provides structural conditions before acceptance");
        var doors = active.materials().stream().filter(row -> row.label().equals(Items.OAK_DOOR.getDescriptionId()))
                .findFirst().orElseThrow();
        helper.assertTrue(doors.needed() == 4 && doors.placed() == 4, "Each complete two-block door counts as one item");
        helper.assertTrue(offer.bonusFound() == 0 && active.bonusFound() == 3 && active.bonusTarget() == 3 && active.bonus() == 5,
                "The separate bonus list contains the exact target and actual progress");
        var panel = new ContractNetwork.Panel(UUID.randomUUID(), false, ContractNetwork.State.ACTIVE, 0, 0,
                java.util.List.of(offer, active), 0, 0, "", "", "");
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ContractNetwork.Panel.write(panel, buffer);
            helper.assertTrue(ContractNetwork.Panel.read(buffer).equals(panel) && !buffer.isReadable(),
                    "The client packet preserves details, measurements and the unavailable reason");
        } finally {
            buffer.release();
        }
    }

    private static int carriedEmeralds(FakePlayer player) {
        return player.getInventory().items.stream().filter(stack -> stack.is(Items.EMERALD))
                .mapToInt(stack -> stack.getCount()).sum();
    }
}
