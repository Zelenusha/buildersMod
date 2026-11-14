package io.github.zelenusha.buildersmod.site;

import com.mojang.authlib.GameProfile;
import io.github.zelenusha.buildersmod.BuildersMod;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

@GameTestHolder(BuildersMod.MOD_ID)
@PrefixGameTestTemplate(false)
public class SitePortalGameTests {
    @GameTest(template = "empty")
    public static void siteLoadsItsPortalAndReadsOverworldTime(GameTestHelper helper) {
        ServerLevel overworld = helper.getLevel().getServer().overworld();
        ServerLevel site = helper.getLevel().getServer().getLevel(ConstructionSite.SITE_LEVEL);
        helper.assertTrue(site != null, "The construction dimension must be registered");
        PortalLocation portal = ConstructionSite.RETURN_PORTAL;
        site.getChunkAt(portal.lowerLeft());
        assertPortal(helper, site, portal);

        long original = overworld.getDayTime();
        try {
            for (long phase : new long[] {2_000L, 14_000L}) {
                overworld.setDayTime(phase);
                helper.assertTrue(site.getDayTime() == phase, "Construction site must read the current Overworld day time");
                helper.assertTrue(site.getTimeOfDay(0.0F) == overworld.getTimeOfDay(0.0F),
                        "Both dimensions use the same daylight cycle");
            }
        } finally {
            overworld.setDayTime(original);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void protectsBothArrivalVolumesFromForgePlacementAndBreak(GameTestHelper helper) {
        ServerLevel overworld = helper.getLevel().getServer().overworld();
        ServerLevel site = helper.getLevel().getServer().getLevel(ConstructionSite.SITE_LEVEL);
        helper.assertTrue(site != null, "The construction dimension must be registered");
        PortalLocation entrance = new PortalLocation(helper.absolutePos(new BlockPos(0, 8, 0)), Direction.Axis.Z,
                helper.absolutePos(new BlockPos(2, 9, 1)));
        FakePlayer officeOwner = new FakePlayer(overworld, new GameProfile(UUID.randomUUID(), "portal-office"));
        installOfficeFixture(overworld, officeOwner, entrance);

        PortalLocation stored = SiteLinks.get(overworld.getServer()).forPlayer(officeOwner.getUUID());
        helper.assertTrue(entrance.equals(stored), "The installed office portal must record its owner's return location");
        assertPortal(helper, overworld, entrance);
        assertProtectedArrival(helper, overworld, officeOwner, entrance);

        PortalLocation returnPortal = ConstructionSite.RETURN_PORTAL;
        site.getChunkAt(returnPortal.lowerLeft());
        site.getChunkAt(returnPortal.arrival());
        FakePlayer sitePlayer = new FakePlayer(site, new GameProfile(UUID.randomUUID(), "portal-site"));
        assertProtectedArrival(helper, site, sitePlayer, returnPortal);
        helper.succeed();
    }

    private static void installOfficeFixture(ServerLevel overworld, FakePlayer owner, PortalLocation entrance) {
        overworld.setBlock(entrance.arrival().below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        overworld.setBlock(entrance.arrival(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        overworld.setBlock(entrance.arrival().above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        ConstructionSite.installOfficePortal(owner, overworld, entrance.lowerLeft(), entrance.axis(), entrance.arrival());
    }

    private static void assertPortal(GameTestHelper helper, ServerLevel level, PortalLocation portal) {
        Block registeredPortal = ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse(BuildersMod.MOD_ID + ":site_portal"));
        helper.assertTrue(registeredPortal instanceof SitePortalBlock, "Registered block must be the site portal surface");
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 5; y++) {
                BlockPos pos = portal.at(x, y);
                BlockState state = level.getBlockState(pos);
                if (portal.containsOpening(pos)) {
                    helper.assertTrue(state.is(registeredPortal) && state.getValue(SitePortalBlock.AXIS) == portal.axis(),
                            "Opening must contain the registered portal block in the specified plane");
                    helper.assertTrue(state.getCollisionShape(level, pos).isEmpty(), "The opening must allow walking through");
                } else {
                    helper.assertTrue(state.is(Blocks.DEEPSLATE_BRICKS), "A 4 by 5 frame must surround the portal");
                }
            }
        }
        helper.assertTrue(!portal.containsOpening(portal.arrival()) && level.isEmptyBlock(portal.arrival())
                && level.isEmptyBlock(portal.arrival().above()), "Arrival must be clear and outside the portal plane");
    }

    private static void assertProtectedArrival(GameTestHelper helper, ServerLevel level, FakePlayer player,
                                               PortalLocation portal) {
        BlockPos feet = portal.arrival();
        BlockPos head = feet.above();
        BlockPos nearby = feet.west();
        helper.assertTrue(placeCanceled(level, player, feet), "Placement at feet must be denied");
        helper.assertTrue(placeCanceled(level, player, head), "Placement at head must be denied");
        helper.assertTrue(!placeCanceled(level, player, nearby), "Other room positions must allow ordinary placement");
        BlockEvent.EntityMultiPlaceEvent multi = new BlockEvent.EntityMultiPlaceEvent(
                List.of(snapshot(level, nearby), snapshot(level, head)), Blocks.STONE.defaultBlockState(), player);
        helper.assertTrue(MinecraftForge.EVENT_BUS.post(multi), "An otherwise valid multi-block placement cannot obstruct the head");
        helper.assertTrue(breakCanceled(level, player, feet.below()), "The floor beneath the arrival must remain intact");
        helper.assertTrue(breakCanceled(level, player, portal.lowerLeft()), "The portal frame cannot be broken");
    }

    private static boolean placeCanceled(ServerLevel level, FakePlayer player, BlockPos pos) {
        return MinecraftForge.EVENT_BUS.post(new BlockEvent.EntityPlaceEvent(snapshot(level, pos),
                Blocks.STONE.defaultBlockState(), player));
    }

    private static BlockSnapshot snapshot(ServerLevel level, BlockPos pos) {
        return BlockSnapshot.create(level.dimension(), level, pos);
    }

    private static boolean breakCanceled(ServerLevel level, FakePlayer player, BlockPos pos) {
        return MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, pos, level.getBlockState(pos), player));
    }
}
