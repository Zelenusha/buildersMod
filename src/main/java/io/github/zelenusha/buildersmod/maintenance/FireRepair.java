package io.github.zelenusha.buildersmod.maintenance;

import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Bounds;
import io.github.zelenusha.buildersmod.site.ConstructionSite;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

/** Tracks one fire and the pre-fire condition of the original starter room. */
public final class FireRepair {
    private static final Bounds STARTER_BOUNDS = new Bounds(new BlockPos(-4, ConstructionSite.START_FLOOR_Y, -4),
            new BlockPos(4, ConstructionSite.START_FLOOR_Y + 5, 4));
    private static final int STARTER_BLOCKS = 9 * 9 * 6;

    private final Set<BlockPos> flames = new HashSet<>();
    private final Map<BlockPos, BlockState> adjacentFuel = new LinkedHashMap<>();
    private final Map<BlockPos, Bounds> unfinishedRooms = new LinkedHashMap<>();
    private Map<BlockPos, BlockState> starterSnapshot = Map.of();
    private boolean observedIgnition;

    public static Bounds starterBounds() {
        return STARTER_BOUNDS;
    }

    public static Map<BlockPos, BlockState> captureStarter(ServerLevel site) {
        if (!IncidentWorld.loaded(site, STARTER_BOUNDS)) {
            return Map.of();
        }
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        for (BlockPos pos : STARTER_BOUNDS.positions()) {
            blocks.put(pos, site.getBlockState(pos));
        }
        return Map.copyOf(blocks);
    }

    /** Call before ignition to capture the intact starter room, then at the end of each site tick. */
    public boolean observe(ServerLevel site, IncidentState fire, List<RoomBlueprint> completed, RoomBlueprint unfinished) {
        if (fire.kind() != IncidentState.Kind.FIRE || fire.status() != IncidentState.Status.ACTIVE) {
            return false;
        }
        boolean changed = !observedIgnition && starterSnapshot.isEmpty() && refreshStarter(site);
        Set<BlockPos> touched = fire.affectedRooms();
        Set<BlockPos> seeds = observedIgnition ? Set.copyOf(flames) : Set.copyOf(fire.initialBlocks());
        Set<BlockPos> nextFlames = observePropagation(site, seeds);
        changed |= !nextFlames.equals(flames);
        if (!observedIgnition && !nextFlames.isEmpty()) {
            observedIgnition = true;
            changed = true;
        }
        flames.clear();
        flames.addAll(nextFlames);

        changed |= observeStarter(site, fire, touched, seeds);
        Map<BlockPos, RoomBlueprint> rooms = completedRooms(completed);
        for (var entry : rooms.entrySet()) {
            if (!touched.contains(entry.getKey()) && reachedConstruction(site, entry.getValue().bounds(), seeds)) {
                changed |= fire.addFireRoom(entry.getKey());
            }
        }
        if (unfinished != null && !rooms.containsKey(unfinished.bounds().min())
                && reachedConstruction(site, unfinished.bounds(), seeds)) {
            BlockPos key = unfinished.bounds().min();
            changed |= unfinishedRooms.putIfAbsent(key, unfinished.bounds()) == null;
            changed |= fire.addFireRoom(key);
        }
        Map<BlockPos, BlockState> nextFuel = captureAdjacentFuel(site);
        if (!adjacentFuel.equals(nextFuel)) {
            adjacentFuel.clear();
            adjacentFuel.putAll(nextFuel);
            changed = true;
        }
        return changed;
    }

    public OptionalInt submit(ServerLevel site, IncidentState fire, List<RoomBlueprint> completed, RoomBlueprint unfinished) {
        observe(site, fire, completed, unfinished);
        if (!observedIgnition) {
            return OptionalInt.empty();
        }
        if (flames.stream().anyMatch(pos -> !hasBlock(site, pos) || isFire(site, pos))) {
            return OptionalInt.empty();
        }
        Map<BlockPos, RoomBlueprint> rooms = completedRooms(completed);
        return fire.submitFire(key -> {
            Bounds bounds = key.equals(STARTER_BOUNDS.min()) ? STARTER_BOUNDS
                    : unfinishedRooms.containsKey(key) ? unfinishedRooms.get(key)
                    : rooms.containsKey(key) ? rooms.get(key).bounds() : null;
            return bounds != null && IncidentWorld.loaded(site, bounds) && !IncidentWorld.hasFire(site, bounds);
        }, key -> {
            if (key.equals(STARTER_BOUNDS.min())) {
                return starterSnapshot.size() == STARTER_BLOCKS && starterSnapshot.entrySet().stream()
                        .allMatch(entry -> site.getBlockState(entry.getKey()).equals(entry.getValue()));
            }
            if (unfinishedRooms.containsKey(key)) {
                return true;
            }
            RoomBlueprint room = rooms.get(key);
            return room != null && room.check(site).complete();
        });
    }

    private boolean observeStarter(ServerLevel site, IncidentState fire, Set<BlockPos> touched, Set<BlockPos> seeds) {
        BlockPos key = STARTER_BOUNDS.min();
        if (touched.contains(key)) {
            return false;
        }
        if (reachedStarter(site, seeds)) {
            return fire.addFireRoom(key);
        }
        return refreshStarter(site);
    }

    private boolean refreshStarter(ServerLevel site) {
        if (!IncidentWorld.loaded(site, STARTER_BOUNDS)) {
            return false;
        }
        Map<BlockPos, BlockState> current = captureStarter(site);
        if (current.equals(starterSnapshot)) {
            return false;
        }
        starterSnapshot = current;
        return true;
    }

    private boolean reachedStarter(ServerLevel site, Set<BlockPos> seeds) {
        if (flames.stream().anyMatch(STARTER_BOUNDS::contains)) {
            return true;
        }
        if (starterSnapshot.isEmpty()) {
            return false;
        }
        // Vanilla can consume an adjacent flammable block outright without replacing it with visible flame.
        for (BlockPos flame : seeds) {
            for (Direction side : Direction.values()) {
                BlockPos pos = flame.relative(side);
                BlockState before = starterSnapshot.get(pos);
                if (burnedAway(site, pos, before, side)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean reachedConstruction(ServerLevel site, Bounds bounds, Set<BlockPos> seeds) {
        if (flames.stream().anyMatch(bounds::contains)) {
            return true;
        }
        if (!observedIgnition) {
            return false;
        }
        for (BlockPos flame : seeds) {
            for (Direction side : Direction.values()) {
                BlockPos pos = flame.relative(side);
                if (bounds.contains(pos) && burnedAway(site, pos, adjacentFuel.get(pos), side)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Map<BlockPos, BlockState> captureAdjacentFuel(ServerLevel site) {
        Map<BlockPos, BlockState> fuel = new LinkedHashMap<>();
        for (BlockPos flame : flames) {
            for (Direction side : Direction.values()) {
                BlockPos pos = flame.relative(side);
                if (!hasBlock(site, flame) || !hasBlock(site, pos)) {
                    BlockState previous = adjacentFuel.get(pos);
                    if (previous != null) {
                        fuel.put(pos, previous);
                    }
                    continue;
                }
                BlockState state = site.getBlockState(pos);
                if (!state.isAir() && state.isFlammable(site, pos, side.getOpposite())) {
                    fuel.put(pos, state);
                }
            }
        }
        return fuel;
    }

    private static boolean burnedAway(ServerLevel site, BlockPos pos, BlockState previous, Direction side) {
        return previous != null && !previous.isAir() && hasBlock(site, pos) && site.getBlockState(pos).isAir()
                && previous.isFlammable(site, pos, side.getOpposite());
    }

    private static Set<BlockPos> observePropagation(ServerLevel site, Set<BlockPos> seeds) {
        Set<BlockPos> next = new HashSet<>();
        Set<BlockPos> visited = new HashSet<>(seeds);
        ArrayDeque<BlockPos> queue = new ArrayDeque<>(seeds);
        while (!queue.isEmpty()) {
            BlockPos source = queue.removeFirst();
            if (!hasBlock(site, source)) {
                if (seeds.contains(source)) {
                    next.add(source);
                }
                continue;
            }
            if (isFire(site, source)) {
                next.add(source);
            }
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    // FireBlock checks air candidates in a 3 x 6 x 3 region; direct burning is included.
                    for (int y = -1; y <= 4; y++) {
                        BlockPos pos = source.offset(x, y, z);
                        if (visited.add(pos) && hasBlock(site, pos) && isFire(site, pos)) {
                            queue.addLast(pos);
                        }
                    }
                }
            }
        }
        return next;
    }

    private static boolean hasBlock(ServerLevel site, BlockPos pos) {
        return site.isInWorldBounds(pos) && site.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
    }

    private static boolean isFire(ServerLevel site, BlockPos pos) {
        return site.getBlockState(pos).is(BlockTags.FIRE);
    }

    private static Map<BlockPos, RoomBlueprint> completedRooms(List<RoomBlueprint> completed) {
        Map<BlockPos, RoomBlueprint> rooms = new LinkedHashMap<>();
        for (RoomBlueprint room : completed) {
            rooms.put(room.bounds().min(), room);
        }
        return rooms;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLongArray("Flames", flames.stream().mapToLong(BlockPos::asLong).toArray());
        tag.putBoolean("ObservedIgnition", observedIgnition);
        ListTag unfinished = new ListTag();
        unfinishedRooms.forEach((min, bounds) -> {
            CompoundTag entry = new CompoundTag();
            entry.putLong("Min", min.asLong());
            entry.putLong("Max", bounds.max().asLong());
            unfinished.add(entry);
        });
        tag.put("UnfinishedRooms", unfinished);
        tag.put("StarterSnapshot", encodeStates(starterSnapshot));
        tag.put("AdjacentFuel", encodeStates(adjacentFuel));
        return tag;
    }

    private static ListTag encodeStates(Map<BlockPos, BlockState> blocks) {
        ListTag snapshot = new ListTag();
        blocks.forEach((pos, state) -> {
            CompoundTag entry = new CompoundTag();
            entry.putLong("Pos", pos.asLong());
            entry.put("State", NbtUtils.writeBlockState(state));
            snapshot.add(entry);
        });
        return snapshot;
    }

    public static FireRepair load(CompoundTag tag) {
        FireRepair repair = new FireRepair();
        for (long pos : tag.getLongArray("Flames")) {
            repair.flames.add(BlockPos.of(pos));
        }
        repair.observedIgnition = tag.getBoolean("ObservedIgnition");
        for (Tag value : tag.getList("UnfinishedRooms", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) value;
            BlockPos min = BlockPos.of(entry.getLong("Min"));
            Bounds bounds = new Bounds(min, BlockPos.of(entry.getLong("Max")));
            repair.unfinishedRooms.put(min, bounds);
        }
        Map<BlockPos, BlockState> snapshot = decodeStates(tag.getList("StarterSnapshot", Tag.TAG_COMPOUND));
        snapshot.keySet().removeIf(pos -> !STARTER_BOUNDS.contains(pos));
        repair.starterSnapshot = Map.copyOf(snapshot);
        repair.adjacentFuel.putAll(decodeStates(tag.getList("AdjacentFuel", Tag.TAG_COMPOUND)));
        return repair;
    }

    @SuppressWarnings("deprecation") // Deserialization only needs the built-in registry's read-only block lookup.
    private static Map<BlockPos, BlockState> decodeStates(ListTag states) {
        Map<BlockPos, BlockState> snapshot = new LinkedHashMap<>();
        for (Tag value : states) {
            CompoundTag entry = (CompoundTag) value;
            BlockPos position = BlockPos.of(entry.getLong("Pos"));
            snapshot.put(position, NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), entry.getCompound("State")));
        }
        return snapshot;
    }
}
