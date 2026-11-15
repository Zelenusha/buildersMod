package io.github.zelenusha.buildersmod.contract;

import static io.github.zelenusha.buildersmod.contract.ConstructionFailureGameTests.inScene;

import io.github.zelenusha.buildersmod.blueprint.RectangularRoomBlueprint;
import io.github.zelenusha.buildersmod.blueprint.RectangularRoomBlueprint.Fixture;
import io.github.zelenusha.buildersmod.blueprint.RectangularRoomBlueprint.Spec;
import io.github.zelenusha.buildersmod.blueprint.RectangularRoomBlueprint.Surface;
import io.github.zelenusha.buildersmod.blueprint.RectangularRoomBlueprint.Wall;
import io.github.zelenusha.buildersmod.blueprint.RectangularRoomBlueprint.Window;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Criterion;
import io.github.zelenusha.buildersmod.contract.ConstructionFailureGameTests.Scene;
import io.github.zelenusha.buildersmod.contract.ContractData.Kind;
import io.github.zelenusha.buildersmod.contract.ContractData.StairInspection;
import io.github.zelenusha.buildersmod.maintenance.IncidentState;
import io.github.zelenusha.buildersmod.site.ConstructionSite;
import io.github.zelenusha.buildersmod.site.ConstructionSite.SiteDoor;
import io.github.zelenusha.buildersmod.site.SiteStairBlueprint;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** End-to-end lifecycles of the orders shown outside the five places: staircase, courtyard and fallback corridor. */
@GameTestHolder("buildersmod")
@PrefixGameTestTemplate(false)
public class SpecialOrdersGameTests {
    private static final long DAY = ContractBalance.TICKS_PER_DAY;

    private static int baseFine(Scene scene) {
        var check = scene.account().active.blueprint().check(scene.site());
        return ContractBalance.incompleteFine(check.mismatchCount(), check.violatedCriteriaCount());
    }

    private static int lastFine(Scene scene) {
        var fines = scene.account().fines.entries();
        return fines.get(fines.size() - 1).amount();
    }

    private static int emeralds(Scene scene) {
        return scene.player().getInventory().countItem(Items.EMERALD);
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void staircaseSurvivesMissedDeadlinesAndDamageAndOpensTheNextFloor(GameTestHelper helper) {
        inScene(helper, "stairs", scene -> {
            var account = scene.account();
            var player = scene.player();
            var data = scene.data();
            scene.removeStarterDoors();
            ContractEvents.accept(player, data, account, 0);
            helper.assertTrue(account.active == null, "No room fits while every doorway is closed");
            ContractEvents.accept(player, data, account, -1);
            helper.assertTrue(account.active != null && account.active.kind() == Kind.STAIRS && account.active.slot() == -1
                    && scene.remaining() == 5 * DAY, "The staircase is a separate level 2 order with five days");
            RoomBlueprint blueprint = account.active.blueprint();

            int base = baseFine(scene);
            scene.expireActiveOrder();
            helper.assertTrue(ContractEvents.checkDeadline(player, data, account) && account.active != null
                    && account.active.lateCount() == 1 && scene.remaining() == 5 * DAY && account.failed.isEmpty(),
                    "A missed deadline keeps the order and gives a new term of the original length");
            helper.assertTrue(lastFine(scene) == base && account.lastCall.contains("Штраф " + base + " ")
                    && account.lastCall.contains("5 дн"), "The first miss costs the plain fine and the foreman calls: "
                    + account.lastCall);
            scene.expireActiveOrder();
            ContractEvents.checkDeadline(player, data, account);
            helper.assertTrue(lastFine(scene) == base + 3 && account.active.lateCount() == 2,
                    "The second miss adds a surcharge of 3");

            scene.buildAll(blueprint);
            scene.expireActiveOrder();
            helper.assertTrue(ContractEvents.checkDeadline(player, data, account) && account.active != null
                    && account.stairInspection == StairInspection.READY_AT_DEADLINE && account.fines.entries().size() == 2
                    && account.highestFloor == 1, "A finished staircase waits for manual submission and is not fined");

            BlockPos step = blueprint.requirements().entrySet().stream()
                    .filter(entry -> entry.getValue().criterion() == Criterion.STAIRWAYS).findFirst().orElseThrow().getKey();
            BlockState original = scene.site().getBlockState(step);
            scene.set(step, Blocks.AIR.defaultBlockState());
            ContractEvents.submit(player, data, account);
            helper.assertTrue(account.stairInspection == StairInspection.NEEDS_REPAIR_DAY && account.highestFloor == 1,
                    "Damage after the deadline check refuses the submission");
            scene.set(step, original);
            ContractEvents.submit(player, data, account);
            helper.assertTrue(account.active != null && account.highestFloor == 1, "A repair day must be bought first");
            player.getInventory().add(new ItemStack(Items.EMERALD, 3));
            ContractEvents.buyTime(player, data, account);
            helper.assertTrue(emeralds(scene) == 0 && scene.remaining() == DAY
                    && account.stairInspection == StairInspection.WAITING, "The repair day costs 3 and runs from the purchase");
            player.getRandom().setSeed(1L);
            ContractEvents.submit(player, data, account);
            helper.assertTrue(account.active == null && account.highestFloor == 2 && account.special == null
                    && account.completed == 0, "Submission opens the floor and does not count as an ordinary room");
            // FR-57: both fines for the missed deadlines are withheld from this wage first.
            helper.assertTrue(account.fines.entries().stream().mapToInt(FineLedger.Fine::withheld).sum() + account.unpaid
                    == blueprintWage(account), "After a missed deadline the staircase pays its wage without an early bonus");
            helper.assertTrue(account.rooms.get(0).blueprint().doors().size() == 3, "The new starter room has three doorways");
        });
    }

    private static long blueprintWage(ContractData.Account account) {
        return account.rooms.get(account.rooms.size() - 1).wage();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void cancelledStaircaseKeepsItsBlueprintLateCountAndBoughtDay(GameTestHelper helper) {
        inScene(helper, "stairs-cancel", scene -> {
            var account = scene.account();
            var player = scene.player();
            var data = scene.data();
            scene.removeStarterDoors();
            ContractEvents.accept(player, data, account, -1);
            RoomBlueprint blueprint = account.active.blueprint();
            player.getInventory().add(new ItemStack(Items.EMERALD, 6));
            ContractEvents.buyTime(player, data, account);
            helper.assertTrue(emeralds(scene) == 3 && scene.remaining() == 6 * DAY, "A level 2 day costs 3 emeralds");

            int base = baseFine(scene);
            ContractEvents.cancel(player, data, account);
            helper.assertTrue(account.active == null && account.failed.isEmpty() && lastFine(scene) == base
                    && account.special != null, "Cancelling fines by the formula, assigns no demolition and keeps the order");
            data.advanceSleep(2 * DAY);
            ContractEvents.accept(player, data, account, -1);
            helper.assertTrue(account.active != null && scene.remaining() == 5 * DAY
                    && account.active.blueprint().requirements().equals(blueprint.requirements()),
                    "The same blueprint returns with the full original term");
            ContractEvents.buyTime(player, data, account);
            helper.assertTrue(emeralds(scene) == 3 && scene.remaining() == 5 * DAY, "The ordinary day stays used after re-acceptance");

            scene.expireActiveOrder();
            ContractEvents.checkDeadline(player, data, account);
            ContractEvents.cancel(player, data, account);
            ContractEvents.accept(player, data, account, -1);
            helper.assertTrue(account.active.lateCount() == 1 && scene.remaining() == 5 * DAY, "The late count survives cancellation");
            var reloaded = ContractData.load(data.save(new CompoundTag())).account(player.getUUID());
            helper.assertTrue(reloaded.active.lateCount() == 1 && reloaded.active.extraDay()
                    && reloaded.special != null && reloaded.active.brief().title().equals(Kind.STAIRS.title),
                    "Late count, bought day and the special card survive a reload");
            base = baseFine(scene);
            scene.expireActiveOrder();
            ContractEvents.checkDeadline(player, data, account);
            helper.assertTrue(lastFine(scene) == base + 3, "The next miss after re-acceptance is the second one");

            scene.buildAll(blueprint);
            ContractEvents.cancel(player, data, account);
            helper.assertTrue(lastFine(scene) == 3 && account.failed.isEmpty() && account.highestFloor == 1 && account.unpaid == 0,
                    "Cancelling a finished staircase costs 3, pays nothing and opens nothing");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void urgentWorkDoesNotPauseTheStaircaseAndASecondUrgentJobIsDeclined(GameTestHelper helper) {
        inScene(helper, "stairs-urgent", scene -> {
            var account = scene.account();
            var player = scene.player();
            var data = scene.data();
            SiteDoor away = new SiteDoor(new BlockPos(24, ConstructionSite.START_FLOOR_Y + 1, 24),
                    ConstructionSite.STARTER_DOORS.get(0).outward());
            RoomBlueprint finished = io.github.zelenusha.buildersmod.blueprint.SampleRoomBlueprint.at(away.lower(), away.outward());
            scene.buildAll(finished);
            // The finished room keeps its walls but offers no doorway, so only the staircase can be ordered.
            finished.doors().forEach(door -> {
                scene.set(door.lowerPosition().above(), Blocks.AIR.defaultBlockState());
                scene.set(door.lowerPosition(), Blocks.AIR.defaultBlockState());
            });
            account.rooms.add(new ContractData.Accepted(away.lower(), away.outward(), ContractBalance.Difficulty.ONE, 0, false,
                    finished, 6, Kind.SAMPLE, 4, 0, Map.of()));
            scene.removeStarterDoors();
            ContractEvents.accept(player, data, account, -1);
            helper.assertTrue(account.active != null && account.active.kind() == Kind.STAIRS, "The staircase is in progress");
            var flood = new IncidentWork.Offer(UUID.randomUUID(), finished.bounds().min(), IncidentState.Kind.FLOOD,
                    ContractBalance.Difficulty.ONE, -1);
            helper.assertTrue(IncidentWork.activate(player, data, account, flood, true) && account.pausedAt < 0,
                    "An urgent flood leaves the staircase running");
            data.advanceSleep(DAY);
            helper.assertTrue(scene.remaining() == 4 * DAY, "The staircase deadline keeps counting during urgent work");
            var second = new IncidentWork.Offer(UUID.randomUUID(), finished.bounds().min(), IncidentState.Kind.EVICTION,
                    ContractBalance.Difficulty.ONE, 3);
            helper.assertTrue(!IncidentWork.accept(player, data, account, second) && account.incidents.size() == 1,
                    "A second pausing job is declined while the first is active");
            IncidentState work = account.incidents.get(0);
            work.initialBlocks().forEach(pos -> scene.site().setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS));
            helper.assertTrue(IncidentWork.submit(scene.site(), account, work).isPresent(), "The flood is drained");
            IncidentWork.finish(player, data, account, work);
            helper.assertTrue(scene.remaining() == 4 * DAY, "Finishing urgent work does not extend the staircase deadline");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void courtyardFollowsItsRoomAndPausesForUrgentWork(GameTestHelper helper) {
        inScene(helper, "courtyard", scene -> {
            var account = scene.account();
            var player = scene.player();
            var data = scene.data();
            SiteDoor door = ConstructionSite.STARTER_DOORS.get(0);
            Surface stone = Surface.solid(Blocks.STONE);
            Spec spec = new Spec(5, 5, List.of(4), stone, stone, stone, Set.of(Wall.OPPOSITE),
                    List.of(new Window(0, Wall.LEFT, 2, 2, 2, 2, Blocks.GLASS)),
                    List.of(new Fixture(0, new BlockPos(2, 1, 2), Blocks.TORCH.defaultBlockState())), List.of(), Optional.empty());
            account.offers[1] = new ContractData.Accepted(door.lower(), door.outward(), ContractBalance.Difficulty.ONE, 0, false,
                    RectangularRoomBlueprint.at(door.lower(), door.outward(), spec), 9, Kind.ROOM, 1, 0, Map.of());
            ContractEvents.accept(player, data, account, 1);
            helper.assertTrue(account.active != null && account.pendingCourtyard != null && account.courtyards.isEmpty(),
                    "The plot behind the window is reserved but not offered while the room is built");
            RoomBlueprint room = account.active.blueprint();
            scene.buildAll(room);
            player.getRandom().setSeed(1L);
            ContractEvents.submit(player, data, account);
            helper.assertTrue(account.completed == 1 && account.courtyards.size() == 1 && account.courtyards.get(0).slot() == -2
                    && account.pendingCourtyard == null, "Crediting the room offers its courtyard on a separate card");
            long roomWage = account.unpaid;

            ContractEvents.accept(player, data, account, -2);
            helper.assertTrue(account.active != null && account.active.kind() == Kind.COURTYARD && scene.remaining() == 3 * DAY,
                    "The courtyard takes the term of its level");
            RoomBlueprint yard = account.active.blueprint();
            player.getInventory().add(new ItemStack(Items.EMERALD, 4));
            ContractEvents.buyTime(player, data, account);
            helper.assertTrue(emeralds(scene) == 2 && scene.remaining() == 4 * DAY, "A level 1 day costs 2 emeralds");
            int base = baseFine(scene);
            ContractEvents.cancel(player, data, account);
            helper.assertTrue(account.active == null && account.failed.isEmpty() && lastFine(scene) == base
                    && account.courtyards.size() == 1, "A cancelled courtyard is fined and stays on its card");
            ContractEvents.accept(player, data, account, -2);
            ContractEvents.buyTime(player, data, account);
            helper.assertTrue(scene.remaining() == 3 * DAY && emeralds(scene) == 2,
                    "Re-acceptance restores the full term and keeps the bought day used");

            var flood = new IncidentWork.Offer(UUID.randomUUID(), room.bounds().min(), IncidentState.Kind.FLOOD,
                    ContractBalance.Difficulty.ONE, -1);
            helper.assertTrue(IncidentWork.activate(player, data, account, flood, false) && account.pausedAt >= 0,
                    "A flood in the finished room pauses the courtyard");
            data.advanceSleep(10 * DAY);
            helper.assertTrue(!ContractEvents.checkDeadline(player, data, account) && account.fines.entries().size() == 1,
                    "No deadline check runs during the pause");
            ContractEvents.accept(player, data, account, 0);
            helper.assertTrue(account.active.kind() == Kind.COURTYARD, "The paused order keeps the construction slot");
            IncidentState work = account.incidents.get(0);
            work.initialBlocks().forEach(pos -> scene.site().setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS));
            helper.assertTrue(IncidentWork.submit(scene.site(), account, work).orElse(0) == 5, "Draining pays the level 1 wage");
            IncidentWork.finish(player, data, account, work);
            helper.assertTrue(account.pausedAt < 0 && scene.remaining() == 3 * DAY, "The courtyard resumes with the saved remainder");

            base = baseFine(scene);
            scene.expireActiveOrder();
            ContractEvents.checkDeadline(player, data, account);
            helper.assertTrue(lastFine(scene) == base && account.active.lateCount() == 1 && scene.remaining() == 3 * DAY,
                    "The first missed courtyard deadline costs the plain fine and restarts the term");
            scene.expireActiveOrder();
            ContractEvents.checkDeadline(player, data, account);
            helper.assertTrue(lastFine(scene) == base + 3, "The second miss adds 3");
            scene.buildAll(yard);
            long before = account.unpaid;
            player.getRandom().setSeed(1L);
            ContractEvents.submit(player, data, account);
            helper.assertTrue(account.active == null && account.courtyards.isEmpty() && account.completed == 1,
                    "The courtyard is credited without raising the ordinary room counter");
            helper.assertTrue(roomWage > 0 && account.fines.entries().stream().mapToInt(FineLedger.Fine::withheld).sum()
                    + account.unpaid - before == blueprintWage(account),
                    "After a missed deadline the courtyard pays its wage without an early bonus");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void fallbackCorridorOpensADoorwayForAnEarlierOffer(GameTestHelper helper) {
        inScene(helper, "corridor", scene -> {
            var account = scene.account();
            var player = scene.player();
            var data = scene.data();
            account.highestFloor = 2;
            scene.removeStarterDoors();
            for (SiteDoor door : SiteStairBlueprint.upperDoors(ConstructionSite.START_FLOOR_Y + ConstructionSite.FLOOR_STEP)) {
                BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, door.outward());
                scene.set(door.lower(), lower);
                scene.set(door.lower().above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
                // A chest beside each doorway leaves room for a corridor but not for the walls of a room.
                BlockPos ahead = door.lower().relative(door.outward(), 3);
                scene.set(ahead.relative(door.outward().getClockWise(), 3), Blocks.CHEST.defaultBlockState());
                scene.set(ahead.relative(door.outward().getCounterClockWise(), 3), Blocks.CHEST.defaultBlockState());
            }
            scene.offerSample(0, ConstructionSite.STARTER_DOORS.get(0));
            ContractData.Accepted earlier = account.offers[0];
            helper.assertTrue(OfferFactory.bind(scene.site(), account, earlier) == null, "The earlier offer fits nowhere");

            ContractEvents.accept(player, data, account, -1);
            helper.assertTrue(account.active != null && account.active.kind() == Kind.CORRIDOR && scene.remaining() == 3 * DAY,
                    "The foreman offers a corridor outside the five places");
            helper.assertTrue(account.offers[0] == earlier, "The earlier offer keeps its place and conditions");
            scene.buildAll(account.active.blueprint());
            scene.expireActiveOrder();
            player.getRandom().setSeed(1L);
            helper.assertTrue(ContractEvents.checkDeadline(player, data, account) && account.active == null
                    && account.completed == 0 && account.fines.entries().isEmpty() && account.unpaid == blueprintWage(account),
                    "A finished corridor is credited by its deadline without a bonus");
            helper.assertTrue(OfferFactory.bind(scene.site(), account, earlier) != null,
                    "After the corridor the earlier offer fits behind one of its doorways");
        });
    }
}
