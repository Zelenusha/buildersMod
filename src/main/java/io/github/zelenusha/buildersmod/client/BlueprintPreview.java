package io.github.zelenusha.buildersmod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.entity.BedBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.client.model.pipeline.VertexConsumerWrapper;

final class BlueprintPreview {
    private static final RenderType BLOCKS = RenderType.entityTranslucent(TextureAtlas.LOCATION_BLOCKS);
    private static final RenderType BEDS = RenderType.entityTranslucent(Sheets.BED_SHEET);

    private BlueprintPreview() { }

    static void draw(Minecraft game, MultiBufferSource.BufferSource buffers, PoseStack pose, BlockPos position, BlockState state) {
        pose.pushPose();
        pose.translate(position.getX(), position.getY(), position.getZ());
        try {
            if (state.getBlock() instanceof BedBlock block) {
                // The preview needs the part at this position. The inventory renderer draws a whole bed.
                var bed = new BedBlockEntity(position, state, block.getColor());
                bed.setLevel(game.level);
                var renderer = game.getBlockEntityRenderDispatcher().getRenderer(bed);
                if (renderer != null) {
                    renderer.render(bed, 0, pose, ghostBuffers(buffers, BEDS), LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
                }
            } else {
                game.getBlockRenderer().renderSingleBlock(state, pose, ghostBuffers(buffers, BLOCKS),
                        LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, BLOCKS);
            }
        } finally {
            pose.popPose();
        }
    }

    static void flush(MultiBufferSource.BufferSource buffers) {
        buffers.endBatch(BLOCKS);
        buffers.endBatch(BEDS);
    }

    private static MultiBufferSource ghostBuffers(MultiBufferSource.BufferSource source, RenderType layer) {
        return type -> new VertexConsumerWrapper(source.getBuffer(layer)) {
            @Override
            public VertexConsumer color(int red, int green, int blue, int alpha) {
                parent.color(red * 7 / 8, green * 15 / 16, blue, alpha * 145 / 255);
                return this;
            }
        };
    }
}
