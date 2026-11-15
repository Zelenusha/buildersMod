package io.github.zelenusha.buildersmod.contract;

import static io.github.zelenusha.buildersmod.contract.ConstructionFailureGameTests.inScene;

import com.mojang.authlib.GameProfile;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Bounds;
import io.github.zelenusha.buildersmod.blueprint.SampleRoomBlueprint;
import io.github.zelenusha.buildersmod.contract.ConstructionFailureGameTests.Scene;
import io.github.zelenusha.buildersmod.contract.ContractData.Kind;
import io.github.zelenusha.buildersmod.intro.IntroFixtures;
import io.github.zelenusha.buildersmod.maintenance.GraffitiEvents;
import io.github.zelenusha.buildersmod.maintenance.IncidentState;
import io.github.zelenusha.buildersmod.maintenance.WorkItems;
import io.github.zelenusha.buildersmod.site.ConstructionSite;
import io.github.zelenusha.buildersmod.site.ConstructionSite.SiteDoor;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.level.SleepFinishedTimeEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Single acceptance rows of §2 and §9 that the larger scenarios do not reach. */
@GameTestHolder("buildersmod")
@PrefixGameTestTemplate(false)
public class AcceptanceRowsGameTests {
    private static final int PLACE = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    @GameTest(template = "empty")
    public static void wageIsPaidAsFarAsTheInventoryHasRoom(GameTestHelper helper) {
        inScene(helper, "partial-pay", scene -> {
            var player = scene.player();
            var inventory = player.getInventory();
            for (int slot = 0; slot < inventory.items.size(); slot++) {
                inventory.setItem(slot, new ItemStack(Items.DIRT, 64));
            }
            inventory.offhand.set(0, new ItemStack(Items.DIRT, 64));
            inventory.setItem(0, new ItemStack(Items.EMERALD, 61));
            scene.account().unpaid = 8;
            ContractEvents.claim(player, scene.data(), scene.account());
            helper.assertTrue(scene.account().unpaid == 5 && inventory.countItem(Items.EMERALD) == 64,
                    "Three emeralds fit, five stay with the foreman");
            ContractEvents.claim(player, scene.data(), scene.account());
            helper.assertTrue(scene.account().unpaid == 5, "A full inventory keeps the remainder whole");
            ContractData reloadedData = ContractData.load(scene.data().save(new CompoundTag()));
            var reloaded = reloadedData.account(player.getUUID());
            inventory.setItem(1, ItemStack.EMPTY);
            ContractEvents.claim(player, reloadedData, reloaded);
            helper.assertTrue(reloaded.unpaid == 0 && inventory.countItem(Items.EMERALD) == 69,
                    "The remainder survives a reload and is paid once there is room");
            ContractEvents.claim(player, reloadedData, reloaded);
            helper.assertTrue(inventory.countItem(Items.EMERALD) == 69, "Nothing is paid twice");
        });
    }

    @GameTest(template = "empty")
    public static void sleepMovesTheOrderClockForward(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        ContractData data = ContractData.get(server);
        ServerLevel overworld = server.overworld();
        long before = data.time(server);
        MinecraftForge.EVENT_BUS.post(new SleepFinishedTimeEvent(overworld, overworld.getDayTime() + 7_000, overworld.getDayTime()));
        helper.assertTrue(data.time(server) - before == 7_000, "The skipped night counts towards order deadlines");
        ServerLevel site = server.getLevel(ConstructionSite.SITE_LEVEL);
        MinecraftForge.EVENT_BUS.post(new SleepFinishedTimeEvent(site, site.getDayTime() + 7_000, site.getDayTime()));
        helper.assertTrue(data.time(server) - before == 7_000, "Only the Overworld clock skips time");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void cardsWithoutAPlaceStateTheReason(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        ServerLevel site = server.getLevel(ConstructionSite.SITE_LEVEL);
        FakePlayer player = new FakePlayer(server.overworld(), new GameProfile(UUID.randomUUID(), "no-space"));
        IntroFixtures.setEmployment(player, helper.absolutePos(BlockPos.ZERO),
                Math.floorDiv(server.overworld().getDayTime(), ContractBalance.TICKS_PER_DAY));
        ContractData.Account account = ContractData.get(server).account(player.getUUID());
        Map<BlockPos, BlockState> touched = new HashMap<>();
        ContractEvents.withLoadedArea(site, new Bounds(new BlockPos(-40, 64, -40), new BlockPos(40, 95, 40)), () -> {
            try {
                for (SiteDoor door : ConstructionSite.STARTER_DOORS) {
                    for (BlockPos half : new BlockPos[] {door.lower().above(), door.lower()}) {
                        touched.put(half, site.getBlockState(half));
                        site.setBlock(half, Blocks.AIR.defaultBlockState(), PLACE);
                    }
                }
                var panel = ContractEvents.panel(player, UUID.randomUUID(), false, "");
                helper.assertTrue(sample(panel).note().equals("Нет места на этаже, нужен отдельный заказ")
                        && !sample(panel).available(), "The earlier card stays visible, unavailable and explains why");
                helper.assertTrue(panel.cards().stream().anyMatch(card -> card.slot() == -1 && card.available()
                        && card.title().equals(Kind.STAIRS.title)), "The staircase is offered outside the five places");

                BlockPos obstacle = new BlockPos(-4, ConstructionSite.START_FLOOR_Y + ConstructionSite.FLOOR_STEP + 1, 0);
                touched.put(obstacle, site.getBlockState(obstacle));
                site.setBlock(obstacle, Blocks.OBSIDIAN.defaultBlockState(), PLACE);
                panel = ContractEvents.panel(player, UUID.randomUUID(), false, "");
                helper.assertTrue(sample(panel).note().equals("Проход перекрыт") && panel.state() == ContractNetwork.State.NO_SPACE
                        && panel.cards().stream().noneMatch(card -> card.slot() == -1),
                        "Without a possible staircase the card says that the passage is blocked");
                site.setBlock(obstacle, touched.get(obstacle), PLACE);

                account.highestFloor = ConstructionSite.FLOOR_COUNT;
                panel = ContractEvents.panel(player, UUID.randomUUID(), false, "");
                helper.assertTrue(sample(panel).note().equals("Открыт последний этаж"), "The eighth floor is the last one");
                account.highestFloor = 1;

                touched.forEach((pos, state) -> site.setBlock(pos, state, PLACE));
                panel = ContractEvents.panel(player, UUID.randomUUID(), false, "");
                helper.assertTrue(sample(panel).available() && sample(panel).note().isEmpty()
                        && sample(panel).title().equals(Kind.SAMPLE.title), "A reopened doorway restores the same card");
            } finally {
                touched.forEach((pos, state) -> site.setBlock(pos, state, PLACE));
            }
            return null;
        });
        helper.succeed();
    }

    private static ContractNetwork.Card sample(ContractNetwork.Panel panel) {
        return panel.cards().stream().filter(card -> card.slot() == 0).findFirst().orElseThrow();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void everyIncidentKindStartsFromTheListAndFromAnEvent(GameTestHelper helper) {
        inScene(helper, "incident-paths", scene -> {
            var account = scene.account();
            var player = scene.player();
            var data = scene.data();
            scene.site().getServer().setDifficulty(Difficulty.NORMAL, true);
            SiteDoor door = ConstructionSite.STARTER_DOORS.get(0);
            RoomBlueprint room = SampleRoomBlueprint.at(door.lower(), door.outward());
            scene.buildAll(room);
            account.rooms.add(new ContractData.Accepted(door.lower(), door.outward(), ContractBalance.Difficulty.ONE, 0, false,
                    room, 6, Kind.SAMPLE, 0, 0, Map.of()));
            for (IncidentState.Kind kind : IncidentState.Kind.values()) {
                for (boolean automatic : new boolean[] {false, true}) {
                    var offer = new IncidentWork.Offer(UUID.randomUUID(), room.bounds().min(), kind,
                            ContractBalance.Difficulty.ONE, automatic ? -1 : 3);
                    boolean started = automatic ? IncidentWork.activate(player, data, account, offer, true)
                            : IncidentWork.accept(player, data, account, offer);
                    helper.assertTrue(started && account.incidents.size() == 1 && account.incidents.get(0).kind() == kind
                            && account.incidents.get(0).automatic() == automatic,
                            kind + (automatic ? " starts from an event" : " starts from the foreman's list"));
                    IncidentState work = account.incidents.get(0);
                    if (kind == IncidentState.Kind.FLOOD && !automatic) {
                        declinesNewRoomsUntilFinished(helper, scene, work);
                    } else {
                        tidy(scene, work);
                        IncidentWork.finish(player, data, account, work);
                    }
                }
            }
        });
    }

    /** FR-163: with a free construction slot a room cannot be taken during an eviction, fire or flood. */
    private static void declinesNewRoomsUntilFinished(GameTestHelper helper, Scene scene, IncidentState work) {
        var account = scene.account();
        scene.offerSample(1, ConstructionSite.STARTER_DOORS.get(1));
        ContractEvents.accept(scene.player(), scene.data(), account, 1);
        helper.assertTrue(account.active == null, "A room is declined while urgent work is active");
        tidy(scene, work);
        IncidentWork.finish(scene.player(), scene.data(), account, work);
        ContractEvents.accept(scene.player(), scene.data(), account, 1);
        helper.assertTrue(account.active != null, "The same room is accepted once the urgent work is finished");
        account.active = null;
        account.pendingCourtyard = null;
    }

    private static void tidy(Scene scene, IncidentState work) {
        ServerLevel site = scene.site();
        work.initialBlocks().forEach(pos -> site.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS));
        if (work.kind() == IncidentState.Kind.CLEANUP) {
            GraffitiEvents.remove(site, work.id());
        }
        for (UUID target : work.remainingEvictionTargets()) {
            Entity resident = site.getEntity(target);
            if (resident != null) {
                resident.discard();
            }
        }
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void nonsenseFineIsIssuedOnSuccessAndWithheldFromTheSameWage(GameTestHelper helper) {
        inScene(helper, "nonsense-fine", scene -> {
            var account = scene.account();
            long seed = 0;
            while (RandomSource.create(seed).nextInt(10) != 0) {
                seed++;
            }
            scene.offerSample(0, ConstructionSite.STARTER_DOORS.get(0));
            ContractEvents.accept(scene.player(), scene.data(), account, 0);
            scene.buildAll(account.active.blueprint());
            scene.player().getRandom().setSeed(seed);
            ContractEvents.submit(scene.player(), scene.data(), account);
            helper.assertTrue(account.completed == 1 && account.active == null && account.fines.entries().size() == 1,
                    "The room is credited and the foreman still writes his fine");
            var fine = account.fines.entries().get(0);
            int wage = 6 + 3;
            helper.assertTrue(fine.amount() == 5 * fine.audacity() && fine.audacity() >= 1 && fine.audacity() <= 5,
                    "A nonsense fine is 5 emeralds per degree of audacity");
            helper.assertTrue(fine.withheld() == Math.min(wage, fine.amount()) && account.unpaid == wage - fine.withheld(),
                    "The fine is withheld from the wage of this very order");
        });
    }

    @GameTest(template = "empty")
    public static void workItemsAreCraftedOnlyByTheirRecipes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ItemStack paper = new ItemStack(Items.PAPER);
        ItemStack none = ItemStack.EMPTY;
        ItemStack iron = new ItemStack(Items.IRON_INGOT);
        ItemStack web = new ItemStack(Items.COBWEB);
        ItemStack water = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.WATER);
        ItemStack awkward = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.AWKWARD);
        helper.assertTrue(craft(level, paper, paper, paper, paper, water, paper, paper, paper, paper).is(WorkItems.WET_RAG.get()),
                "Eight sheets of paper around a water bottle make a wet rag");
        helper.assertTrue(craft(level, paper, paper, paper, paper, awkward, paper, paper, paper, paper).isEmpty(),
                "Another potion gives nothing");
        helper.assertTrue(craft(level, none, iron, none, none, iron, none, none, web, none).is(WorkItems.FIRE_EXTINGUISHER.get()),
                "Two iron ingots over a cobweb in the middle column make an extinguisher");
        helper.assertTrue(craft(level, iron, none, none, iron, none, none, web, none, none).isEmpty(),
                "The extinguisher needs the middle column");
        ItemStack rag = new ItemStack(WorkItems.WET_RAG.get());
        ItemStack extinguisher = new ItemStack(WorkItems.FIRE_EXTINGUISHER.get());
        helper.assertTrue(rag.getMaxDamage() == 8 && extinguisher.getMaxDamage() == 16,
                "The rag lasts eight attempts and the extinguisher sixteen uses");
        helper.succeed();
    }

    private static ItemStack craft(ServerLevel level, ItemStack... grid) {
        CraftingContainer container = new TransientCraftingContainer(new AbstractContainerMenu(null, -1) {
            @Override
            public ItemStack quickMoveStack(Player player, int slot) {
                return ItemStack.EMPTY;
            }

            @Override
            public boolean stillValid(Player player) {
                return false;
            }
        }, 3, 3);
        for (int slot = 0; slot < grid.length; slot++) {
            container.setItem(slot, grid[slot].copy());
        }
        return level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, container, level)
                .map(recipe -> recipe.assemble(container, level.registryAccess())).orElse(ItemStack.EMPTY);
    }
}
