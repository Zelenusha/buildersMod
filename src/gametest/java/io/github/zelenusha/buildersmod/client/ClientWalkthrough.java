package io.github.zelenusha.buildersmod.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.zelenusha.buildersmod.BuildersMod;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Criterion;
import io.github.zelenusha.buildersmod.contract.ContractFixtures;
import io.github.zelenusha.buildersmod.contract.ContractNetwork;
import io.github.zelenusha.buildersmod.intro.IntroData;
import io.github.zelenusha.buildersmod.maintenance.GraffitiPattern;
import io.github.zelenusha.buildersmod.maintenance.IncidentState;
import io.github.zelenusha.buildersmod.maintenance.IncidentWorld;
import io.github.zelenusha.buildersmod.maintenance.WorkItems;
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
import net.minecraft.core.Direction;
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
import net.minecraft.world.phys.BlockHitResult;
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
                    if (file.getName().startsWith(SCENARIO + "-")) {
                        file.delete();
                    }
                }
            }
            switch (SCENARIO) {
                case "main" -> mainCycle(mc);
                case "fines" -> finesAndDemolition(mc);
                case "incidents" -> floodAndCleanup(mc);
                default -> throw new IllegalArgumentException("Неизвестный сценарий " + SCENARIO);
            }
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
            Screenshot.grab(mc.gameDirectory, SCENARIO + "-FAIL.png", mc.getMainRenderTarget(), message -> { });
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
        run("", () -> Screenshot.grab(mc().gameDirectory, String.format(Locale.ROOT, "%s-%02d-%s.png", SCENARIO, ++shots, name),
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

    private static void enterFreshWorld(Minecraft mc) {
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
    }

    /** The shortest way to a working foreman: accept the job and skip to the first offer day. */
    private static void hire(Minecraft mc) {
        enterFreshWorld(mc);
        step("рядом с игроком появился строитель", 200, () -> npc(BUILDER) != null);
        run("", () -> mc.player.connection.sendCommand("buildersmod_job accept"));
        step("работа принята, офис построен", 200,
                () -> onServer(() -> IntroData.get(server()).hasAccepted(mc.player.getUUID())));
        run("", () -> {
            office = IntroData.get(server()).officeArrival(mc.player.getUUID()).offset(-8, -1, -5);
            command("time add 24000");
        });
        pause(20);
    }

    private static ContractNetwork.FinePanel fines() {
        check(mc().screen instanceof FineScreen, "Открыт экран штрафов");
        return ((FineScreen) mc().screen).panel;
    }

    private static ContractNetwork.DemolitionPanel demolitions() {
        check(mc().screen instanceof DemolitionScreen, "Открыт экран сносов");
        return ((DemolitionScreen) mc().screen).panel;
    }

    private static void reloadWorld(Minecraft mc) {
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
    }

    /** Hires the player and credits the reference room, so that the site has a room where incidents can happen. */
    private static void firstRoom(Minecraft mc) {
        hire(mc);
        openForeman();
        press("", "Открыть заказы");
        press("пробная комната принята", "Принять заказ");
        run("пробная комната построена", () -> onServer(() -> {
            ServerLevel site = server().getLevel(ConstructionSite.SITE_LEVEL);
            ContractFixtures.activeBlueprint(serverPlayer()).requirements()
                    .forEach((pos, requirement) -> site.setBlock(pos, requirement.expectedState(), PLACE));
            serverPlayer().getRandom().setSeed(1L);
            return null;
        }));
        press("пробная комната зачтена", "Сдать");
        press("", "Забрать зарплату");
        closeScreen();
    }

    private static void useOn(BlockPos pos, Direction face) {
        Vec3 hit = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        // Sneak for the click so that a workbench under the cursor does not open instead of the held item being used.
        var sneak = net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY;
        var stand = net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY;
        mc().getConnection().send(new net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket(mc().player, sneak));
        mc().gameMode.useItemOn(mc().player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false));
        mc().getConnection().send(new net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket(mc().player, stand));
    }

    private static void hold(int hotbar, String item) {
        command("item replace entity @s hotbar." + hotbar + " with " + item);
        mc().player.getInventory().selected = hotbar;
    }

    /** A free spot on the room floor near the target, from which the player can reach it. */
    private static Vec3 beside(BlockPos target) {
        return onServer(() -> {
            ServerLevel site = serverPlayer().serverLevel();
            RoomBlueprint room = ContractFixtures.firstRoom(serverPlayer());
            int floor = room.interior().min().getY();
            for (int[] offset : new int[][] {{2, 0}, {-2, 0}, {0, 2}, {0, -2}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}}) {
                BlockPos feet = new BlockPos(target.getX() + offset[0], floor, target.getZ() + offset[1]);
                if (room.containsInterior(feet) && site.getBlockState(feet).getCollisionShape(site, feet).isEmpty()
                        && site.getBlockState(feet.above()).getCollisionShape(site, feet.above()).isEmpty()) {
                    return Vec3.atBottomCenterOf(feet);
                }
            }
            throw new AssertionError("Нет свободного места рядом с " + target);
        });
    }

    /** Selects a card by its tab label, or the separate cards when the label is empty. */
    private static void card(String tab) {
        run("", () -> button(tab.isEmpty() ? "Отдельные заказы" : tab).onPress());
    }

    private static void scrubBand(int band) {
        Screen screen = mc().screen;
        check(screen instanceof GraffitiScreen, "Открыта мини-игра отмывания");
        int scale = Math.max(1, Math.min((screen.width - 32) / GraffitiPattern.CANVAS_WIDTH,
                (screen.height - 76) / GraffitiPattern.CANVAS_HEIGHT));
        int left = (screen.width - GraffitiPattern.CANVAS_WIDTH * scale) / 2;
        int top = (screen.height - GraffitiPattern.CANVAS_HEIGHT * scale) / 2;
        double y = top + (band * 3 + 1.5) * GraffitiPattern.CELL * scale;
        screen.mouseClicked(left + 2.5 * scale, y, 0);
        screen.mouseDragged(left + (GraffitiPattern.CANVAS_WIDTH - 2.5) * scale, y, 0, 0, 0);
        screen.mouseReleased(left + (GraffitiPattern.CANVAS_WIDTH - 2.5) * scale, y, 0);
    }

    // Scenario: an automatic flood with the foreman's call and a paused room, then a voluntary cleanup

    private static void floodAndCleanup(Minecraft mc) {
        BlockPos[] source = new BlockPos[1];
        int[] floor = new int[1];
        long[] frozen = new long[1];
        firstRoom(mc);

        // FR-42, FR-43, FR-41: the event arrives as a call and pauses the room in progress
        openForeman();
        press("", "Открыть заказы");
        card("2");
        press("вторая комната принята в работу", "Принять заказ");
        closeScreen();
        run("событие потопа ждёт загрузки стройки", () -> onServer(() -> {
            ContractFixtures.queueIncident(serverPlayer(), IncidentState.Kind.FLOOD);
            return null;
        }));
        pause(40);
        run("вне стройки событие не запускается", () -> check(
                ContractFixtures.incident(serverPlayer(), IncidentState.Kind.FLOOD) == null && ContractClient.callTicks == 0,
                "Пока игрок в офисе, потопа и звонка нет"));
        enterPortal(() -> at(office, 8.5, 1, 8.5), ConstructionSite.SITE_LEVEL, "переход на стройку");
        step("на стройке событие запустилось и прораб позвонил", 200,
                () -> ContractFixtures.incident(serverPlayer(), IncidentState.Kind.FLOOD) != null && ContractClient.callTicks > 0);
        run("стройка встала на паузу, звонок сохранён", () -> {
            check(ContractFixtures.paused(serverPlayer()), "Обычная комната на паузе");
            check(ContractFixtures.lastCall(serverPlayer()).contains("сантехник"), ContractFixtures.lastCall(serverPlayer()));
            REPORT.add("Звонок: " + ContractFixtures.lastCall(serverPlayer()));
        });
        shot("call-and-paused-room");
        int[] shown = new int[1];
        run("", () -> {
            shown[0] = ContractClient.callTicks;
            mc.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
        });
        pause(60);
        run("пока открыто меню, время показа звонка не идёт", () -> check(ContractClient.callTicks == shown[0],
                "Осталось " + ContractClient.callTicks + " из " + shown[0]));
        closeScreen();
        step("звонок исчезает после восьми секунд игрового экрана", 220, () -> ContractClient.callTicks == 0);

        // FR-36: displace the water, try to submit, remove the temporary blocks
        run("", () -> onServer(() -> {
            source[0] = ContractFixtures.incident(serverPlayer(), IncidentState.Kind.FLOOD).initialBlocks().get(0);
            floor[0] = ContractFixtures.firstRoom(serverPlayer()).interior().min().getY();
            return null;
        }));
        run("", () -> {
            Vec3 spot = beside(source[0]);
            stand(spot, Vec3.atCenterOf(source[0]));
            hold(3, "minecraft:dirt 8");
        });
        pause(10);
        shot("flood");
        int[] next = new int[1];
        int[] wait = new int[1];
        step("источник воды вытеснен блоками", 300, () -> {
            if (wait[0]-- > 0) {
                return false;
            }
            int y = floor[0] + next[0];
            if (y > source[0].getY()) {
                return true;
            }
            useOn(new BlockPos(source[0].getX(), y - 1, source[0].getZ()), Direction.UP);
            next[0]++;
            wait[0] = 6;
            return false;
        });
        step("вода ушла из комнаты", 1200, () -> onServer(() -> IncidentWorld.roomHasNoWater(serverPlayer().serverLevel(),
                ContractFixtures.firstRoom(serverPlayer()))));
        run("временные блоки учтены в задании", () -> {
            var fill = ContractFixtures.incident(serverPlayer(), IncidentState.Kind.FLOOD).floodFill();
            // Furniture under the source already displaces water, so the column may be shorter than its height.
            check(fill.contains(source[0]) && fill.stream().allMatch(pos -> pos.getX() == source[0].getX()
                    && pos.getZ() == source[0].getZ() && pos.getY() >= floor[0]), "Учтены блоки колонны: " + fill);
            REPORT.add("Источник " + source[0].toShortString() + ", временных блоков " + fill.size());
        });
        shot("flood-displaced");
        enterPortal(() -> new Vec3(2.5, 65, -2.5), Level.OVERWORLD, "возврат в офис");
        openForeman();
        press("", "Открыть заказы");
        run("карточка комнаты показывает паузу и её причину", () -> {
            check(panel().pauseReason().equals("Осушение"), "Причина паузы: " + panel().pauseReason());
            frozen[0] = panel().cards().stream().filter(entry -> entry.slot() == 1).findFirst().orElseThrow().remaining();
        });
        shot("room-card-paused");
        card("");
        shot("flood-card");
        press("", "Сдать работу");
        run("осушение не принято, пока стоят временные блоки", () -> check(panel().feedback().startsWith("Работа пока не готова")
                && ContractFixtures.incident(serverPlayer(), IncidentState.Kind.FLOOD) != null, panel().feedback()));
        closeScreen();
        enterPortal(() -> at(office, 8.5, 1, 8.5), ConstructionSite.SITE_LEVEL, "возврат на стройку");
        run("", () -> stand(beside(source[0]), Vec3.atCenterOf(source[0])));
        pause(10);
        step("временные блоки убраны руками", 800, () -> {
            List<BlockPos> fill = onServer(() -> ContractFixtures.incident(serverPlayer(), IncidentState.Kind.FLOOD)
                    .floodFill().stream().sorted(java.util.Comparator.comparingInt(pos -> -pos.getY())).toList());
            for (BlockPos pos : fill) {
                if (!mc.level.getBlockState(pos).isAir()) {
                    mc.gameMode.continueDestroyBlock(pos, Direction.UP);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    return false;
                }
            }
            return true;
        });
        pause(10);
        enterPortal(() -> new Vec3(2.5, 65, -2.5), Level.OVERWORLD, "возврат в офис");
        openForeman();
        press("", "Открыть заказы");
        card("");
        press("осушение сдано прорабу", "Сдать работу");
        run("работа оплачена по уровню, стройка возобновилась с прежним остатком", () -> {
            check(panel().feedback().startsWith("Работа принята") && ContractFixtures.unpaid(serverPlayer()) == 5,
                    panel().feedback() + ", к выдаче " + ContractFixtures.unpaid(serverPlayer()));
            check(!ContractFixtures.paused(serverPlayer()) && panel().pauseReason().isEmpty(), "Пауза снята");
            long remaining = panel().cards().stream().filter(entry -> entry.slot() == 1).findFirst().orElseThrow().remaining();
            check(Math.abs(remaining - frozen[0]) < 200, "Остаток срока " + remaining + " после паузы " + frozen[0]);
        });
        shot("flood-paid-room-resumed");

        // FR-33, FR-39, FR-40, FR-45: a voluntary cleanup beside the room in progress
        run("", () -> onServer(() -> {
            ContractFixtures.offerIncident(serverPlayer(), IncidentState.Kind.CLEANUP);
            return null;
        }));
        press("", "Забрать зарплату");
        card("4");
        run("очистку можно принять дополнительно", () -> {
            var cleanup = panel().cards().stream().filter(entry -> entry.slot() == 3).findFirst().orElseThrow();
            check(cleanup.title().equals("Очистка комнаты") && cleanup.canParallel() && cleanup.remaining() < 0
                    && cleanup.wage() == 5, "Очистка без срока за 5 изумрудов");
        });
        shot("cleanup-offer");
        press("очистка принята при активной стройке", "Принять заказ");
        run("стройка продолжается без паузы", () -> check(!ContractFixtures.paused(serverPlayer())
                && ContractFixtures.incident(serverPlayer(), IncidentState.Kind.CLEANUP) != null
                && ContractFixtures.activeBlueprint(serverPlayer()) != null, "Оба заказа активны"));
        closeScreen();
        run("", () -> stand(at(office, 4.5, 1, 2.5), at(office, 3.5, 2.6, 3.5)));
        pause(10);
        run("", () -> interact(BUILDER));
        step("", 100, () -> mc.screen instanceof MerchantScreen);
        run("", () -> {
            MerchantMenu menu = ((MerchantScreen) mc.screen).getMenu();
            check(menu.getOffers().get(10).getBaseCostA().getCount() == 2, "Мокрая тряпка стоит 2 изумруда");
            menu.setSelectionHint(10);
            menu.tryMoveItems(10);
            mc.getConnection().send(new ServerboundSelectTradePacket(10));
        });
        pause(6);
        run("", () -> {
            int menu = ((MerchantScreen) mc.screen).getMenu().containerId;
            mc.gameMode.handleInventoryMouseClick(menu, 2, 0, ClickType.PICKUP, mc.player);
            mc.gameMode.handleInventoryMouseClick(menu, 31, 0, ClickType.PICKUP, mc.player);
        });
        pause(6);
        closeScreen();
        step("мокрая тряпка куплена у строителя", 60, () -> onServer(() -> serverPlayer().getInventory().getItem(1)
                .is(WorkItems.WET_RAG.get())));
        enterPortal(() -> at(office, 8.5, 1, 8.5), ConstructionSite.SITE_LEVEL, "переход на стройку");
        run("", () -> {
            List<BlockPos> webs = ContractFixtures.incident(serverPlayer(), IncidentState.Kind.CLEANUP).initialBlocks();
            check(!webs.isEmpty() && webs.size() <= 2, "На первом уровне до двух паутин");
            stand(beside(webs.get(0)), Vec3.atCenterOf(webs.get(0)));
            hold(2, "minecraft:stone_sword");
        });
        pause(10);
        shot("cleanup-cobwebs-and-graffiti");
        BlockPos[] reached = new BlockPos[1];
        step("паутина убрана", 600, () -> {
            for (BlockPos web : ContractFixtures.incident(serverPlayer(), IncidentState.Kind.CLEANUP).initialBlocks()) {
                if (!mc.level.getBlockState(web).isAir()) {
                    if (!web.equals(reached[0])) {
                        reached[0] = web;
                        stand(beside(web), Vec3.atCenterOf(web));
                        return false;
                    }
                    mc.gameMode.continueDestroyBlock(web, Direction.UP);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    return false;
                }
            }
            return true;
        });
        run("", () -> {
            var marker = GraffitiClient.MARKERS.values().iterator().next();
            stand(beside(marker.pos().relative(marker.face())), Vec3.atCenterOf(marker.pos()));
            mc.player.getInventory().selected = 1;
        });
        pause(10);
        shot("graffiti-on-wall");
        run("", () -> {
            var marker = GraffitiClient.MARKERS.values().iterator().next();
            useOn(marker.pos(), marker.face());
        });
        step("правый клик тряпкой открывает отмывание", 100, () -> mc.screen instanceof GraffitiScreen);
        shot("graffiti-start");
        run("", () -> scrubBand(0));
        step("стёрта верхняя часть рисунка", 100, () -> ContractFixtures.incident(serverPlayer(), IncidentState.Kind.CLEANUP)
                .erasedPatches().cardinality() == 15);
        shot("graffiti-partial");
        closeScreen();
        run("прерванная попытка израсходовала одну единицу тряпки", () -> check(onServer(() -> serverPlayer().getInventory()
                .getItem(1).getDamageValue()) == 1, "Прочность тряпки уменьшилась на 1"));
        pause(10);
        run("", () -> {
            var marker = GraffitiClient.MARKERS.values().iterator().next();
            useOn(marker.pos(), marker.face());
        });
        step("при повторном открытии рисунок сохранил стёртое", 100, () -> mc.screen instanceof GraffitiScreen);
        shot("graffiti-reopened");
        run("", () -> scrubBand(1));
        pause(5);
        run("", () -> scrubBand(2));
        step("рисунок отмыт полностью, мини-игра закрылась", 200, () -> mc.screen == null
                && ContractFixtures.incident(serverPlayer(), IncidentState.Kind.CLEANUP).erasedPatches().cardinality()
                == GraffitiPattern.PATCH_COUNT && GraffitiClient.MARKERS.isEmpty());
        run("вторая попытка израсходовала вторую единицу тряпки", () -> check(onServer(() -> serverPlayer().getInventory()
                .getItem(1).getDamageValue()) == 2, "Прочность тряпки уменьшилась на 2"));
        shot("graffiti-clean-wall");
        enterPortal(() -> new Vec3(2.5, 65, -2.5), Level.OVERWORLD, "возврат в офис");
        openForeman();
        press("", "Открыть заказы");
        card("4");
        press("очистка сдана прорабу", "Сдать работу");
        run("очистка оплачена, комната по-прежнему в работе", () -> check(panel().feedback().startsWith("Работа принята")
                && ContractFixtures.unpaid(serverPlayer()) == 5 && ContractFixtures.activeBlueprint(serverPlayer()) != null
                && ContractFixtures.incident(serverPlayer(), IncidentState.Kind.CLEANUP) == null, panel().feedback()));
        shot("cleanup-paid");
        closeScreen();

        // FR-42: the last call survives a restart
        reloadWorld(mc);
        openForeman();
        run("последний звонок доступен у прораба после перезапуска", () -> check(panel().lastCall().contains("сантехник"),
                "Последний звонок: " + panel().lastCall()));
        shot("last-call-after-reload");
        closeScreen();
    }

    // Scenario: a cancelled room, its fine, a lost and a won appeal, the compensation and the demolition

    private static void finesAndDemolition(Minecraft mc) {
        List<String> argument = List.of("Прошу открыть запись о штрафе.", "Сначала сверим назначенную сумму.",
                "Затем сверим основание с условиями заказа.", "По условиям заказа проверим расчёт.", "Прошу пересмотреть штраф.");
        BlockPos[] planks = new BlockPos[2];
        hire(mc);
        openForeman();
        press("меню заказов открыто", "Открыть заказы");
        run("карточка перечисляет обязательные условия словами", () -> check(panel().cards().get(0).conditions()
                .contains("Внутри 5×5, квадратная") && panel().cards().get(1).conditions().size() >= 5, "Условия на карточке"));
        shot("offer-with-conditions");
        run("", () -> button("2").onPress());
        shot("generated-offer-with-conditions");
        run("", () -> button("1").onPress());
        press("пробная комната принята", "Принять заказ");
        closeScreen();
        run("в стене будущей комнаты поставлены два блока досок", () -> onServer(() -> {
            RoomBlueprint room = ContractFixtures.activeBlueprint(serverPlayer());
            planks[0] = new BlockPos(room.bounds().max().getX(), room.bounds().min().getY() + 1, room.bounds().min().getZ() + 1);
            planks[1] = planks[0].above();
            for (BlockPos pos : planks) {
                server().getLevel(ConstructionSite.SITE_LEVEL).setBlock(pos, Blocks.OAK_PLANKS.defaultBlockState(), PLACE);
            }
            return null;
        }));

        // FR-149, FR-131, FR-70: cancellation
        openForeman();
        press("", "Открыть заказы");
        press("заказ отменён у прораба", "Отменить");
        run("один штраф по формуле, один снос, место в списке обновлено", () -> {
            check(panel().feedback().equals("Заказ отменён. Штраф: 25 изумрудов."), "146 позиций и 5 критериев: " + panel().feedback());
            check(ContractFixtures.fines(serverPlayer()) == 1 && ContractFixtures.demolitions(serverPlayer()) == 1
                    && ContractFixtures.activeBlueprint(serverPlayer()) == null, "Один штраф и один снос, слот свободен");
            check(panel().cards().stream().anyMatch(card -> card.slot() == 0 && !card.title().equals("Пробная комната")),
                    "В первом месте новое предложение");
        });
        shot("order-cancelled");
        closeScreen();

        // FR-53, FR-54, FR-55: the fine list and two appeal attempts
        openForeman();
        shot("dialogue-with-fine-and-demolition");
        press("экран штрафов открыт из диалога", "Штрафы");
        run("штраф за отмену виден целиком, доступны две попытки", () -> {
            var fine = fines().fines().get(0);
            check(fine.amount() == 25 && fine.outstanding() == 25 && fine.attemptsLeft() == 2 && !fine.overturned(),
                    "Штраф 25, остаток 25, попыток 2");
        });
        shot("fine-list");
        press("первая попытка спора началась", "Оспорить штраф");
        run("в попытке семь карточек и таймер на 15 секунд", () -> {
            var attempt = fines().attempt();
            check(attempt != null && attempt.cards().size() == 7 && attempt.remainingMillis() > 13_000
                    && attempt.required() >= 1 && attempt.required() <= 5, "Семь карточек, таймер идёт");
            REPORT.add("Нужно карточек: " + attempt.required() + ". " + attempt.hint());
        });
        shot("appeal-attempt");
        Screen[] before = new Screen[1];
        run("", () -> {
            before[0] = mc.screen;
            String wrong = fines().attempt().cards().stream().map(card -> card.text())
                    .filter(text -> !argument.contains(text)).findFirst().orElseThrow();
            button(wrong).onPress();
        });
        step("неверная карточка завершает попытку без зачёта", 100, () -> mc.screen instanceof FineScreen
                && mc.screen != before[0] && fines().attempt() == null);
        run("осталась одна попытка, штраф в силе", () -> {
            check(fines().feedback().startsWith("Аргумент не принят"), fines().feedback());
            check(fines().fines().get(0).attemptsLeft() == 1 && !fines().fines().get(0).overturned(), "Одна попытка осталась");
        });
        shot("appeal-wrong-order");
        press("вторая попытка началась", "Оспорить штраф");
        int[] chosen = {0};
        step("верная последовательность выигрывает спор", 300, () -> {
            if (!(mc.screen instanceof FineScreen) || mc.screen == before[0]) {
                return false;
            }
            if (fines().attempt() == null) {
                return true;
            }
            before[0] = mc.screen;
            button(argument.get(chosen[0]++)).onPress();
            return false;
        });
        run("штраф отменён, компенсация равна штрафу плюс оставшиеся секунды", () -> {
            check(fines().feedback().startsWith("Штраф отменён. Возврат 0, компенсация "), fines().feedback());
            long unpaid = ContractFixtures.unpaid(serverPlayer());
            check(fines().fines().get(0).overturned() && unpaid >= 25 + 8 && unpaid <= 25 + 15, "К выдаче " + unpaid);
            REPORT.add(fines().feedback());
        });
        shot("appeal-won");
        press("возврат к заказам и выплатам", "К заказам и выплатам");
        press("компенсация выдана изумрудами", "Забрать зарплату");
        run("компенсация получена один раз", () -> check(carried(Items.EMERALD) >= 33
                && ContractFixtures.unpaid(serverPlayer()) == 0, "Изумрудов " + carried(Items.EMERALD)));
        shot("compensation-claimed");
        closeScreen();

        // FR-30, FR-31, FR-150, FR-152: the demolition
        openForeman();
        press("экран сносов открыт из диалога", "Сносы");
        shot("demolition-not-started");
        press("", "Сдать материалы");
        run("снос не принимается, пока постройка не разобрана", () -> check(
                demolitions().feedback().startsWith("Разбери постройку") && ContractFixtures.demolitions(serverPlayer()) == 1,
                demolitions().feedback()));
        closeScreen();
        enterPortal(() -> at(office, 8.5, 1, 8.5), ConstructionSite.SITE_LEVEL, "переход на стройку к недострою");
        run("", () -> stand(new Vec3(5.5, 65, planks[0].getZ() + 0.5), Vec3.atCenterOf(planks[1])));
        shot("demolition-on-site");
        for (int index = 1; index >= 0; index--) {
            int block = index;
            step("блок недостроя разобран руками в выживании", 300, () -> {
                if (mc.level.getBlockState(planks[block]).isAir()) {
                    return true;
                }
                mc.gameMode.continueDestroyBlock(planks[block], net.minecraft.core.Direction.EAST);
                mc.player.swing(InteractionHand.MAIN_HAND);
                return false;
            });
        }
        pause(20);
        enterPortal(() -> new Vec3(2.5, 65, -2.5), Level.OVERWORLD, "возврат в офис");
        run("", () -> command("clear @s minecraft:oak_planks"));
        openForeman();
        press("", "Сносы");
        run("список сдачи равен фактической добыче", () -> {
            var materials = demolitions().cards().get(0).materials();
            check(materials.size() == 1 && materials.get(0).label().equals("block.minecraft.oak_planks")
                    && materials.get(0).needed() == 2, "К сдаче две дубовые доски: " + materials);
        });
        shot("demolition-materials");
        press("", "Сдать материалы");
        run("без материалов снос не принят", () -> check(demolitions().feedback().startsWith("Принеси все материалы")
                && ContractFixtures.demolitions(serverPlayer()) == 1, demolitions().feedback()));
        run("", () -> command("give @s minecraft:oak_planks 3"));
        press("", "Сдать материалы");
        run("снос принят, списаны только две доски из запасов", () -> check(demolitions().feedback().equals("Снос принят.")
                && ContractFixtures.demolitions(serverPlayer()) == 0 && carried(Items.OAK_PLANKS) == 1,
                demolitions().feedback() + ", досок " + carried(Items.OAK_PLANKS)));
        shot("demolition-accepted");
        closeScreen();
    }

    // Scenario: from the first meeting with the builder to the first wage and a reload

    private static void mainCycle(Minecraft mc) {
        UUID[] npcs = new UUID[3];
        enterFreshWorld(mc);

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
        reloadWorld(mc);
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
