package io.github.zelenusha.buildersmod.maintenance;

import io.github.zelenusha.buildersmod.BuildersMod;
import net.minecraft.client.renderer.entity.VindicatorRenderer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class RoomResident extends Vindicator {
    private static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, BuildersMod.MOD_ID);
    static final RegistryObject<EntityType<RoomResident>> TYPE = ENTITIES.register("resident", () ->
            EntityType.Builder.<RoomResident>of(RoomResident::new, MobCategory.MISC).sized(0.6F, 1.95F)
                    .clientTrackingRange(8).build(BuildersMod.MOD_ID + ":resident"));

    private RoomResident(EntityType<? extends Vindicator> type, Level level) {
        super(type, level);
    }

    public static void register(IEventBus modBus) {
        ENTITIES.register(modBus);
        modBus.addListener(RoomResident::attributes);
    }

    private static void attributes(EntityAttributeCreationEvent event) {
        event.put(TYPE.get(), Vindicator.createAttributes().build());
    }

    @Override
    protected boolean shouldDespawnInPeaceful() {
        return false;
    }

    @Mod.EventBusSubscriber(modid = BuildersMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Client {
        private Client() { }

        @SubscribeEvent
        public static void registerRenderer(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(TYPE.get(), VindicatorRenderer::new);
        }
    }
}
