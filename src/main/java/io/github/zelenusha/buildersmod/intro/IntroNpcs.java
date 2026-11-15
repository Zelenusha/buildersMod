package io.github.zelenusha.buildersmod.intro;

import io.github.zelenusha.buildersmod.BuildersMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class IntroNpcs extends Villager {
    private static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, BuildersMod.MOD_ID);

    public static final RegistryObject<EntityType<IntroNpcs>> BUILDER = ENTITIES.register("builder", () -> type("builder"));
    public static final RegistryObject<EntityType<IntroNpcs>> FOREMAN = ENTITIES.register("foreman", () -> type("foreman"));
    public static final ResourceLocation BUILDER_SKIN =
            ResourceLocation.fromNamespaceAndPath(BuildersMod.MOD_ID, "textures/entity/builder.png");
    public static final ResourceLocation FOREMAN_SKIN =
            ResourceLocation.fromNamespaceAndPath(BuildersMod.MOD_ID, "textures/entity/foreman.png");

    private IntroNpcs(EntityType<? extends Villager> type, Level level) {
        super(type, level);
    }

    private static EntityType<IntroNpcs> type(String name) {
        return EntityType.Builder.<IntroNpcs>of(IntroNpcs::new, MobCategory.MISC).sized(0.6F, 1.8F)
                .clientTrackingRange(10).build(BuildersMod.MOD_ID + ":" + name);
    }

    public static void register(IEventBus bus) {
        ENTITIES.register(bus);
        bus.addListener(IntroNpcs::attributes);
    }

    private static void attributes(EntityAttributeCreationEvent event) {
        event.put(BUILDER.get(), Villager.createAttributes().build());
        event.put(FOREMAN.get(), Villager.createAttributes().build());
    }
}
