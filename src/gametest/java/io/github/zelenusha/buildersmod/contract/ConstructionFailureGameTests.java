package io.github.zelenusha.buildersmod.contract;

import com.mojang.authlib.GameProfile;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Bounds;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Criterion;
import io.github.zelenusha.buildersmod.blueprint.SampleRoomBlueprint;
import io.github.zelenusha.buildersmod.intro.IntroFixtures;
import io.github.zelenusha.buildersmod.site.ConstructionSite;
import io.github.zelenusha.buildersmod.site.ConstructionSite.SiteDoor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** End-to-end failure paths of an ordinary room: the deadline, cancellation, the fine and the demolition. */
@GameTestHolder("buildersmod")
@PrefixGameTestTemplate(false)
public class ConstructionFailureGameTests {
    private static final int PLACE = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    record Scene(GameTestHelper helper, ServerLevel site, FakePlayer player, ContractData data,
                 ContractData.Account account, Map<BlockPos, BlockState> touched) {
        void set(BlockPos pos, BlockState state) {
            touched.putIfAbsent(pos.immutable(), site.getBlockState(pos));
            site.setBlock(pos, state, PLACE);
        }

        void offerSample(int slot, SiteDoor door) {
            account.offers[slot] = new ContractData.Accepted(door.lower(), door.outward(), ContractBalance.Difficulty.ONE, 0,
                    false, SampleRoomBlueprint.at(door.lower(), door.outward()), 6, ContractData.Kind.SAMPLE, slot, 0, Map.of());
        }

        void buildAll(RoomBlueprint room) {
            room.requirements().forEach((pos, requirement) -> set(pos, requirement.expectedState()));
        }

        void expireActiveOrder() {
            data.advanceSleep(account.active.deadline() - data.time(player.server) + 1);
        }

        /** Without door blocks no room can attach to the starter room of the first floor. */
        void removeStarterDoors() {
            for (SiteDoor door : ConstructionSite.STARTER_DOORS) {
                set(door.lower().above(), Blocks.AIR.defaultBlockState());
                set(door.lower(), Blocks.AIR.defaultBlockState());
            }
        }

        long remaining() {
            return account.active.deadline() - data.time(player.server);
        }
    }

    static void inScene(GameTestHelper helper, String name, Consumer<Scene> scenario) {
        var server = helper.getLevel().getServer();
        ServerLevel site = server.getLevel(ConstructionSite.SITE_LEVEL);
        FakePlayer player = new FakePlayer(server.overworld(), new GameProfile(UUID.randomUUID(), name));
        IntroFixtures.setEmployment(player, helper.absolutePos(BlockPos.ZERO),
                Math.floorDiv(server.overworld().getDayTime(), ContractBalance.TICKS_PER_DAY));
        ContractData data = new ContractData();
        Scene scene = new Scene(helper, site, player, data, data.account(player.getUUID()), new HashMap<>());
        // Seeded so that the foreman's independent 10% nonsense fine never joins these sums.
        player.getRandom().setSeed(1L);
        ContractEvents.withLoadedArea(site, new Bounds(new BlockPos(-40, 64, -40), new BlockPos(40, 70, 40)), () -> {
            try {
                scenario.accept(scene);
            } finally {
                scene.touched.forEach((pos, state) -> site.setBlock(pos, state, PLACE));
            }
            return null;
        });
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void missedDeadlineFinesOnceAndLeavesADemolitionBesideANewOrder(GameTestHelper helper) {
        inScene(helper, "deadline-failure", scene -> {
            var account = scene.account();
            scene.offerSample(0, ConstructionSite.STARTER_DOORS.get(0));
            scene.offerSample(1, ConstructionSite.STARTER_DOORS.get(1));
            ContractEvents.accept(scene.player(), scene.data(), account, 0);
            RoomBlueprint room = account.active.blueprint();
            BlockPos replacedDeck = room.bounds().min().offset(3, 0, 3);
            helper.assertTrue(scene.site().getBlockState(replacedDeck).is(Blocks.STONE), "The deck already serves as the floor");
            scene.set(replacedDeck, Blocks.OAK_PLANKS.defaultBlockState());

            long setTime = scene.player().server.overworld().getDayTime();
            scene.player().server.overworld().setDayTime(setTime + 5 * ContractBalance.TICKS_PER_DAY);
            helper.assertTrue(!ContractEvents.checkDeadline(scene.player(), scene.data(), account) && account.active != null,
                    "Moving the world clock with /time does not move the order clock");
            scene.player().server.overworld().setDayTime(setTime);

            scene.expireActiveOrder();
            helper.assertTrue(ContractEvents.checkDeadline(scene.player(), scene.data(), account),
                    "The check runs as soon as the order clock passes the deadline");
            // 149 wrong positions: every wall, ceiling, door, light and bench block and the replaced deck tile. Six criteria.
            int expectedFine = 15 + 2 * 6;
            helper.assertTrue(account.active == null && account.failed.size() == 1 && account.completed == 0 && account.unpaid == 0,
                    "A missed deadline fails the order without pay and assigns one demolition");
            helper.assertTrue(account.fines.entries().size() == 1 && account.fines.entries().get(0).amount() == expectedFine,
                    "The fine counts each wrong position and each broken criterion once");
            helper.assertTrue(account.offers[0] == null && account.offers[1] != null, "Only the failed order's slot is refreshed");
            helper.assertTrue(!ContractEvents.checkDeadline(scene.player(), scene.data(), account)
                    && account.fines.entries().size() == 1 && account.failed.size() == 1, "The result is recorded once");

            ContractEvents.accept(scene.player(), scene.data(), account, 1);
            helper.assertTrue(account.active != null && account.failed.size() == 1,
                    "A new room can be built while the demolition is open");

            ContractData reloadedData = ContractData.load(scene.data().save(new CompoundTag()));
            var reloaded = reloadedData.account(scene.player().getUUID());
            helper.assertTrue(reloaded.failed.size() == 1 && reloaded.fines.entries().get(0).amount() == expectedFine
                    && reloaded.active != null, "The fine, the demolition and the new order survive a reload");
            UUID work = reloaded.failed.get(0).id();
            ContractEvents.deliverDemolition(scene.player(), reloadedData, reloaded, work);
            helper.assertTrue(reloaded.failed.size() == 1, "The replaced deck tile keeps the demolition open");
            scene.set(replacedDeck, Blocks.AIR.defaultBlockState());
            ContractEvents.deliverDemolition(scene.player(), reloadedData, reloaded, work);
            helper.assertTrue(reloaded.failed.size() == 1, "A hole is not the original deck");
            scene.set(replacedDeck, Blocks.STONE.defaultBlockState());
            ContractEvents.deliverDemolition(scene.player(), reloadedData, reloaded, work);
            helper.assertTrue(reloaded.failed.isEmpty() && reloaded.active != null,
                    "Restoring the deck completes the demolition and keeps the new order");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void cancellationFinesByMismatchesAndAFinishedRoomCostsThree(GameTestHelper helper) {
        inScene(helper, "cancellation", scene -> {
            var account = scene.account();
            scene.offerSample(0, ConstructionSite.STARTER_DOORS.get(0));
            ContractEvents.accept(scene.player(), scene.data(), account, 0);
            RoomBlueprint room = account.active.blueprint();
            scene.buildAll(room);
            List<BlockPos> walls = room.requirements().entrySet().stream()
                    .filter(entry -> entry.getValue().criterion() == Criterion.WALLS).map(Map.Entry::getKey).limit(21).toList();
            walls.forEach(pos -> scene.set(pos, Blocks.AIR.defaultBlockState()));
            helper.assertTrue(room.check(scene.site()).mismatchCount() == 21 && room.check(scene.site()).violatedCriteriaCount() == 1,
                    "The scene has 21 wrong positions in one criterion");

            ContractEvents.cancel(scene.player(), scene.data(), account);
            helper.assertTrue(account.active == null && account.failed.size() == 1 && account.unpaid == 0 && account.completed == 0,
                    "Cancellation fails the order, frees the slot and assigns one demolition");
            helper.assertTrue(account.fines.entries().size() == 1 && account.fines.entries().get(0).amount() == 5,
                    "21 positions and one criterion cost 3 + 2 emeralds");
            helper.assertTrue(account.offers[0] == null, "The cancelled order leaves its slot for a new offer");
            ContractEvents.cancel(scene.player(), scene.data(), account);
            helper.assertTrue(account.fines.entries().size() == 1 && account.failed.size() == 1, "A repeated cancel changes nothing");

            // The wall blocks came from the scene, so nothing was dropped and nothing is owed.
            room.requirements().keySet().forEach(pos -> scene.set(pos, scene.touched().get(pos)));
            ContractEvents.deliverDemolition(scene.player(), scene.data(), account, account.failed.get(0).id());
            helper.assertTrue(account.failed.isEmpty(), "The cleared plot closes the demolition");

            scene.offerSample(2, ConstructionSite.STARTER_DOORS.get(0));
            ContractEvents.accept(scene.player(), scene.data(), account, 2);
            scene.buildAll(account.active.blueprint());
            helper.assertTrue(account.active.blueprint().check(scene.site()).complete(), "The second room is ready for credit");
            ContractEvents.cancel(scene.player(), scene.data(), account);
            helper.assertTrue(account.fines.entries().size() == 2 && account.fines.entries().get(1).amount() == 3
                    && account.failed.size() == 1 && account.completed == 0 && account.unpaid == 0,
                    "Cancelling a finished room costs a flat 3 emeralds, pays nothing and still requires demolition");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void finishedRoomIsCreditedAtTheDeadlineWithoutEarlyBonus(GameTestHelper helper) {
        inScene(helper, "deadline-credit", scene -> {
            var account = scene.account();
            scene.offerSample(0, ConstructionSite.STARTER_DOORS.get(0));
            ContractEvents.accept(scene.player(), scene.data(), account, 0);
            scene.buildAll(account.active.blueprint());
            helper.assertTrue(!ContractEvents.checkDeadline(scene.player(), scene.data(), account) && account.active != null,
                    "Nothing is checked before the deadline");
            scene.expireActiveOrder();
            helper.assertTrue(ContractEvents.checkDeadline(scene.player(), scene.data(), account) && account.active == null
                    && account.completed == 1 && account.failed.isEmpty() && account.fines.entries().isEmpty(),
                    "A finished room is credited by the deadline check");
            helper.assertTrue(account.unpaid == 6, "The base wage is paid without cakes and without an early bonus");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void extraDayIsSoldOncePerOrderAndRemovesTheEarlyBonus(GameTestHelper helper) {
        inScene(helper, "extra-day", scene -> {
            var account = scene.account();
            var player = scene.player();
            scene.offerSample(0, ConstructionSite.STARTER_DOORS.get(0));
            ContractEvents.accept(player, scene.data(), account, 0);
            long deadline = account.active.deadline();
            ContractEvents.buyTime(player, scene.data(), account);
            helper.assertTrue(account.active.deadline() == deadline && !account.active.extraDay(),
                    "Without emeralds the deadline stays");
            player.getInventory().add(new ItemStack(Items.EMERALD, 5));
            ContractEvents.buyTime(player, scene.data(), account);
            helper.assertTrue(account.active.deadline() == deadline + ContractBalance.TICKS_PER_DAY
                    && player.getInventory().countItem(Items.EMERALD) == 3, "A level 1 day costs 2 emeralds and extends the deadline");
            ContractEvents.buyTime(player, scene.data(), account);
            helper.assertTrue(account.active.deadline() == deadline + ContractBalance.TICKS_PER_DAY
                    && player.getInventory().countItem(Items.EMERALD) == 3, "The second ordinary purchase is declined for free");
            scene.buildAll(account.active.blueprint());
            ContractEvents.submit(player, scene.data(), account);
            helper.assertTrue(account.completed == 1 && account.unpaid == 6, "After buying a day there is no early bonus");
        });
    }
}
