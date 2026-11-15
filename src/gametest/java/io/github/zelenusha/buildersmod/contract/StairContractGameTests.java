package io.github.zelenusha.buildersmod.contract;

import com.mojang.authlib.GameProfile;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint;
import io.github.zelenusha.buildersmod.site.ConstructionSite;
import io.github.zelenusha.buildersmod.site.SiteStairBlueprint;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("buildersmod")
@PrefixGameTestTemplate(false)
public final class StairContractGameTests {
    private static final int FIXTURE_UPDATE = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void stairDeadlinesRenewAndChargeTheCurrentMismatchWithEscalation(GameTestHelper helper) {
        ServerLevel site = site(helper);
        FakePlayer player = player(site);
        ContractData data = new ContractData();
        ContractData.Account account = data.account(player.getUUID());
        ContractData.Accepted offer = OfferFactory.stairs(site, account);
        helper.assertTrue(offer != null, "The original starter room allows a staircase order");
        ContractData.Accepted accepted = offer.started(data.time(player.server));
        account.active = accepted;
        account.special = accepted;
        RoomBlueprint blueprint = accepted.blueprint();
        List<String> notices = new ArrayList<>();
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        BlockPos step = step(blueprint);
        BlockPos shell = blueprint.requirements().entrySet().stream()
                .filter(entry -> entry.getValue().criterion() == RoomBlueprint.Criterion.WALLS).findFirst().orElseThrow().getKey();
        BlockPos opening = blueprint.requirements().entrySet().stream().filter(entry -> entry.getValue().clearance())
                .filter(entry -> entry.getKey().getY() == ConstructionSite.START_FLOOR_Y + ConstructionSite.FLOOR_STEP)
                .findFirst().orElseThrow().getKey();
        List<BlockPos> damaged = List.of(step, shell, opening);
        try {
            blueprint.requirements().forEach((pos, requirement) -> setBlock(site, pos, requirement.expectedState(), before));
            for (BlockPos pos : damaged) {
                site.setBlock(pos, Blocks.DIRT.defaultBlockState(), FIXTURE_UPDATE);
            }
            int[] baseFines = {7, 5, 3};
            for (int attempt = 0; attempt < baseFines.length; attempt++) {
                RoomBlueprint.BlueprintCheck check = blueprint.check(site);
                helper.assertTrue(check.mismatchCount() == damaged.size() - attempt
                        && check.violatedCriteria().size() == damaged.size() - attempt, "Tread, shell and clearance fail independently");
                int expectedFine = baseFines[attempt] + 3 * attempt;
                advanceToDeadline(data, player, account.active);
                helper.assertTrue(ContractEvents.checkDeadline(player, data, account, notices::add),
                        "Each deadline must evaluate the current blocks");
                helper.assertTrue(account.fines.entries().size() == attempt + 1
                        && account.fines.entries().get(attempt).amount() == expectedFine,
                        "Each fine uses the current mismatch and the expected 0, 3 or 6 additional emeralds");
                helper.assertTrue(account.active.lateCount() == attempt + 1 && account.active.blueprint() == blueprint
                        && account.active.deadline() - data.time(player.server) == offer.difficulty().duration()
                        && account.active == account.special, "Renewal keeps the blueprint and starts the full original duration");
                helper.assertTrue(account.highestFloor == 1 && account.unpaid == 0 && account.rooms.isEmpty(),
                        "An unfinished staircase cannot open or pay for the next floor");
                helper.assertTrue(!ContractEvents.checkDeadline(player, data, account, notices::add),
                        "Rechecking the same moment cannot issue another fine");
                BlockPos repaired = damaged.get(attempt);
                site.setBlock(repaired, blueprint.requirements().get(repaired).expectedState(), FIXTURE_UPDATE);
            }
            helper.assertTrue(notices.size() == 3 && notices.get(2).equals(account.lastCall),
                    "Each renewal emits one foreman notice and retains the last one");
        } finally {
            restore(site, before);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void repairsCanPurchaseADayAfterTheOrdinaryExtensionAndOpenOnlyOnSubmission(GameTestHelper helper) {
        ServerLevel site = site(helper);
        FakePlayer player = player(site);
        ContractData data = new ContractData();
        ContractData.Account account = data.account(player.getUUID());
        ContractData.Accepted offer = OfferFactory.stairs(site, account);
        helper.assertTrue(offer != null, "The staircase order is available");
        account.active = offer.started(data.time(player.server));
        account.special = account.active;
        int price = offer.difficulty().extraDayPrice();
        player.getInventory().setItem(0, new ItemStack(Items.EMERALD, 4 * price));
        long originalDeadline = account.active.deadline();
        ContractEvents.buyTime(player, data, account);
        helper.assertTrue(account.active.extraDay() && account.active.deadline() == originalDeadline + ContractBalance.TICKS_PER_DAY
                && carriedEmeralds(player) == 3 * price, "An ordinary purchase adds one day and charges its configured price");

        RoomBlueprint blueprint = offer.blueprint();
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        BlockPos step = step(blueprint);
        List<String> notices = new ArrayList<>();
        try {
            blueprint.requirements().forEach((pos, requirement) -> setBlock(site, pos, requirement.expectedState(), before));
            helper.assertTrue(blueprint.check(site).complete(), "All staircase, upper room and clearance requirements are present");

            for (int caseNumber = 1; caseNumber <= 2; caseNumber++) {
                advanceToDeadline(data, player, account.active);
                helper.assertTrue(ContractEvents.checkDeadline(player, data, account, notices::add), "The complete staircase gets inspected");
                helper.assertTrue(account.stairInspection == ContractData.StairInspection.READY_AT_DEADLINE
                        && account.highestFloor == 1 && account.unpaid == 0 && account.fines.entries().isEmpty(),
                        "Passing inspection at the deadline waits for manual submission");
                site.setBlock(step, Blocks.AIR.defaultBlockState(), FIXTURE_UPDATE);
                ContractEvents.submit(player, data, account);
                helper.assertTrue(account.stairInspection == ContractData.StairInspection.NEEDS_REPAIR_DAY,
                        "A damaged staircase needs a repair day before another submission");
                site.setBlock(step, blueprint.requirements().get(step).expectedState(), FIXTURE_UPDATE);
                ContractEvents.submit(player, data, account);
                helper.assertTrue(account.active != null && account.highestFloor == 1
                        && account.stairInspection == ContractData.StairInspection.NEEDS_REPAIR_DAY,
                        "Repair by itself does not bypass the repair-day purchase");

                long now = data.time(player.server);
                ContractEvents.buyTime(player, data, account);
                helper.assertTrue(account.stairInspection == ContractData.StairInspection.WAITING && account.active.extraDay()
                        && account.active.deadline() == now + ContractBalance.TICKS_PER_DAY
                        && carriedEmeralds(player) == (3 - caseNumber) * price,
                        "Each distinct damage case permits one paid day after the ordinary purchase");
                ContractEvents.buyTime(player, data, account);
                helper.assertTrue(carriedEmeralds(player) == (3 - caseNumber) * price
                        && account.active.deadline() == now + ContractBalance.TICKS_PER_DAY,
                        "A repeated purchase in the same damage case has no effect");

                data = reload(data);
                account = data.account(player.getUUID());
                helper.assertTrue(account.highestFloor == 1 && account.active.extraDay(),
                        "Reload preserves the purchased day while the floor is still closed");
            }

            player.getRandom().setSeed(1L);
            ContractEvents.submit(player, data, account);
            helper.assertTrue(account.highestFloor == 2 && account.active == null && account.special == null
                    && account.rooms.size() == 1 && account.completed == 0,
                    "Only a successful handover opens the next floor and records the special order without counting an ordinary room");
            helper.assertTrue(account.unpaid == offer.wage() && notices.isEmpty(),
                    "Repair does not create a late notice and a purchased day excludes the early bonus");
            ContractEvents.submit(player, data, account);
            helper.assertTrue(account.highestFloor == 2 && account.unpaid == offer.wage(), "The staircase cannot open or pay twice");
        } finally {
            restore(site, before);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void nextStaircaseReusesThePriorShellMaterialsAfterReload(GameTestHelper helper) {
        ServerLevel site = site(helper);
        FakePlayer player = player(site);
        ContractData data = new ContractData();
        ContractData.Account account = data.account(player.getUUID());
        ContractData.Accepted offer = OfferFactory.stairs(site, account);
        helper.assertTrue(offer != null, "The first staircase is available");
        var granite = Blocks.POLISHED_GRANITE.defaultBlockState();
        var material = new SiteStairBlueprint.Materials(Blocks.COBBLESTONE_STAIRS.defaultBlockState(),
                Blocks.COBBLESTONE.defaultBlockState(), granite, Blocks.GRANITE.defaultBlockState(), granite);
        int lower = ConstructionSite.START_FLOOR_Y;
        int upper = lower + ConstructionSite.FLOOR_STEP;
        RoomBlueprint blueprint = SiteStairBlueprint.create(lower, SiteStairBlueprint.Layout.NORTH_FIRST, material);
        account.rooms.add(offer.rebound(offer.anchor(), offer.outward(), blueprint, Map.of()));
        account.highestFloor = 2;
        ContractData.Account reloaded = reload(data).account(player.getUUID());
        helper.assertTrue(reloaded.rooms.get(0).blueprint().requirements().get(step(blueprint)).expectedState()
                .is(Blocks.COBBLESTONE_STAIRS), "The stair material remains frozen in the persisted blueprint");

        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        try {
            blueprint.requirements().forEach((pos, requirement) -> setBlock(site, pos, requirement.expectedState(), before));
            var next = OfferFactory.stairs(site, reloaded);
            helper.assertTrue(next != null && next.blueprint().interior().min().getY() == upper + ConstructionSite.FLOOR_STEP + 1,
                    "A room using a non-default roof can be expanded to the next deck");
            var footing = next.blueprint().requirements().entrySet().stream()
                    .filter(entry -> entry.getKey().getY() == upper && entry.getValue().criterion() == RoomBlueprint.Criterion.STAIRWAYS)
                    .findFirst().orElseThrow().getValue();
            helper.assertTrue(footing.expectedState().equals(granite), "The next approach uses the previous room's floor material");
        } finally {
            restore(site, before);
        }

        long combinations = java.util.stream.IntStream.range(0, 64)
                .mapToObj(seed -> SiteStairBlueprint.selectMaterials(RandomSource.create(seed * 7_919L)))
                .distinct().count();
        helper.assertTrue(combinations == 4, "Stairs and the upper shell draw independently from the two common palettes");
        helper.succeed();
    }

    private static ServerLevel site(GameTestHelper helper) {
        ServerLevel site = helper.getLevel().getServer().getLevel(ConstructionSite.SITE_LEVEL);
        helper.assertTrue(site != null, "The construction dimension is required");
        return site;
    }

    private static FakePlayer player(ServerLevel site) {
        return new FakePlayer(site.getServer().overworld(), new GameProfile(UUID.randomUUID(), "stair-contract"));
    }

    private static void advanceToDeadline(ContractData data, FakePlayer player, ContractData.Accepted order) {
        data.advanceSleep(order.deadline() - data.time(player.server));
    }

    private static BlockPos step(RoomBlueprint blueprint) {
        return blueprint.requirements().entrySet().stream()
                .filter(entry -> entry.getValue().expectedState().getBlock() instanceof StairBlock)
                .findFirst().orElseThrow().getKey();
    }

    private static void setBlock(ServerLevel site, BlockPos pos, BlockState state, Map<BlockPos, BlockState> before) {
        before.putIfAbsent(pos, site.getBlockState(pos));
        site.setBlock(pos, state, FIXTURE_UPDATE);
    }

    private static void restore(ServerLevel site, Map<BlockPos, BlockState> before) {
        before.forEach((pos, state) -> site.setBlock(pos, state, FIXTURE_UPDATE));
    }

    private static ContractData reload(ContractData data) {
        return ContractData.load(data.save(new CompoundTag()));
    }

    private static int carriedEmeralds(FakePlayer player) {
        return player.getInventory().items.stream().filter(stack -> stack.is(Items.EMERALD)).mapToInt(ItemStack::getCount).sum();
    }
}
