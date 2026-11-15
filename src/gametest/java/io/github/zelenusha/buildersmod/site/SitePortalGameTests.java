package io.github.zelenusha.buildersmod.site;

import com.mojang.authlib.GameProfile;
import io.github.zelenusha.buildersmod.BuildersMod;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
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

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void lightsNewAndExistingDecksWhilePreservingWorkAreas(GameTestHelper helper) {
        ServerLevel site = helper.getLevel().getServer().getLevel(ConstructionSite.SITE_LEVEL);
        BlockPos light = new BlockPos(120_132, ConstructionSite.START_FLOOR_Y, 120_132);
        site.getChunkAt(light);
        for (int floor = 0; floor < ConstructionSite.FLOOR_COUNT; floor++) {
            BlockPos lamp = light.above(floor * ConstructionSite.FLOOR_STEP);
            helper.assertTrue(site.getBlockState(lamp).is(Blocks.SEA_LANTERN) && site.isEmptyBlock(lamp.above()),
                    "Every new deck has a fixture flush with its walking surface");
        }
        var links = new SiteLinks();
        BlockPos reserved = light.east(8);
        BlockPos modified = light.south(8);
        BlockState beforeReserved = site.getBlockState(reserved);
        BlockState beforeModified = site.getBlockState(modified);
        try {
            site.setBlock(light, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            site.setBlock(reserved, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            site.setBlock(modified, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_CLIENTS);
            SiteLighting.retrofit(site, new ChunkPos(light), links, reserved::equals);
            helper.assertTrue(site.getBlockState(light).is(Blocks.SEA_LANTERN) && site.getBlockState(reserved).is(Blocks.STONE)
                            && site.getBlockState(modified).is(Blocks.OAK_PLANKS),
                    "The retrofit illuminates bare stone and preserves work areas and installed flooring");
            site.setBlock(light, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            SiteLighting.retrofit(site, new ChunkPos(light), links, pos -> false);
            helper.assertTrue(site.getBlockState(light).is(Blocks.STONE)
                            && links.save(new net.minecraft.nbt.CompoundTag()).getLongArray("CheckedLighting").length == 1,
                    "The saved retrofit marker makes the operation one-time");
        } finally {
            site.setBlock(light, Blocks.SEA_LANTERN.defaultBlockState(), Block.UPDATE_CLIENTS);
            site.setBlock(reserved, beforeReserved, Block.UPDATE_CLIENTS);
            site.setBlock(modified, beforeModified, Block.UPDATE_CLIENTS);
        }
        helper.succeedWhen(() -> helper.assertTrue(site.getBrightness(LightLayer.BLOCK, light.above()) >= 13,
                "The deck is illuminated independently of sunlight"));
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

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void ownerTravelsThroughBothPortalsWithVanillaDimensionPackets(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        ServerLevel overworld = server.overworld();
        ServerLevel site = server.getLevel(ConstructionSite.SITE_LEVEL);
        helper.assertTrue(site != null, "The construction dimension must be registered");
        PortalLocation entrance = new PortalLocation(helper.absolutePos(new BlockPos(0, 8, 0)), Direction.Axis.X,
                helper.absolutePos(new BlockPos(1, 9, 3)));
        ServerPlayer player = new ServerPlayer(server, overworld, new GameProfile(UUID.randomUUID(), "portal-roundtrip"));
        RecordingConnection connection = new RecordingConnection(player);
        long originalDayTime = overworld.getDayTime();

        try {
            installOfficeFixture(overworld, player, entrance);
            moveTo(player, entrance.at(1, 1));
            overworld.addNewPlayer(player);
            helper.assertTrue(overworld.getEntity(player.getUUID()) == player, "The real player is registered in the source level");

            overworld.setDayTime(4_000L);
            connection.packets.clear();
            triggerPortal(player);
            assertArrival(helper, player, connection, overworld, site, ConstructionSite.SITE_ARRIVAL);
            helper.assertTrue(site.getDayTime() == 4_000L, "Midday carries through the entry portal");

            moveTo(player, ConstructionSite.RETURN_PORTAL.at(1, 1));
            overworld.setDayTime(16_000L);
            connection.packets.clear();
            triggerPortal(player);
            assertArrival(helper, player, connection, site, overworld, entrance.arrival());
            helper.assertTrue(overworld.getDayTime() == 16_000L, "Night carries through the return portal");

            connection.packets.clear();
            triggerPortal(player);
            helper.assertTrue(player.serverLevel() == overworld && connection.packets.stream()
                    .noneMatch(ClientboundRespawnPacket.class::isInstance), "Standing at arrival must not send the player back");
        } finally {
            player.serverLevel().removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
            overworld.setDayTime(originalDayTime);
        }
        helper.succeed();
    }

    private static void moveTo(ServerPlayer player, BlockPos block) {
        player.moveTo(block.getX() + 0.5, block.getY(), block.getZ() + 0.5, 0.0F, 0.0F);
    }

    private static void triggerPortal(ServerPlayer player) {
        MinecraftForge.EVENT_BUS.post(new TickEvent.PlayerTickEvent(TickEvent.Phase.END, player));
    }

    private static void assertArrival(GameTestHelper helper, ServerPlayer player, RecordingConnection connection,
                                      ServerLevel source, ServerLevel target, BlockPos arrival) {
        helper.assertTrue(player.serverLevel() == target && source.getEntity(player.getUUID()) == null
                && target.getEntity(player.getUUID()) == player, "The real player must move between the two level entity managers");
        helper.assertTrue(player.getX() == arrival.getX() + 0.5 && player.getY() == arrival.getY()
                && player.getZ() == arrival.getZ() + 0.5, "The player must reach the exact protected arrival point");
        helper.assertTrue(connection.packets.stream().anyMatch(packet -> packet instanceof ClientboundRespawnPacket respawn
                && respawn.getDimension() == target.dimension()), "The client is told which dimension to load");
        helper.assertTrue(connection.packets.stream().anyMatch(packet -> packet instanceof ClientboundPlayerPositionPacket position
                && position.getX() == player.getX() && position.getY() == player.getY() && position.getZ() == player.getZ()),
                "The client position packet matches the server arrival");
        helper.assertTrue(connection.packets.stream().anyMatch(packet -> packet instanceof ClientboundSetTimePacket time
                && Math.abs(time.getDayTime()) == target.getDayTime()), "The client receives the target time immediately");
    }

    private static void installOfficeFixture(ServerLevel overworld, ServerPlayer owner, PortalLocation entrance) {
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

    private static final class RecordingConnection extends ServerGamePacketListenerImpl {
        private final List<Packet<?>> packets = new ArrayList<>();

        RecordingConnection(ServerPlayer player) {
            super(player.server, new Connection(PacketFlow.SERVERBOUND), player);
        }

        @Override
        public void send(Packet<?> packet, PacketSendListener listener) {
            packets.add(packet);
        }
    }
}
