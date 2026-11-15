package io.github.zelenusha.buildersmod.client;

import io.github.zelenusha.buildersmod.BuildersMod;
import io.github.zelenusha.buildersmod.intro.IntroNpcs;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = BuildersMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class IntroNpcRenderer extends MobRenderer<IntroNpcs, PlayerModel<IntroNpcs>> {
    private final ResourceLocation skin;

    private IntroNpcRenderer(EntityRendererProvider.Context context, ResourceLocation skin) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        this.skin = skin;
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(IntroNpcs.BUILDER.get(), context -> new IntroNpcRenderer(context, IntroNpcs.BUILDER_SKIN));
        event.registerEntityRenderer(IntroNpcs.FOREMAN.get(), context -> new IntroNpcRenderer(context, IntroNpcs.FOREMAN_SKIN));
    }

    @Override
    public ResourceLocation getTextureLocation(IntroNpcs npc) {
        return skin;
    }
}
