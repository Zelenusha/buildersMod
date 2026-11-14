package io.github.zelenusha.buildersmod.maintenance;

import io.github.zelenusha.buildersmod.contract.ContractBalance;
import io.github.zelenusha.buildersmod.contract.ContractBalance.Difficulty;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

public final class IncidentState {
    public enum Kind {
        CLEANUP("Очистка комнаты"), EVICTION("Выселение"), FIRE("Тушение и ремонт"), FLOOD("Осушение");

        public final String title;

        Kind(String title) {
            this.title = title;
        }

        public boolean pausesConstruction() {
            return this != CLEANUP;
        }
    }

    public enum Status { ACTIVE, COMPLETED, FAILED }

    private final UUID id;
    private final Kind kind;
    private final Difficulty difficulty;
    private final boolean automatic;
    private final BlockPos originRoom;
    private final List<BlockPos> initialBlocks;
    private final List<UUID> evictionTargets;
    private final int graffitiPatches;
    private final BitSet erased = new BitSet();
    private final Set<UUID> deadTargets = new HashSet<>();
    private final Set<BlockPos> affectedRooms = new HashSet<>();
    private final Set<BlockPos> floodFill = new HashSet<>();
    private Status status = Status.ACTIVE;

    private IncidentState(UUID id, Kind kind, Difficulty difficulty, boolean automatic, BlockPos originRoom,
                          List<BlockPos> initialBlocks, List<UUID> evictionTargets, int graffitiPatches) {
        this.id = id;
        this.kind = kind;
        this.difficulty = difficulty;
        this.automatic = automatic;
        this.originRoom = originRoom.immutable();
        this.initialBlocks = initialBlocks.stream().map(BlockPos::immutable).toList();
        this.evictionTargets = List.copyOf(evictionTargets);
        this.graffitiPatches = graffitiPatches;
        affectedRooms.add(this.originRoom);
    }

    public static IncidentState cleanup(UUID id, Difficulty difficulty, boolean automatic, BlockPos room,
                                        Collection<BlockPos> cobwebPositions, int graffitiPatches, long seed) {
        if (graffitiPatches <= 0) {
            throw new IllegalArgumentException("A graffiti drawing must have at least one patch");
        }
        return new IncidentState(id, Kind.CLEANUP, difficulty, automatic, room,
                choose(cobwebPositions, 2 * tier(difficulty), seed), List.of(), graffitiPatches);
    }

    public static IncidentState eviction(UUID id, Difficulty difficulty, boolean automatic, BlockPos room,
                                         Collection<UUID> eligibleTargets, long seed) {
        return new IncidentState(id, Kind.EVICTION, difficulty, automatic, room,
                List.of(), choose(eligibleTargets, tier(difficulty), seed), 0);
    }

    public static IncidentState fire(UUID id, Difficulty difficulty, boolean automatic, BlockPos room, BlockPos ignition) {
        return new IncidentState(id, Kind.FIRE, difficulty, automatic, room, List.of(ignition), List.of(), 0);
    }

    public static IncidentState flood(UUID id, Difficulty difficulty, boolean automatic, BlockPos room,
                                       Collection<BlockPos> waterPositions, long seed) {
        return new IncidentState(id, Kind.FLOOD, difficulty, automatic, room,
                choose(waterPositions, tier(difficulty), seed), List.of(), 0);
    }

    private static int tier(Difficulty difficulty) {
        return switch (difficulty) {
            case ONE, TWO -> 1;
            case THREE, FOUR -> 2;
            case FIVE -> 3;
        };
    }

    private static <T extends Comparable<? super T>> List<T> choose(Collection<T> eligible, int limit, long seed) {
        List<T> sorted = new ArrayList<>(new HashSet<>(eligible));
        Collections.sort(sorted);
        Collections.shuffle(sorted, new Random(seed));
        return sorted.subList(0, Math.min(sorted.size(), limit));
    }

    public UUID id() { return id; }
    public Kind kind() { return kind; }
    public Difficulty difficulty() { return difficulty; }
    public boolean automatic() { return automatic; }
    public BlockPos originRoom() { return originRoom; }
    public Status status() { return status; }
    public List<BlockPos> initialBlocks() { return initialBlocks; }
    public int graffitiPatches() { return graffitiPatches; }
    public BitSet erasedPatches() { return (BitSet) erased.clone(); }
    public Set<BlockPos> affectedRooms() { return Set.copyOf(affectedRooms); }
    public Set<BlockPos> floodFill() { return Set.copyOf(floodFill); }

    public List<UUID> remainingEvictionTargets() {
        return evictionTargets.stream().filter(target -> !deadTargets.contains(target)).toList();
    }

    public boolean erasePatch(int patch) {
        if (!active(Kind.CLEANUP) || patch < 0 || patch >= graffitiPatches || erased.get(patch)) {
            return false;
        }
        erased.set(patch);
        return true;
    }

    public boolean recordTargetDeath(UUID target) {
        return active(Kind.EVICTION) && evictionTargets.contains(target) && deadTargets.add(target);
    }

    public boolean recordPlayerDeath() {
        if (!active(Kind.EVICTION)) {
            return false;
        }
        status = Status.FAILED;
        return true;
    }

    public boolean addFireRoom(BlockPos room) {
        return active(Kind.FIRE) && affectedRooms.add(room.immutable());
    }

    public boolean recordFloodFill(BlockPos pos) {
        return active(Kind.FLOOD) && floodFill.add(pos.immutable());
    }

    private boolean active(Kind expected) {
        return kind == expected && status == Status.ACTIVE;
    }

    public OptionalInt submitCleanup(Predicate<BlockPos> cobwebRemoved) {
        return complete(active(Kind.CLEANUP) && erased.cardinality() == graffitiPatches
                && initialBlocks.stream().allMatch(cobwebRemoved));
    }

    public OptionalInt submitEviction() {
        return complete(active(Kind.EVICTION) && !evictionTargets.isEmpty() && deadTargets.containsAll(evictionTargets));
    }

    public OptionalInt submitFire(Predicate<BlockPos> roomHasNoFire, Predicate<BlockPos> roomRepaired) {
        return complete(active(Kind.FIRE) && affectedRooms.stream().allMatch(roomHasNoFire)
                && affectedRooms.stream().allMatch(roomRepaired));
    }

    public OptionalInt submitFlood(Predicate<BlockPos> roomHasNoWater, Predicate<BlockPos> fillRemoved) {
        return complete(active(Kind.FLOOD) && !initialBlocks.isEmpty() && roomHasNoWater.test(originRoom)
                && floodFill.stream().allMatch(fillRemoved));
    }

    private OptionalInt complete(boolean satisfied) {
        if (!satisfied) {
            return OptionalInt.empty();
        }
        status = Status.COMPLETED;
        return OptionalInt.of(difficulty.emeralds());
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putString("Kind", kind.name());
        tag.putString("Difficulty", difficulty.name());
        tag.putString("Status", status.name());
        tag.putBoolean("Automatic", automatic);
        tag.putLong("Origin", originRoom.asLong());
        tag.putLongArray("Blocks", packPositions(initialBlocks));
        tag.putLongArray("Rooms", packPositions(affectedRooms));
        tag.putLongArray("FloodFill", packPositions(floodFill));
        tag.putInt("GraffitiPatches", graffitiPatches);
        tag.putLongArray("Erased", erased.toLongArray());
        tag.put("Targets", saveIds(evictionTargets));
        tag.put("DeadTargets", saveIds(deadTargets));
        return tag;
    }

    public static IncidentState load(CompoundTag tag) {
        IncidentState state = new IncidentState(tag.getUUID("Id"), Kind.valueOf(tag.getString("Kind")),
                Difficulty.valueOf(tag.getString("Difficulty")), tag.getBoolean("Automatic"),
                BlockPos.of(tag.getLong("Origin")), unpackPositions(tag.getLongArray("Blocks")),
                loadIds(tag.getList("Targets", Tag.TAG_INT_ARRAY)), tag.getInt("GraffitiPatches"));
        state.status = Status.valueOf(tag.getString("Status"));
        state.affectedRooms.addAll(unpackPositions(tag.getLongArray("Rooms")));
        state.floodFill.addAll(unpackPositions(tag.getLongArray("FloodFill")));
        state.deadTargets.addAll(loadIds(tag.getList("DeadTargets", Tag.TAG_INT_ARRAY)));
        state.erased.or(BitSet.valueOf(tag.getLongArray("Erased")));
        return state;
    }

    private static long[] packPositions(Collection<BlockPos> positions) {
        return positions.stream().mapToLong(BlockPos::asLong).toArray();
    }

    private static List<BlockPos> unpackPositions(long[] positions) {
        List<BlockPos> result = new ArrayList<>(positions.length);
        for (long pos : positions) {
            result.add(BlockPos.of(pos));
        }
        return result;
    }

    private static ListTag saveIds(Collection<UUID> ids) {
        ListTag tags = new ListTag();
        ids.forEach(id -> tags.add(NbtUtils.createUUID(id)));
        return tags;
    }

    private static List<UUID> loadIds(ListTag tags) {
        return tags.stream().map(NbtUtils::loadUUID).toList();
    }

    public static final class Schedule {
        private static final long WEEK = 7 * ContractBalance.TICKS_PER_DAY;
        private long nextCheck;
        private IncidentState pending;

        public Schedule(long acceptedAt) {
            nextCheck = acceptedAt + WEEK;
        }

        public long nextCheck() {
            return nextCheck;
        }

        public boolean roll(long worldTime, boolean blocked, RandomSource random) {
            if (worldTime < nextCheck) {
                return false;
            }
            nextCheck += WEEK;
            return pending == null && !blocked && random.nextInt(100) < 35;
        }

        public boolean queue(IncidentState incident) {
            if (pending != null || !incident.automatic || incident.status != Status.ACTIVE) {
                return false;
            }
            pending = incident;
            return true;
        }

        public Optional<IncidentState> takePending(Predicate<IncidentState> siteLoaded, boolean blocked) {
            if (pending == null || !siteLoaded.test(pending)) {
                return Optional.empty();
            }
            IncidentState incident = pending;
            pending = null;
            return blocked ? Optional.empty() : Optional.of(incident);
        }

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putLong("NextCheck", nextCheck);
            if (pending != null) {
                tag.put("Pending", pending.save());
            }
            return tag;
        }

        public static Schedule load(CompoundTag tag) {
            Schedule schedule = new Schedule(0);
            schedule.nextCheck = tag.getLong("NextCheck");
            if (tag.contains("Pending", Tag.TAG_COMPOUND)) {
                schedule.pending = IncidentState.load(tag.getCompound("Pending"));
            }
            return schedule;
        }
    }
}
