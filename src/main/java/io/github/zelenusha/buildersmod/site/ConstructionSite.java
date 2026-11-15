package io.github.zelenusha.buildersmod.site;

import java.util.List;
import java.util.Set;
import io.github.zelenusha.buildersmod.BuildersMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelWriter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod.EventBusSubscriber(modid = BuildersMod.MOD_ID)
public final class ConstructionSite {
    public static final ResourceKey<Level> SITE_LEVEL =
            ResourceKey.create(Registries.DIMENSION, new ResourceLocation(BuildersMod.MOD_ID, "construction_site"));
    public static final int START_FLOOR_Y = 64;
    public static final int FLOOR_STEP = 25;
    public static final int FLOOR_COUNT = 8;
    public static final BlockPos SITE_ARRIVAL = new BlockPos(0, START_FLOOR_Y + 1, 0);
    public static final List<SiteDoor> STARTER_DOORS = List.of(
            new SiteDoor(new BlockPos(0, START_FLOOR_Y + 1, -4), Direction.NORTH),
            new SiteDoor(new BlockPos(4, START_FLOOR_Y + 1, 0), Direction.EAST),
            new SiteDoor(new BlockPos(0, START_FLOOR_Y + 1, 4), Direction.SOUTH));

    static final PortalLocation RETURN_PORTAL =
            new PortalLocation(new BlockPos(1, START_FLOOR_Y, -3), Direction.Axis.X, SITE_ARRIVAL);
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, BuildersMod.MOD_ID);
    private static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(ForgeRegistries.FEATURES, BuildersMod.MOD_ID);
    private static final RegistryObject<Block> PORTAL = BLOCKS.register("site_portal", () -> new SitePortalBlock(
            BlockBehaviour.Properties.of().noCollission().noOcclusion().noLootTable()
                    .strength(-1.0F, 3_600_000.0F).lightLevel(state -> 11).pushReaction(PushReaction.BLOCK)));
    static {
        FEATURES.register("site_frame", SiteFrameFeature::new);
    }

    private ConstructionSite() {}

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        FEATURES.register(modBus);
    }

    public static void installOfficePortal(ServerPlayer owner, ServerLevel level, BlockPos lowerLeft,
                                           Direction.Axis axis, BlockPos arrival) {
        if (level.dimension() != Level.OVERWORLD || axis == Direction.Axis.Y) {
            throw new IllegalArgumentException("The office portal needs a horizontal frame in the Overworld");
        }
        PortalLocation location = new PortalLocation(lowerLeft.immutable(), axis, arrival.immutable());
        placePortal(level, location);
        SiteLinks.get(level.getServer()).put(owner.getUUID(), location);
    }

    static void placePortal(LevelWriter level, PortalLocation location) {
        for (int horizontal = 0; horizontal < 4; horizontal++) {
            for (int vertical = 0; vertical < 5; vertical++) {
                BlockState state = horizontal == 0 || horizontal == 3 || vertical == 0 || vertical == 4
                        ? Blocks.DEEPSLATE_BRICKS.defaultBlockState()
                        : PORTAL.get().defaultBlockState().setValue(SitePortalBlock.AXIS, location.axis());
                level.setBlock(location.at(horizontal, vertical), state, Block.UPDATE_CLIENTS);
            }
        }
    }

    public static boolean isProtectedArrival(ServerLevel level, BlockPos pos) {
        if (level.dimension() == SITE_LEVEL) {
            return RETURN_PORTAL.containsArrival(pos);
        }
        return level.dimension() == Level.OVERWORLD && SiteLinks.get(level.getServer()).protectsArrival(pos);
    }

    public static boolean isReservedSiteBlock(BlockPos pos) {
        return RETURN_PORTAL.containsArrival(pos) || RETURN_PORTAL.protectsFromBreak(pos);
    }

    public static boolean isOriginalColumnPosition(BlockPos pos) {
        return SiteFrameFeature.isColumnPosition(pos.getX(), pos.getY(), pos.getZ());
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (event instanceof BlockEvent.EntityMultiPlaceEvent multi) {
            if (multi.getReplacedBlockSnapshots().stream().anyMatch(snapshot -> isProtectedArrival(level, snapshot.getPos()))) {
                event.setCanceled(true);
            }
        } else if (isProtectedArrival(level, event.getPos())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = event.getPos();
        boolean reserved = level.dimension() == SITE_LEVEL && RETURN_PORTAL.protectsFromBreak(pos);
        if (level.dimension() == Level.OVERWORLD) {
            reserved = SiteLinks.get(level.getServer()).protectsFromBreak(pos);
        }
        if (reserved) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)
                || !player.isAlive() || player.isSpectator() || player.isPassenger()) {
            return;
        }
        ServerLevel source = player.serverLevel();
        if (source.dimension() == SITE_LEVEL && player.tickCount % 20 == 0) {
            SiteLighting.updateNearby(source, player.chunkPosition());
        }
        BlockPos contact = player.blockPosition();
        if (!source.getBlockState(contact).is(PORTAL.get())) {
            return;
        }

        PortalLocation office = SiteLinks.get(source.getServer()).forPlayer(player.getUUID());
        if (office == null) {
            return;
        }
        if (source.dimension() == Level.OVERWORLD && office.containsOpening(contact)) {
            ServerLevel site = source.getServer().getLevel(SITE_LEVEL);
            if (site != null) {
                travel(player, site, SITE_ARRIVAL, 180.0F);
            }
        } else if (source.dimension() == SITE_LEVEL && RETURN_PORTAL.containsOpening(contact)) {
            travel(player, source.getServer().overworld(), office.arrival(), player.getYRot());
        }
    }

    private static void travel(ServerPlayer player, ServerLevel destination, BlockPos arrival, float facing) {
        destination.getChunkAt(arrival);
        BlockPos support = arrival.below();
        if (!destination.isEmptyBlock(arrival) || !destination.isEmptyBlock(arrival.above())
                || !destination.getBlockState(support).isFaceSturdy(destination, support, Direction.UP)) {
            if (player.tickCount % 40 == 0) {
                player.displayClientMessage(Component.translatableWithFallback(
                        "message.buildersmod.site_portal.blocked", "Место прибытия у портала заблокировано."), true);
            }
            return;
        }
        if (player.teleportTo(destination, arrival.getX() + 0.5, arrival.getY(), arrival.getZ() + 0.5,
                Set.of(), facing, 0.0F)) {
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0.0F;
            // Secondary dimensions read the Overworld clock. Send it before the next periodic time update.
            player.connection.send(new ClientboundSetTimePacket(destination.getGameTime(), destination.getDayTime(),
                    destination.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)));
        }
    }

    public record SiteDoor(BlockPos lower, Direction outward) {}
}
