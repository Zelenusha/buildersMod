package io.github.zelenusha.buildersmod.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.zelenusha.buildersmod.BuildersMod;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Criterion;
import io.github.zelenusha.buildersmod.contract.ContractFixtures;
import io.github.zelenusha.buildersmod.contract.ContractNetwork;
import io.github.zelenusha.buildersmod.intro.IntroData;
import io.github.zelenusha.buildersmod.site.ConstructionSite;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * Scripted single-player session for the client-side acceptance checks.
 * Start with {@code gradlew runClient -Pwalkthrough=main}. Screenshots and the step report go to run-walkthrough.
 * World edits that only prepare a scene run on the server thread. Menus, trades and portals use the real client path.
 */
@Mod.EventBusSubscriber(modid = BuildersMod.MOD_ID, value = Dist.CLIENT)
public final class ClientWalkthrough {
    private static final String SCENARIO = System.getProperty("buildersmod.walkthrough", "");
    private static final String WORLD = "walkthrough";
    private static final String BUILDER = "buildersmod_builder";
    private static final String FOREMAN = "buildersmod_foreman";
    private static final String TRADER = "buildersmod_trader";
    private static final int PLACE = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final Deque<Step> STEPS = new ArrayDeque<>();
    private static final List<String> REPORT = new ArrayList<>();
    private static final List<String> CHAT = new ArrayList<>();
    private static boolean started;
    private static boolean finished;
    private static int waited;
    private static int shots;
    private static BlockPos office;
    private static BlockPos dugout;

    private record Step(String name, int timeout, BooleanSupplier done) { }

    private ClientWalkthrough() { }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (SCENARIO.isEmpty() || finished || event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!started) {
            if (!(mc.screen instanceof TitleScreen)) {
                return;
            }
            started = true;
            File[] old = new File(mc.gameDirectory, "screenshots").listFiles();
            if (old != null) {
                for (File file : old) {
                    file.delete();
                }
            }
            mainCycle(mc);
        }
        Step step = STEPS.peek();
        if (step == null) {
            end(mc, null);
            return;
        }
        try {
            if (step.done().getAsBoolean()) {
                if (!step.name().isEmpty()) {
                    REPORT.add("PASS " + step.name());
                }
                STEPS.poll();
                waited = 0;
            } else if (++waited > step.timeout()) {
                end(mc, step.name() + " — не дождались за " + step.timeout() + " тиков");
            }
        } catch (Throwable error) {
            end(mc, step.name() + " — " + error);
        }
    }

    @SubscribeEvent
    public static void chat(ClientChatReceivedEvent event) {
        CHAT.add(event.getMessage().getString());
    }

    private static void end(Minecraft mc, String failure) {
        finished = true;
        if (failure != null) {
            REPORT.add("FAIL " + failure);
            REPORT.add("Чат: " + CHAT);
            Screenshot.grab(mc.gameDirectory, "FAIL.png", mc.getMainRenderTarget(), message -> { });
        } else {
            REPORT.add("ИТОГ: все шаги пройдены");
        }
        try {
            Files.write(new File(mc.gameDirectory, "walkthrough-" + SCENARIO + ".txt").toPath(), REPORT, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
        mc.stop();
    }

    // Script vocabulary

    private static void step(String name, int timeout, BooleanSupplier done) {
        STEPS.add(new Step(name, timeout, done));
    }

    private static void run(String name, Runnable action) {
        step(name, 0, () -> {
            action.run();
            return true;
        });
    }

    private static void pause(int ticks) {
        int[] left = {ticks};
        step("", ticks + 1, () -> --left[0] <= 0);
    }

    private static void shot(String name) {
        pause(10);
        run("", () -> Screenshot.grab(mc().gameDirectory, String.format(Locale.ROOT, "%02d-%s.png", ++shots, name),
                mc().getMainRenderTarget(), message -> { }));
        pause(2);
    }

    private static void check(boolean condition, String expectation) {
        if (!condition) {
            throw new AssertionError(expectation);
        }
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    private static IntegratedServer server() {
        return mc().getSingleplayerServer();
    }

    private static ServerPlayer serverPlayer() {
        return server().getPlayerList().getPlayer(mc().player.getUUID());
    }

    private static <T> T onServer(Supplier<T> work) {
        return server().submit(work).join();
    }

    private static void command(String text) {
        onServer(() -> server().getCommands().performPrefixedCommand(
                serverPlayer().createCommandSourceStack().withPermission(4).withSuppressedOutput(), text));
    }

    /** Puts the player's feet at the position and turns the camera to the target. */
    private static void stand(Vec3 feet, Vec3 target) {
        double dx = target.x - feet.x;
        double dy = target.y - (feet.y + 1.62);
        double dz = target.z - feet.z;
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double pitch = -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        command(String.format(Locale.ROOT, "tp @s %.2f %.2f %.2f %.1f %.1f", feet.x, feet.y, feet.z, yaw, pitch));
    }

    private static Vec3 onGround(double x, double z) {
        return onServer(() -> new Vec3(x, serverPlayer().serverLevel().getHeight(Heightmap.Types.MOTION_BLOCKING,
                (int) Math.floor(x), (int) Math.floor(z)), z));
    }

    private static Vec3 at(BlockPos origin, double x, double y, double z) {
        return new Vec3(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
    }

    private static Entity npc(String tag) {
        return onServer(() -> {
            for (Entity entity : server().overworld().getAllEntities()) {
                if (entity.getTags().contains(tag) && entity.isAlive()) {
                    return entity;
                }
            }
            return null;
        });
    }

    private static void interact(String tag) {
        UUID id = npc(tag).getUUID();
        for (Entity entity : mc().level.entitiesForRendering()) {
            if (entity.getUUID().equals(id)) {
                mc().gameMode.interact(mc().player, entity, InteractionHand.MAIN_HAND);
                return;
            }
        }
        throw new AssertionError("Клиент не видит NPC " + tag);
    }

    private static boolean heard(String text) {
        return CHAT.stream().anyMatch(line -> line.contains(text));
    }

    private static Button button(String label) {
        for (GuiEventListener child : mc().screen.children()) {
            if (child instanceof Button button && button.getMessage().getString().startsWith(label)) {
                return button;
            }
        }
        throw new AssertionError("Нет кнопки «" + label + "» на экране " + mc().screen.getClass().getSimpleName());
    }

    /** Presses a button whose handler asks the server for a new panel, then waits for that panel. */
    private static void press(String name, String label) {
        Screen[] before = new Screen[1];
        run("", () -> {
            before[0] = mc().screen;
            Button target = button(label);
            check(target.active, "Кнопка «" + label + "» должна быть доступна");
            target.onPress();
        });
        step(name, 100, () -> mc().screen != null && mc().screen != before[0]);
    }

    private static ContractNetwork.Panel panel() {
        check(mc().screen instanceof ForemanScreen, "Открыт экран прораба");
        return ((ForemanScreen) mc().screen).panel;
    }

    private static void openForeman() {
        run("", () -> stand(at(office, 6.5, 1, 2.5), at(office, 5.5, 2.6, 5.5)));
        pause(10);
        run("", () -> interact(FOREMAN));
        step("правый клик по прорабу открывает диалог", 100,
                () -> mc().screen instanceof ForemanScreen screen && screen.panel.dialogue());
    }

    private static void closeScreen() {
        run("", () -> mc().player.closeContainer());
        step("", 40, () -> mc().screen == null);
    }

    private static int carried(Item item) {
        return onServer(() -> serverPlayer().getInventory().countItem(item));
    }

    private static void toggleBlueprint() {
        run("", () -> KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_B)));
        pause(3);
    }

    private static void enterPortal(Supplier<Vec3> opening, net.minecraft.resources.ResourceKey<Level> destination, String name) {
        run("", () -> stand(opening.get(), opening.get().add(0, 1.62, 1)));
        step(name, 300, () -> mc().level != null && mc().level.dimension() == destination && mc().screen == null
                && onServer(() -> serverPlayer().serverLevel().dimension() == destination));
        pause(40);
    }

    private static void dismissWorldWarning() {
        if (mc().screen == null) {
            return;
        }
        String skip = Component.translatable("selectWorld.backupJoinSkipButton").getString();
        String proceed = Component.translatable("gui.proceed").getString();
        for (GuiEventListener child : mc().screen.children()) {
            if (child instanceof Button button && (button.getMessage().getString().equals(skip)
                    || button.getMessage().getString().equals(proceed))) {
                button.onPress();
                return;
            }
        }
    }

    // Scenario: from the first meeting with the builder to the first wage and a reload

    private static void mainCycle(Minecraft mc) {


        UUID[] npcs = new UUID[3];

        run("создан новый мир выживания", () -> {
            try {
                if (mc.getLevelSource().levelExists(WORLD)) {
                    try (var access = mc.getLevelSource().createAccess(WORLD)) {
                        access.deleteLevel();
                    }
                }
            } catch (IOException error) {
                throw new IllegalStateException(error);
            }
            GameRules rules = new GameRules();
            rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
            rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
            rules.getRule(GameRules.RULE_DO_TRADER_SPAWNING).set(false, null);
            rules.getRule(GameRules.RULE_DO_PATROL_SPAWNING).set(false, null);
            mc.createWorldOpenFlows().createFreshLevel(WORLD, new LevelSettings(WORLD, GameType.SURVIVAL, false,
                    Difficulty.NORMAL, true, rules, WorldDataConfiguration.DEFAULT),
                    new WorldOptions(Long.getLong("buildersmod.walkthrough.seed", 20261115L), true, false),
                    WorldPresets::createNormalWorldDimensions);
        });
        step("игрок появился в мире", 2400, () -> mc.player != null && mc.level != null && mc.screen == null
                && server() != null && serverPlayer() != null);
        pause(100);

        // FR-10, FR-139, FR-173: the builder, refusal and a repeated offer
        step("рядом с игроком появился строитель", 200, () -> npc(BUILDER) != null);
        run("", () -> {
            Entity builder = npc(BUILDER);
            check(onServer(() -> builder.distanceTo(serverPlayer())) <= 8, "Строитель стоит рядом с игроком");
            stand(serverPlayer().position(), builder.getEyePosition());
        });
        shot("builder-at-spawn");
        run("", () -> interact(BUILDER));
        step("строитель предлагает работу и жильё", 100, () -> heard("Нужна работа?") && heard("[Принять работу]"));
        shot("job-offer");
        run("", () -> mc.player.connection.sendCommand("buildersmod_job decline"));
        step("после отказа строитель остаётся на месте", 100, () -> heard("Если передумаешь"));
        run("до согласия построек нет", () -> check(!onServer(() -> IntroData.get(server()).hasAccepted(mc.player.getUUID())),
                "Работа ещё не принята"));
        run("", () -> {
            CHAT.clear();
            interact(BUILDER);
        });
        step("тот же строитель повторяет предложение", 100, () -> heard("Нужна работа?"));
        run("", () -> mc.player.connection.sendCommand("buildersmod_job accept"));
        step("после согласия появились землянка и офис", 200,
                () -> onServer(() -> IntroData.get(server()).hasAccepted(mc.player.getUUID())));
        run("", () -> {
            IntroData data = IntroData.get(server());
            office = data.officeArrival(mc.player.getUUID()).offset(-8, -1, -5);
            dugout = data.dugout(mc.player.getUUID()).offset(-2, 0, -2);
            npcs[0] = npc(BUILDER).getUUID();
            npcs[1] = npc(FOREMAN).getUUID();
            npcs[2] = npc(TRADER).getUUID();
            REPORT.add("Землянка " + dugout.toShortString() + ", офис " + office.toShortString()
                    + ", игрок " + mc.player.blockPosition().toShortString());
        });

        // FR-11, FR-61, FR-142: the dugout and the office
        run("", () -> stand(onGround(dugout.getX() + 2.5, dugout.getZ() - 5.5), at(dugout, 2.5, 2, 0)));
        shot("dugout-outside");
        run("", () -> stand(at(dugout, 2.5, 1, 1.5), at(dugout, 2, 1.4, 3.5)));
        shot("dugout-inside");
        run("", () -> stand(onGround(office.getX() + 6.5, office.getZ() - 9.5), at(office, 6.5, 3, 0)));
        shot("office-outside");
        run("", () -> stand(at(office, 6.5, 1, 1.5), at(office, 6.5, 2.2, 6)));
        shot("office-inside");
        run("в офисе стоят прораб, торговец и тот же строитель", () -> {
            for (String tag : List.of(BUILDER, FOREMAN, TRADER)) {
                BlockPos position = npc(tag).blockPosition();
                check(position.getX() > office.getX() && position.getX() < office.getX() + 12
                        && position.getZ() > office.getZ() && position.getZ() < office.getZ() + 10,
                        tag + " находится внутри офиса");
            }
        });

        // FR-146, FR-95: the dialogue first, no offers on the hiring day
        openForeman();
        shot("foreman-dialogue");
        press("действие в диалоге открывает меню заказов", "Открыть заказы");
        run("в день найма предложений ещё нет", () -> check(!panel().dialogue()
                && panel().state() == ContractNetwork.State.NOT_READY && panel().cards().isEmpty(), "Список пуст до смены дня"));
        shot("orders-before-first-day");
        closeScreen();

        // FR-167, FR-168, FR-170: both shops open at once and take emeralds only
        run("", () -> {
            command("give @s minecraft:emerald 16");
            stand(at(office, 4.5, 1, 2.5), at(office, 3.5, 2.6, 3.5));
        });
        pause(10);
        run("", () -> interact(BUILDER));
        step("правый клик по строителю сразу открывает торговлю", 100, () -> mc.screen instanceof MerchantScreen);
        run("у строителя десять инструментов по 2 и 4 изумруда", () -> {
            var offers = ((MerchantScreen) mc.screen).getMenu().getOffers();
            check(offers.size() == 12, "Десять инструментов, тряпка и огнетушитель, а не " + offers.size());
            for (int index = 0; index < 10; index++) {
                check(offers.get(index).getBaseCostA().is(Items.EMERALD)
                        && offers.get(index).getBaseCostA().getCount() == (index < 5 ? 2 : 4)
                        && offers.get(index).getCostB().isEmpty(), "Цена инструмента " + index);
            }
            check(offers.get(0).getResult().is(Items.STONE_PICKAXE) && offers.get(9).getResult().is(Items.IRON_SWORD),
                    "Первой идёт каменная кирка, десятым — железный меч");
        });
        shot("builder-trade");
        run("", () -> {
            MerchantMenu menu = ((MerchantScreen) mc.screen).getMenu();
            menu.setSelectionHint(0);
            menu.tryMoveItems(0);
            mc.getConnection().send(new ServerboundSelectTradePacket(0));
        });
        pause(6);
        run("", () -> {
            int menu = ((MerchantScreen) mc.screen).getMenu().containerId;
            mc.gameMode.handleInventoryMouseClick(menu, 2, 0, ClickType.PICKUP, mc.player);
            mc.gameMode.handleInventoryMouseClick(menu, 3, 0, ClickType.PICKUP, mc.player);
        });
        shot("builder-trade-bought");
        closeScreen();
        step("каменная кирка куплена за 2 изумруда", 60,
                () -> carried(Items.STONE_PICKAXE) == 1 && carried(Items.EMERALD) == 14);
        run("", () -> {
            CHAT.clear();
            stand(at(office, 8.5, 1, 3.5), at(office, 10.5, 2.6, 4.5));
        });
        pause(10);
        run("", () -> interact(TRADER));
        step("правый клик по торговцу открывает торговлю или сообщает о пустом дне", 100,
                () -> mc.screen instanceof MerchantScreen || heard("Сегодня подходящих материалов нет"));
        run("", () -> {
            if (mc.screen instanceof MerchantScreen screen) {
                var offers = screen.getMenu().getOffers();
                check(offers.size() <= 4, "У торговца не более четырёх товаров");
                StringBuilder goods = new StringBuilder("Товары торговца:");
                for (var offer : offers) {
                    check(offer.getBaseCostA().is(Items.EMERALD) && offer.getResult().getCount() == 16 && offer.getMaxUses() == 8,
                            "Партия из 16 единиц за изумруды, восемь партий в день");
                    goods.append(' ').append(offer.getResult().getItem()).append(" за ").append(offer.getBaseCostA().getCount());
                }
                REPORT.add(goods.toString());
            } else {
                REPORT.add("Товары торговца: рядом нет дальних биомов из таблицы, ассортимент пуст");
            }
        });
        shot("trader");
        closeScreen();

        // FR-01, FR-22, FR-95, FR-172: the first offers on the next game day
        run("", () -> command("time add 24000"));
        pause(20);
        openForeman();
        press("на следующий день список заказов открыт", "Открыть заказы");
        run("три строительных места заполнены, первая карточка — пробная комната", () -> {
            var cards = panel().cards();
            check(panel().state() == ContractNetwork.State.AVAILABLE, "Есть доступные предложения");
            for (int slot = 0; slot < 3; slot++) {
                check(cards.size() > slot && cards.get(slot).slot() == slot, "Место " + (slot + 1) + " занято строительным заказом");
            }
            var sample = cards.get(0);
            check(sample.title().equals("Пробная комната") && sample.available(), "Пробная комната доступна");
            check(sample.wage() == 6 && sample.bonus() == 5, "База 6 и максимум на 5 больше за торты");
            check(sample.bonusTarget() == 3, "Бонус требует ровно три торта");
            check(needed(sample, "block.minecraft.oak_door") == 4 && needed(sample, "block.minecraft.torch") == 2
                    && needed(sample, "block.minecraft.crafting_table") == 1, "Четыре двери, два факела и верстак");
            StringBuilder list = new StringBuilder("Карточки:");
            for (var card : cards) {
                list.append(" [").append(card.slot() + 1).append("] ").append(card.title()).append(" ур.").append(card.level())
                        .append(' ').append(card.wage()).append('/').append(card.wage() + card.bonus())
                        .append(card.available() ? "" : " недоступна");
            }
            REPORT.add(list.toString());
        });
        shot("offers-card-1");
        for (int tab = 2; tab <= 5; tab++) {
            String label = Integer.toString(tab);
            run("", () -> {
                Button card = null;
                for (GuiEventListener child : mc.screen.children()) {
                    if (child instanceof Button button && button.getMessage().getString().equals(label) && button.active) {
                        card = button;
                    }
                }
                if (card != null) {
                    card.onPress();
                }
            });
            shot("offers-card-" + tab);
        }
        run("", () -> button("1").onPress());

        // FR-25, FR-71, FR-101: acceptance
        press("заказ принят у прораба", "Принять заказ");
        run("карточка показывает статус «в работе» и счётчики материалов", () -> {
            check(panel().state() == ContractNetwork.State.ACTIVE && panel().feedback().startsWith("Заказ принят"),
                    "Сервер подтвердил принятие: " + panel().feedback());
            check(ContractFixtures.activeBlueprint(serverPlayer()) != null, "Чертёж привязан к проёму");
        });
        shot("order-accepted");
        closeScreen();

        // FR-14, FR-143, FR-105, FR-171: the office portal
        enterPortal(() -> at(office, 8.5, 1, 8.5), ConstructionSite.SITE_LEVEL, "вход в офисный портал переносит на стройку");
        run("игрок прибыл на защищённое место в стартовой комнате", () -> check(
                onServer(() -> serverPlayer().blockPosition().equals(ConstructionSite.SITE_ARRIVAL)), "Точка прибытия 0, 65, 0"));
        run("", () -> stand(new Vec3(0.5, 65, 2.5), new Vec3(0.5, 66.5, -4)));
        shot("site-arrival");
        run("", () -> stand(new Vec3(-2.5, 65, 2.5), new Vec3(3, 66.5, -3)));
        shot("site-return-portal");

        // FR-07, FR-41, FR-62, FR-63: blueprint silhouettes and the task list
        run("", () -> stand(new Vec3(10.5, 65, -19.5), new Vec3(0.5, 67, -8.5)));
        shot("blueprint-empty");
        run("", () -> stand(new Vec3(0.5, 70, -2.5), new Vec3(0.5, 65, -9)));
        shot("blueprint-from-roof");
        run("поставлены пол и стены, один блок неверный, один пропущен", () -> onServer(() -> {
            ServerPlayer player = serverPlayer();
            RoomBlueprint room = ContractFixtures.activeBlueprint(player);
            ServerLevel site = player.serverLevel();
            BlockPos corner = new BlockPos(room.bounds().max().getX(), room.bounds().min().getY() + 2, room.bounds().min().getZ());
            room.requirements().forEach((pos, requirement) -> {
                if (requirement.criterion() == Criterion.FLOOR || requirement.criterion() == Criterion.WALLS) {
                    BlockState state = pos.equals(corner.south(2)) ? Blocks.COBBLESTONE.defaultBlockState()
                            : requirement.expectedState();
                    if (!pos.equals(corner.south(4))) {
                        site.setBlock(pos, state, PLACE);
                    }
                }
            });
            return null;
        }));
        run("", () -> stand(new Vec3(10.5, 65, -19.5), new Vec3(0.5, 67, -8.5)));
        shot("blueprint-partial");
        run("", () -> stand(new Vec3(7.5, 65, -9.5), new Vec3(3.5, 66.5, -8.5)));
        shot("blueprint-markers-close");
        toggleBlueprint();
        shot("blueprint-hidden");
        toggleBlueprint();
        shot("blueprint-shown-again");
        run("поставлены один факел из двух и два торта", () -> onServer(() -> {
            ServerPlayer player = serverPlayer();
            RoomBlueprint room = ContractFixtures.activeBlueprint(player);
            ServerLevel site = player.serverLevel();
            BlockPos torch = room.requirements().entrySet().stream()
                    .filter(entry -> entry.getValue().criterion() == Criterion.LIGHTING).findFirst().orElseThrow().getKey();
            site.setBlock(torch, Blocks.TORCH.defaultBlockState(), PLACE);
            BlockPos inside = room.interior().min().offset(2, 0, 2);
            site.setBlock(inside, Blocks.CAKE.defaultBlockState(), PLACE);
            site.setBlock(inside.east(), Blocks.CAKE.defaultBlockState(), PLACE);
            return null;
        }));

        // FR-02, FR-147, FR-172: a refused submission and the progress lists
        enterPortal(() -> new Vec3(2.5, 65, -2.5), Level.OVERWORLD, "обратный портал возвращает в офис");
        run("игрок прибыл на защищённое место в офисе", () -> check(onServer(() -> serverPlayer().blockPosition()
                .equals(IntroData.get(server()).officeArrival(mc.player.getUUID()))), "Точка прибытия в офисе"));
        openForeman();
        press("", "Открыть заказы");
        run("список показывает прогресс недостроя", () -> {
            var card = panel().cards().get(0);
            check(placed(card, "block.minecraft.torch") == 1 && placed(card, "block.minecraft.oak_door") == 0,
                    "Факелы 1 / 2, двери 0 / 4");
            check(card.bonusFound() == 2 && card.missing() > 0, "Торты 2 / 3, есть расхождения");
        });
        shot("card-partial-progress");
        press("прораб отказывается принять недострой", "Сдать");
        run("заказ остаётся активным после отказа", () -> check(panel().feedback().startsWith("Такую комнату я не приму")
                && panel().state() == ContractNetwork.State.ACTIVE && ContractFixtures.completed(serverPlayer()) == 0,
                "Короткая реплика отказа: " + panel().feedback()));
        shot("submit-refused");
        run("комната достроена, пока игрок в офисе: добавлен третий торт", () -> onServer(() -> {
            ServerPlayer player = serverPlayer();
            RoomBlueprint room = ContractFixtures.activeBlueprint(player);
            ServerLevel site = server().getLevel(ConstructionSite.SITE_LEVEL);
            room.requirements().forEach((pos, requirement) -> site.setBlock(pos, requirement.expectedState(), PLACE));
            site.setBlock(room.interior().min().offset(2, 0, 3), Blocks.CAKE.defaultBlockState(), PLACE);
            player.getRandom().setSeed(1L);
            return null;
        }));
        press("", "Сдать");
        run("комната зачтена, начислены база, бонус за торты и досрочные сутки", () -> {
            check(panel().feedback().startsWith("Постройка принята"), "Прораб принял комнату: " + panel().feedback());
            check(ContractFixtures.completed(serverPlayer()) == 1 && ContractFixtures.unpaid(serverPlayer()) == 6 + 5 + 3,
                    "К выдаче 14 изумрудов, а не " + ContractFixtures.unpaid(serverPlayer()));
            check(panel().cards().stream().anyMatch(card -> card.slot() == 0 && !card.title().equals("Пробная комната")),
                    "В первом месте появилось новое предложение");
        });
        shot("order-credited");
        press("зарплата выдана изумрудами", "Забрать зарплату");
        run("у игрока 28 изумрудов, остатка к выдаче нет", () -> check(carried(Items.EMERALD) == 28
                && ContractFixtures.unpaid(serverPlayer()) == 0, "В инвентаре " + carried(Items.EMERALD)));
        shot("wage-claimed");
        closeScreen();

        // The finished room has no markers and the task list is empty
        enterPortal(() -> at(office, 8.5, 1, 8.5), ConstructionSite.SITE_LEVEL, "повторный вход на стройку");
        run("", () -> stand(new Vec3(10.5, 65, -19.5), new Vec3(0.5, 67, -8.5)));
        shot("room-finished");
        enterPortal(() -> new Vec3(2.5, 65, -2.5), Level.OVERWORLD, "возврат в офис перед сохранением");

        // FR-66, FR-148: reload
        run("мир сохранён и закрыт", () -> {
            mc.level.disconnect();
            mc.clearLevel(new GenericDirtMessageScreen(Component.translatable("menu.savingLevel")));
            mc.setScreen(new TitleScreen());
        });
        pause(20);
        run("", () -> mc.createWorldOpenFlows().loadLevel(mc.screen, WORLD));
        step("мир открыт повторно", 2400, () -> {
            dismissWorldWarning();
            return mc.player != null && mc.level != null && mc.screen == null && server() != null && serverPlayer() != null;
        });
        pause(60);
        run("после перезагрузки сохранились работа, NPC, зачёт и выплата", () -> {
            check(onServer(() -> IntroData.get(server()).hasAccepted(mc.player.getUUID())), "Работа принята");
            check(npc(BUILDER).getUUID().equals(npcs[0]) && npc(FOREMAN).getUUID().equals(npcs[1])
                    && npc(TRADER).getUUID().equals(npcs[2]), "В офисе те же три NPC");
            check(ContractFixtures.completed(serverPlayer()) == 1 && ContractFixtures.unpaid(serverPlayer()) == 0
                    && carried(Items.EMERALD) == 28, "Зачёт и изумруды сохранены");
        });
        openForeman();
        press("", "Открыть заказы");
        run("предложения прораба сохранились", () -> check(panel().state() == ContractNetwork.State.AVAILABLE
                && panel().completed() == 1 && panel().cards().size() >= 3, "Список заказов восстановлен"));
        shot("orders-after-reload");
        closeScreen();
        run("", () -> {
            CHAT.clear();
            stand(at(office, 4.5, 1, 2.5), at(office, 3.5, 2.6, 3.5));
        });
        pause(10);
        run("", () -> interact(BUILDER));
        step("торговля со строителем доступна после перезагрузки", 100, () -> mc.screen instanceof MerchantScreen);
        closeScreen();
    }

    private static int needed(ContractNetwork.Card card, String label) {
        return card.materials().stream().filter(material -> material.label().equals(label))
                .mapToInt(ContractNetwork.Material::needed).sum();
    }

    private static int placed(ContractNetwork.Card card, String label) {
        return card.materials().stream().filter(material -> material.label().equals(label))
                .mapToInt(ContractNetwork.Material::placed).sum();
    }
}
