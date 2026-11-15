package io.github.zelenusha.buildersmod.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.zelenusha.buildersmod.BuildersMod;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint;
import io.github.zelenusha.buildersmod.contract.ContractBalance;
import io.github.zelenusha.buildersmod.contract.ContractNetwork;
import io.github.zelenusha.buildersmod.intro.IntroNpcs;
import io.github.zelenusha.buildersmod.site.ConstructionSite;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = BuildersMod.MOD_ID, value = Dist.CLIENT)
public final class ContractClient {
    private static final KeyMapping TOGGLE = new KeyMapping("key.buildersmod.blueprint", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_B, "key.categories.buildersmod");
    private static RoomBlueprint blueprint;
    private static String title;
    private static long remaining;
    private static boolean visible = true;
    private static List<String> tasks = List.of();
    private static boolean paused;
    private static String call;
    private static int callTicks;

    private ContractClient() { }

    public static void open(ContractNetwork.Panel panel) {
        Minecraft.getInstance().setScreen(new ForemanScreen(panel));
    }

    public static void openDemolitions(ContractNetwork.DemolitionPanel panel) {
        Minecraft.getInstance().setScreen(new DemolitionScreen(panel));
    }

    public static void openFines(ContractNetwork.FinePanel panel) {
        Minecraft.getInstance().setScreen(new FineScreen(panel));
    }

    public static void updateTasks(List<String> names, boolean isPaused) {
        tasks = names;
        paused = isPaused;
    }

    public static void showCall(String message) {
        call = message;
        callTicks = 160;
    }

    public static void updateSite(ContractNetwork.SiteView view) {
        if (view.blueprint() == null) {
            clear();
        } else {
            blueprint = RoomBlueprint.decode(view.blueprint());
            title = view.title();
            remaining = view.remaining();
        }
    }

    public static void updateTimer(boolean active, long ticks) {
        if (active) {
            remaining = ticks;
        } else {
            clear();
        }
    }

    private static void clear() {
        blueprint = null;
        title = null;
        remaining = 0;
    }

    static String days(long ticks) {
        if (ticks <= 0) {
            return "0 дней";
        }
        BigDecimal value = BigDecimal.valueOf(ticks)
                .divide(BigDecimal.valueOf(ContractBalance.TICKS_PER_DAY), 2, RoundingMode.HALF_UP);
        if (value.signum() == 0) {
            return "<0,01 дня";
        }
        BigDecimal display = value.stripTrailingZeros();
        String units = "дня";
        if (display.scale() <= 0) {
            long count = display.longValueExact();
            long lastTwo = count % 100;
            long last = count % 10;
            units = lastTwo >= 11 && lastTwo <= 14 ? "дней" : last == 1 ? "день" : last >= 2 && last <= 4 ? "дня" : "дней";
        }
        return display.toPlainString().replace('.', ',') + " " + units;
    }

    @SubscribeEvent
    public static void logOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
        tasks = List.of();
        paused = false;
        callTicks = 0;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            while (TOGGLE.consumeClick()) {
                visible = !visible;
            }
            Minecraft game = Minecraft.getInstance();
            if (callTicks > 0 && game.screen == null && game.level != null && !game.options.hideGui) {
                callTicks--;
            }
        }
    }

    @SubscribeEvent
    public static void renderBlueprint(RenderLevelStageEvent event) {
        Minecraft game = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS
                || !visible || blueprint == null || game.level == null
                || game.level.dimension() != ConstructionSite.SITE_LEVEL) {
            return;
        }

        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        MultiBufferSource.BufferSource buffers = game.renderBuffers().bufferSource();
        VertexConsumer faces = buffers.getBuffer(RenderType.debugFilledBox());
        var outlines = new ArrayList<BlockPos>();
        for (var entry : blueprint.requirements().entrySet()) {
            BlockPos position = entry.getKey();
            if (!game.level.hasChunkAt(position) || position.distToCenterSqr(camera.x, camera.y, camera.z) > 64 * 64) {
                continue;
            }
            RoomBlueprint.Status status = blueprint.checkPosition(game.level, position);
            if (status == RoomBlueprint.Status.CORRECT) {
                continue;
            }
            if (status == RoomBlueprint.Status.MISSING) {
                var shape = entry.getValue().expectedState().getShape(game.level, position);
                if (shape.isEmpty()) {
                    drawSilhouette(pose, faces, position, new AABB(0.25, 0.0, 0.25, 0.75, 0.75, 0.75));
                } else {
                    for (AABB part : shape.toAabbs()) {
                        drawSilhouette(pose, faces, position, part);
                    }
                }
            } else {
                outlines.add(position);
            }
        }
        buffers.endBatch(RenderType.debugFilledBox());
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        for (BlockPos position : outlines) {
            double x = position.getX();
            double y = position.getY();
            double z = position.getZ();
            LevelRenderer.renderLineBox(pose, lines, x - 0.002, y - 0.002, z - 0.002,
                    x + 1.002, y + 1.002, z + 1.002, 1.0F, 0.2F, 0.15F, 0.92F);
        }
        buffers.endBatch(RenderType.lines());
        pose.popPose();
    }

    private static void drawSilhouette(PoseStack pose, VertexConsumer faces, BlockPos position, AABB shape) {
        AABB box = shape.move(position).deflate(0.002);
        LevelRenderer.addChainedFilledBoxVertices(pose, faces, box.minX, box.minY, box.minZ, box.maxX, box.maxY,
                box.maxZ, 0.22F, 0.58F, 1.0F, 0.25F);
    }

    @Mod.EventBusSubscriber(modid = BuildersMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Registrations {
        private Registrations() { }

        @SubscribeEvent
        public static void key(RegisterKeyMappingsEvent event) {
            event.register(TOGGLE);
        }

        @SubscribeEvent
        public static void overlay(RegisterGuiOverlaysEvent event) {
            event.registerAboveAll("contract_notice", (gui, graphics, partialTick, width, height) -> {
                Minecraft game = Minecraft.getInstance();
                if (game.options.hideGui) {
                    return;
                }
                var font = game.font;
                boolean showCall = callTicks > 0 && game.screen == null;
                boolean stackCall = showCall && width < 240;
                int callTextWidth = stackCall ? Math.max(1, width - 47) : Math.min(width / 2 - 36, 200);
                int callLeft = width - callTextWidth - 32;
                int labelWidth = Math.max(1, (showCall && !stackCall ? callLeft - 5 : width - 5) - 12);
                int y = 5;
                var labels = new ArrayList<String>();
                if (blueprint != null) {
                    labels.add(title + (paused ? " · пауза" : "") + " — " + days(remaining));
                }
                labels.addAll(tasks);
                for (String text : labels) {
                    var lines = font.split(Component.literal(text), labelWidth);
                    int boxWidth = lines.stream().mapToInt(font::width).max().orElse(0);
                    int bottom = y + lines.size() * font.lineHeight + 6;
                    graphics.fill(5, y, 12 + boxWidth, bottom, 0xA0232A33);
                    int textY = y + 4;
                    for (var line : lines) {
                        graphics.drawString(font, line, 8, textY, 0xFFFFFF, false);
                        textY += font.lineHeight;
                    }
                    y = bottom + 1;
                }
                if (showCall) {
                    int top = stackCall ? y + 3 : 5;
                    var message = Component.literal(call);
                    int bottom = top + Math.max(32, 23 + font.wordWrapHeight(message, callTextWidth));
                    graphics.fill(callLeft, top, width - 5, bottom, 0xD0232A33);
                    PlayerFaceRenderer.draw(graphics, IntroNpcs.FOREMAN_SKIN, callLeft + 5, top + 6, 16);
                    graphics.drawString(font, "Прораб", callLeft + 25, top + 5, 0x8BCCF3, false);
                    graphics.drawWordWrap(font, message, callLeft + 25, top + 17, callTextWidth, 0xFFFFFF);
                }
            });
        }
    }
}
