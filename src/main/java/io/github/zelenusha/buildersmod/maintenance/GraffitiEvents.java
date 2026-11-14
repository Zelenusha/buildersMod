package io.github.zelenusha.buildersmod.maintenance;

import io.github.zelenusha.buildersmod.BuildersMod;
import io.github.zelenusha.buildersmod.client.GraffitiClient;
import io.github.zelenusha.buildersmod.maintenance.IncidentWorld.GraffitiTarget;
import io.github.zelenusha.buildersmod.site.ConstructionSite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;

@Mod.EventBusSubscriber(modid = BuildersMod.MOD_ID)
public final class GraffitiEvents {
    public static final int PATCH_COUNT = GraffitiPattern.PATCH_COUNT;
    private static final String PROTOCOL = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(BuildersMod.MOD_ID, "graffiti"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
    private static final Map<UUID, Attempt> ATTEMPTS = new HashMap<>();
    private static BiFunction<ServerPlayer, UUID, IncidentState> activeCleanup = (player, id) -> null;
    private static Consumer<ServerPlayer> onProgress = player -> { };

    private record Marker(UUID owner, UUID incident, BlockPos pos, Direction face) {}

    private record Stroke(UUID token, boolean begin, int x, int y) {}

    private record Close(UUID token) {}

    private record Attempt(UUID token, UUID incident, Cursor cursor) {
        Attempt(UUID incident) {
            this(UUID.randomUUID(), incident, new Cursor());
        }
    }

    private static final class Cursor {
        private int x = -1;
        private int y = -1;
        private long tick = Long.MIN_VALUE;
        private int strokes;

        boolean accepts(long currentTick) {
            if (tick != currentTick) {
                tick = currentTick;
                strokes = 0;
            }
            return ++strokes <= 16;
        }
    }

    private record Open(UUID token, BitSet erased) {}

    private record Progress(UUID token, UUID incident, BitSet erased, boolean finished) {}

    private record MarkerUpdate(UUID incident, BlockPos pos, Direction face, BitSet erased) {}

    private GraffitiEvents() { }

    public static void register(BiFunction<ServerPlayer, UUID, IncidentState> resolver, Consumer<ServerPlayer> dirty) {
        activeCleanup = resolver;
        onProgress = dirty;
        CHANNEL.messageBuilder(MarkerUpdate.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((value, buffer) -> {
                    buffer.writeUUID(value.incident());
                    buffer.writeNullable(value.pos(), FriendlyByteBuf::writeBlockPos);
                    if (value.pos() != null) {
                        buffer.writeEnum(value.face());
                        buffer.writeLongArray(value.erased().toLongArray());
                    }
                })
                .decoder(buffer -> {
                    UUID incident = buffer.readUUID();
                    BlockPos pos = buffer.readNullable(FriendlyByteBuf::readBlockPos);
                    return pos == null ? new MarkerUpdate(incident, null, null, null)
                            : new MarkerUpdate(incident, pos, buffer.readEnum(Direction.class), readPatches(buffer));
                })
                .consumerMainThread((value, context) -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> GraffitiClient.updateMarker(value.incident(), value.pos(), value.face(), value.erased()))).add();
        CHANNEL.messageBuilder(Open.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((value, buffer) -> {
                    buffer.writeUUID(value.token());
                    buffer.writeLongArray(value.erased().toLongArray());
                })
                .decoder(buffer -> new Open(buffer.readUUID(), readPatches(buffer)))
                .consumerMainThread((value, context) -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> GraffitiClient.open(value.token(), value.erased()))).add();
        CHANNEL.messageBuilder(Progress.class, 2, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((value, buffer) -> {
                    buffer.writeUUID(value.token());
                    buffer.writeUUID(value.incident());
                    buffer.writeLongArray(value.erased().toLongArray());
                    buffer.writeBoolean(value.finished());
                })
                .decoder(buffer -> new Progress(buffer.readUUID(), buffer.readUUID(), readPatches(buffer), buffer.readBoolean()))
                .consumerMainThread((value, context) -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> GraffitiClient.progress(value.token(), value.incident(), value.erased(), value.finished()))).add();
        CHANNEL.messageBuilder(Stroke.class, 3, NetworkDirection.PLAY_TO_SERVER)
                .encoder((value, buffer) -> buffer.writeUUID(value.token()).writeBoolean(value.begin())
                        .writeByte(value.x()).writeByte(value.y()))
                .decoder(buffer -> new Stroke(buffer.readUUID(), buffer.readBoolean(), buffer.readUnsignedByte(),
                        buffer.readUnsignedByte()))
                .consumerMainThread((value, context) -> {
                    ServerPlayer player = context.get().getSender();
                    if (player != null) {
                        stroke(player, value);
                    }
                }).add();
        CHANNEL.messageBuilder(Close.class, 4, NetworkDirection.PLAY_TO_SERVER)
                .encoder((value, buffer) -> buffer.writeUUID(value.token()))
                .decoder(buffer -> new Close(buffer.readUUID()))
                .consumerMainThread((value, context) -> {
                    ServerPlayer player = context.get().getSender();
                    if (player != null) {
                        ATTEMPTS.computeIfPresent(player.getUUID(), (id, attempt) ->
                                attempt.token().equals(value.token()) ? null : attempt);
                    }
                }).add();
    }

    private static BitSet readPatches(FriendlyByteBuf buffer) {
        return BitSet.valueOf(buffer.readLongArray(null, (PATCH_COUNT + 63) / 64));
    }

    public static void sendStroke(UUID token, boolean begin, int x, int y) {
        CHANNEL.sendToServer(new Stroke(token, begin, x, y));
    }

    public static void endAttempt(UUID token) {
        CHANNEL.sendToServer(new Close(token));
    }

    public static boolean place(ServerPlayer owner, ServerLevel site, UUID incidentId, GraffitiTarget target) {
        if (site.dimension() != ConstructionSite.SITE_LEVEL || !target.face().getAxis().isHorizontal()
                || !site.isInWorldBounds(target.pos()) || ConstructionSite.isReservedSiteBlock(target.pos())) {
            return false;
        }
        MarkerData data = MarkerData.get(site);
        Marker marker = new Marker(owner.getUUID(), incidentId, target.pos(), target.face());
        Marker existing = data.markers.get(incidentId);
        if (existing != null) {
            return existing.equals(marker);
        }
        if (data.markers.values().stream().anyMatch(saved -> saved.pos().equals(marker.pos()) && saved.face() == marker.face())) {
            return false;
        }
        data.markers.put(incidentId, marker);
        data.setDirty();
        send(owner, new MarkerUpdate(incidentId, marker.pos(), marker.face(), new BitSet()));
        return true;
    }

    public static void remove(ServerLevel site, UUID incidentId) {
        if (site.dimension() != ConstructionSite.SITE_LEVEL) {
            return;
        }
        MarkerData data = MarkerData.get(site);
        Marker marker = data.markers.remove(incidentId);
        if (marker != null) {
            data.setDirty();
            ATTEMPTS.computeIfPresent(marker.owner(), (owner, attempt) ->
                    attempt.incident().equals(incidentId) ? null : attempt);
            ServerPlayer player = site.getServer().getPlayerList().getPlayer(marker.owner());
            if (player != null) {
                send(player, new MarkerUpdate(incidentId, null, null, null));
            }
        }
    }

    @SubscribeEvent
    public static void useRag(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.serverLevel().dimension() != ConstructionSite.SITE_LEVEL
                || !event.getItemStack().is(WorkItems.WET_RAG.get())) {
            return;
        }
        ServerLevel level = player.serverLevel();
        Marker marker = MarkerData.get(level).markers.values().stream()
                .filter(value -> value.owner().equals(player.getUUID()) && value.pos().equals(event.getPos())
                        && value.face() == event.getHitVec().getDirection()).findFirst().orElse(null);
        if (marker == null || !canReach(player, marker)) {
            return;
        }
        IncidentState state = active(player, marker.incident());
        if (state == null) {
            return;
        }
        if (!ATTEMPTS.containsKey(player.getUUID()) && WorkItems.useWetRag(player, event.getHand())) {
            Attempt attempt = new Attempt(marker.incident());
            ATTEMPTS.put(player.getUUID(), attempt);
            send(player, new Open(attempt.token(), state.erasedPatches()));
        }
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    private static void stroke(ServerPlayer player, Stroke value) {
        Attempt attempt = ATTEMPTS.get(player.getUUID());
        if (attempt == null || !attempt.token().equals(value.token()) || !GraffitiPattern.inside(value.x(), value.y())) {
            return;
        }
        IncidentState state = active(player, attempt.incident());
        Marker marker = marker(player, attempt.incident());
        if (state == null || marker == null || !canReach(player, marker)) {
            ATTEMPTS.remove(player.getUUID());
            send(player, new Progress(value.token(), attempt.incident(), new BitSet(), true));
            return;
        }
        Cursor cursor = attempt.cursor();
        if (!cursor.accepts(player.serverLevel().getGameTime())) {
            return;
        }
        int startX = value.begin() || cursor.x < 0 ? value.x() : cursor.x;
        int startY = value.begin() || cursor.y < 0 ? value.y() : cursor.y;
        cursor.x = value.x();
        cursor.y = value.y();

        BitSet covered = GraffitiPattern.scrub(startX, startY, cursor.x, cursor.y);
        boolean changed = false;
        for (int patch = covered.nextSetBit(0); patch >= 0; patch = covered.nextSetBit(patch + 1)) {
            changed |= state.erasePatch(patch);
        }
        if (changed) {
            onProgress.accept(player);
            BitSet erased = state.erasedPatches();
            boolean finished = erased.cardinality() == PATCH_COUNT;
            send(player, new Progress(value.token(), attempt.incident(), erased, finished));
            if (finished) {
                remove(player.serverLevel(), state.id());
            }
        }
    }

    private static IncidentState active(ServerPlayer player, UUID incident) {
        IncidentState state = activeCleanup.apply(player, incident);
        return state != null && state.id().equals(incident) && state.kind() == IncidentState.Kind.CLEANUP
                && state.status() == IncidentState.Status.ACTIVE && state.graffitiPatches() == PATCH_COUNT
                && state.erasedPatches().cardinality() < PATCH_COUNT ? state : null;
    }

    private static Marker marker(ServerPlayer player, UUID incident) {
        if (player.serverLevel().dimension() != ConstructionSite.SITE_LEVEL) {
            return null;
        }
        Marker marker = MarkerData.get(player.serverLevel()).markers.get(incident);
        return marker != null && marker.owner().equals(player.getUUID()) ? marker : null;
    }

    private static boolean canReach(ServerPlayer player, Marker marker) {
        return player.isAlive() && !player.isSpectator() && !player.isSleeping()
                && player.getEyePosition().distanceToSqr(Vec3.atCenterOf(marker.pos())) <= 36.0;
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ServerLevel site = player.serverLevel().getServer().getLevel(ConstructionSite.SITE_LEVEL);
            if (site == null) {
                return;
            }
            for (Marker marker : MarkerData.get(site).markers.values()) {
                IncidentState state = marker.owner().equals(player.getUUID()) ? active(player, marker.incident()) : null;
                if (state != null) {
                    send(player, new MarkerUpdate(marker.incident(), marker.pos(), marker.face(), state.erasedPatches()));
                }
            }
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        ATTEMPTS.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) {
            return;
        }
        Attempt attempt = ATTEMPTS.get(player.getUUID());
        if (attempt != null) {
            Marker marker = marker(player, attempt.incident());
            if (marker == null || !canReach(player, marker)) {
                ATTEMPTS.remove(player.getUUID());
                send(player, new Progress(attempt.token(), attempt.incident(), new BitSet(), true));
            }
        }
    }

    private static void send(ServerPlayer player, Object message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    private static final class MarkerData extends SavedData {
        private final Map<UUID, Marker> markers = new HashMap<>();

        static MarkerData get(ServerLevel site) {
            return site.getDataStorage().computeIfAbsent(MarkerData::load, MarkerData::new, "buildersmod_graffiti");
        }

        private static MarkerData load(CompoundTag root) {
            MarkerData data = new MarkerData();
            for (Tag raw : root.getList("Markers", Tag.TAG_COMPOUND)) {
                CompoundTag entry = (CompoundTag) raw;
                Direction face = Direction.byName(entry.getString("Face"));
                if (entry.hasUUID("Owner") && entry.hasUUID("Incident") && entry.contains("Position", Tag.TAG_LONG)
                        && face != null && face.getAxis().isHorizontal()) {
                    UUID incident = entry.getUUID("Incident");
                    data.markers.put(incident, new Marker(entry.getUUID("Owner"), incident,
                            BlockPos.of(entry.getLong("Position")), face));
                }
            }
            return data;
        }

        @Override
        public CompoundTag save(CompoundTag root) {
            ListTag entries = new ListTag();
            for (Marker marker : markers.values()) {
                CompoundTag entry = new CompoundTag();
                entry.putUUID("Owner", marker.owner());
                entry.putUUID("Incident", marker.incident());
                entry.putLong("Position", marker.pos().asLong());
                entry.putString("Face", marker.face().getName());
                entries.add(entry);
            }
            root.put("Markers", entries);
            return root;
        }
    }
}
