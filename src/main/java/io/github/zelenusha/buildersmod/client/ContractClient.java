package io.github.zelenusha.buildersmod.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.zelenusha.buildersmod.BuildersMod;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint;
import io.github.zelenusha.buildersmod.contract.ContractBalance;
import io.github.zelenusha.buildersmod.contract.ContractNetwork;
import io.github.zelenusha.buildersmod.site.ConstructionSite;
import java.math.BigDecimal;
import java.math.RoundingMode;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
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
        return value.stripTrailingZeros().toPlainString().replace('.', ',') + " дн.";
    }

    @SubscribeEvent
    public static void logOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            while (TOGGLE.consumeClick()) {
                visible = !visible;
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
        var outlines = new java.util.ArrayList<BlockPos>();
        for (BlockPos position : blueprint.requirements().keySet()) {
            if (!game.level.hasChunkAt(position) || position.distToCenterSqr(camera.x, camera.y, camera.z) > 64 * 64) {
                continue;
            }
            RoomBlueprint.Status status = blueprint.checkPosition(game.level, position);
            if (status == RoomBlueprint.Status.CORRECT) {
                continue;
            }
            double x = position.getX();
            double y = position.getY();
            double z = position.getZ();
            if (status == RoomBlueprint.Status.MISSING) {
                LevelRenderer.addChainedFilledBoxVertices(pose, faces, x + 0.006, y + 0.006, z + 0.006,
                        x + 0.994, y + 0.994, z + 0.994, 0.22F, 0.58F, 1.0F, 0.25F);
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
                if (blueprint != null && !Minecraft.getInstance().options.hideGui) {
                    String text = title + " — " + days(remaining);
                    var font = Minecraft.getInstance().font;
                    graphics.fill(5, 5, 12 + font.width(text), 21, 0xA0232A33);
                    graphics.drawString(font, text, 8, 9, 0xFFFFFF, false);
                }
            });
        }
    }
}
