package io.github.zelenusha.buildersmod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.zelenusha.buildersmod.BuildersMod;
import io.github.zelenusha.buildersmod.maintenance.GraffitiPattern;
import io.github.zelenusha.buildersmod.site.ConstructionSite;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = BuildersMod.MOD_ID, value = Dist.CLIENT)
public final class GraffitiClient {
    private static final Map<UUID, Marker> MARKERS = new HashMap<>();

    private record Marker(BlockPos pos, Direction face, BitSet erased) {}

    private GraffitiClient() { }

    public static void updateMarker(UUID incident, BlockPos pos, Direction face, BitSet erased) {
        if (pos == null) {
            MARKERS.remove(incident);
        } else {
            MARKERS.put(incident, new Marker(pos, face, erased));
        }
    }

    public static void open(UUID token, BitSet erased) {
        Minecraft.getInstance().setScreen(new GraffitiScreen(token, erased));
    }

    public static void progress(UUID token, UUID incident, BitSet erased, boolean finished) {
        Marker marker = MARKERS.get(incident);
        if (marker != null) {
            marker.erased().or(erased);
        }
        if (Minecraft.getInstance().screen instanceof GraffitiScreen screen) {
            screen.progress(token, erased, finished);
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        MARKERS.clear();
    }

    @SubscribeEvent
    public static void renderMarkers(RenderLevelStageEvent event) {
        Minecraft game = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || MARKERS.isEmpty()
                || game.level == null || game.level.dimension() != ConstructionSite.SITE_LEVEL) {
            return;
        }
        var camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        var buffers = game.renderBuffers().bufferSource();
        var polygons = buffers.getBuffer(RenderType.debugFilledBox());
        for (Marker marker : MARKERS.values()) {
            BlockPos pos = marker.pos();
            if (pos.distToCenterSqr(camera.x, camera.y, camera.z) > 32 * 32) {
                continue;
            }
            double faceSize = 0.84;
            double pitch = faceSize / GraffitiPattern.WIDTH;
            for (int y = 0; y < GraffitiPattern.HEIGHT; y++) {
                for (int x = 0; x < GraffitiPattern.WIDTH; x++) {
                    int patch = GraffitiPattern.patchAt(x, y);
                    if (patch < 0 || marker.erased().get(patch)) {
                        continue;
                    }
                    double left = 0.08 + x * pitch;
                    double right = left + pitch;
                    double low = 0.67 - (y + 1) * pitch;
                    double high = low + pitch;
                    if (marker.face().getAxis() == Direction.Axis.X) {
                        double surface = pos.getX() + (marker.face() == Direction.EAST ? 1.003 : -0.005);
                        double start = pos.getZ() + (marker.face() == Direction.WEST ? left : 1 - right);
                        LevelRenderer.addChainedFilledBoxVertices(pose, polygons, surface, pos.getY() + low, start,
                                surface + 0.002, pos.getY() + high, start + pitch, 0.21F, 0.68F, 0.53F, 0.82F);
                    } else {
                        double surface = pos.getZ() + (marker.face() == Direction.SOUTH ? 1.003 : -0.005);
                        double start = pos.getX() + (marker.face() == Direction.SOUTH ? left : 1 - right);
                        LevelRenderer.addChainedFilledBoxVertices(pose, polygons, start, pos.getY() + low, surface,
                                start + pitch, pos.getY() + high, surface + 0.002, 0.21F, 0.68F, 0.53F, 0.82F);
                    }
                }
            }
        }
        buffers.endBatch(RenderType.debugFilledBox());
        pose.popPose();
    }
}
