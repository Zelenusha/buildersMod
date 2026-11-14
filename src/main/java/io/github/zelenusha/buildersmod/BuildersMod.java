package io.github.zelenusha.buildersmod;

import io.github.zelenusha.buildersmod.contract.ContractNetwork;
import io.github.zelenusha.buildersmod.intro.IntroEvents;
import io.github.zelenusha.buildersmod.maintenance.WorkItems;
import io.github.zelenusha.buildersmod.site.ConstructionSite;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(BuildersMod.MOD_ID)
public final class BuildersMod {
    public static final String MOD_ID = "buildersmod";

    public BuildersMod(FMLJavaModLoadingContext context) {
        ConstructionSite.register(context.getModEventBus());
        WorkItems.register(context.getModEventBus());
        IntroEvents.register(MinecraftForge.EVENT_BUS);
        ContractNetwork.register();
    }
}
