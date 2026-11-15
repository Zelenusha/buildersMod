package io.github.zelenusha.buildersmod.contract;

import io.github.zelenusha.buildersmod.intro.IntroEvents;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint;
import io.github.zelenusha.buildersmod.maintenance.FireRepair;
import io.github.zelenusha.buildersmod.maintenance.GraffitiEvents;
import io.github.zelenusha.buildersmod.maintenance.IncidentEligibility;
import io.github.zelenusha.buildersmod.maintenance.IncidentState;
import io.github.zelenusha.buildersmod.maintenance.IncidentWorld;
import io.github.zelenusha.buildersmod.site.ConstructionSite;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;

public final class IncidentWork {
    private IncidentWork() { }

    public static void startClock(ServerPlayer player) {
        ContractData data = ContractData.get(player.server);
        var account = data.account(player.getUUID());
        if (account.incidentSchedule == null) {
            account.incidentSchedule = new IncidentState.Schedule(data.time(player.server));
            data.setDirty();
        }
    }

    public static void register() {
        GraffitiEvents.register(IncidentWork::cleanup, player -> ContractData.get(player.server).setDirty());
    }

    static IncidentState cleanup(ServerPlayer player, UUID id) {
        return ContractData.get(player.server).account(player.getUUID()).incidents.stream()
                .filter(incident -> incident.id().equals(id) && incident.kind() == IncidentState.Kind.CLEANUP)
                .findFirst().orElse(null);
    }

    static boolean blocksEvents(ContractData.Account account) {
        return account.incidents.stream().anyMatch(incident -> incident.automatic() || incident.kind().pausesConstruction());
    }

    static void tick(ServerPlayer player, ContractData data, ContractData.Account account, long now) {
        if (!IntroEvents.hasAcceptedWork(player)) {
            return;
        }
        startClock(player);
        ServerLevel site = player.server.getLevel(ConstructionSite.SITE_LEVEL);
        if (site == null) {
            return;
        }
        if (account.fireRepair != null) {
            account.incidents.stream().filter(work -> work.kind() == IncidentState.Kind.FIRE).findFirst().ifPresent(work -> {
                if (account.fireRepair.observe(site, work, completedRooms(account), unfinishedRoom(account))) {
                    data.setDirty();
                }
            });
        }
        var schedule = account.incidentSchedule;
        boolean blocked = blocksEvents(account);
        long before = schedule.nextCheck();
        if (schedule.roll(now, blocked || account.pendingIncidentSeed != null, player.getRandom())
                && account.rooms.stream().anyMatch(IncidentWork::isOrdinaryRoom)) {
            account.pendingIncidentSeed = player.getRandom().nextLong();
        }
        if (before != schedule.nextCheck()) {
            data.setDirty();
        }
        Long seed = account.pendingIncidentSeed;
        if (seed != null && blocked) {
            account.pendingIncidentSeed = null;
            data.setDirty();
        } else if (seed != null && player.serverLevel() == site && now % 20 == 0) {
            WeeklyCandidate result = chooseWeekly(site, account, RandomSource.create(seed));
            Offer pending = result.offer();
            if (pending != null && activate(player, data, account, pending, true)) {
                account.pendingIncidentSeed = null;
                account.lastCall = call(pending.kind());
                ContractNetwork.call(player, account.lastCall);
                data.setDirty();
            } else if (!result.waitingForChunks()) {
                account.pendingIncidentSeed = null;
                data.setDirty();
            }
        }
    }

    record WeeklyCandidate(Offer offer, boolean waitingForChunks) { }

    static WeeklyCandidate chooseWeekly(ServerLevel site, ContractData.Account account, RandomSource random) {
        var roomsByKind = new EnumMap<IncidentState.Kind, List<BlockPos>>(IncidentState.Kind.class);
        boolean waiting = false;
        for (var order : account.rooms) {
            if (!isOrdinaryRoom(order) || hasWork(account, order.blueprint().bounds().min())) {
                continue;
            }
            RoomBlueprint room = order.blueprint();
            BlockPos position = room.bounds().min();
            if (!IncidentWorld.loaded(site, room.bounds())) {
                waiting = true;
                continue;
            }
            for (var kind : suitableKinds(site, account, room)) {
                roomsByKind.computeIfAbsent(kind, ignored -> new ArrayList<>()).add(position);
            }
        }
        if (roomsByKind.isEmpty()) {
            return new WeeklyCandidate(null, waiting);
        }
        var kinds = List.copyOf(roomsByKind.keySet());
        var kind = kinds.get(random.nextInt(kinds.size()));
        var rooms = roomsByKind.get(kind);
        return new WeeklyCandidate(newOffer(account, random, -1, rooms.get(random.nextInt(rooms.size())), kind), waiting);
    }

    static Offer chooseManual(ServerLevel site, ContractData.Account account, RandomSource random, int slot) {
        var rooms = new ArrayList<>(account.rooms.stream().filter(IncidentWork::isOrdinaryRoom).toList());
        Collections.shuffle(rooms, new Random(random.nextLong()));
        for (var order : rooms) {
            RoomBlueprint room = order.blueprint();
            BlockPos position = room.bounds().min();
            if (hasWork(account, position) || alreadyOffered(account, position)) {
                continue;
            }
            var kinds = ContractEvents.withLoadedArea(site, room.bounds(), () -> List.copyOf(suitableKinds(site, account, room)));
            if (!kinds.isEmpty()) {
                return newOffer(account, random, slot, position, kinds.get(random.nextInt(kinds.size())));
            }
        }
        return null;
    }

    private static Set<IncidentState.Kind> suitableKinds(ServerLevel site, ContractData.Account account, RoomBlueprint room) {
        boolean surviving = account.failedEvictions.containsKey(room.bounds().min());
        var kinds = IncidentEligibility.kinds(site, room, surviving);
        return surviving && kinds.contains(IncidentState.Kind.EVICTION) ? Set.of(IncidentState.Kind.EVICTION) : kinds;
    }

    private static Offer newOffer(ContractData.Account account, RandomSource random, int slot, BlockPos room, IncidentState.Kind kind) {
        return new Offer(UUID.randomUUID(), room, kind, ContractBalance.selectDifficulty(random, account.completed), slot);
    }

    private static boolean isOrdinaryRoom(ContractData.Accepted order) {
        return order.kind() == ContractData.Kind.ROOM || order.kind() == ContractData.Kind.SAMPLE;
    }

    private static boolean alreadyOffered(ContractData.Account account, BlockPos room) {
        for (Offer offer : account.incidentOffers) {
            if (offer != null && offer.room().equals(room)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasWork(ContractData.Account account, BlockPos room) {
        return account.incidents.stream().anyMatch(incident -> incident.originRoom().equals(room));
    }

    private static boolean available(ContractData.Account account, BlockPos room, IncidentState.Kind kind) {
        return !hasWork(account, room)
                && (kind == IncidentState.Kind.EVICTION || !account.failedEvictions.containsKey(room));
    }

    static boolean roomLoaded(ServerLevel site, ContractData.Account account, BlockPos position) {
        var order = room(account, position);
        return order != null && IncidentWorld.loaded(site, order.blueprint().bounds());
    }

    static boolean accept(ServerPlayer player, ContractData data, ContractData.Account account, Offer offer) {
        ServerLevel site = player.server.getLevel(ConstructionSite.SITE_LEVEL);
        var target = room(account, offer.room());
        if (site == null || target == null || !available(account, offer.room(), offer.kind())
                || (offer.kind().pausesConstruction() && blocksEvents(account))) {
            return false;
        }
        return ContractEvents.withLoadedArea(site, target.blueprint().bounds(),
                () -> activate(player, data, account, offer, false));
    }

    static boolean activate(ServerPlayer player, ContractData data, ContractData.Account account, Offer offer, boolean automatic) {
        ServerLevel site = player.server.getLevel(ConstructionSite.SITE_LEVEL);
        if (site == null || !roomLoaded(site, account, offer.room()) || !available(account, offer.room(), offer.kind())
                || ((automatic || offer.kind().pausesConstruction()) && blocksEvents(account))) {
            return false;
        }
        var room = room(account, offer.room()).blueprint();
        if (!suitableKinds(site, account, room).contains(offer.kind())) {
            return false;
        }
        var positions = IncidentWorld.interiorCandidates(site, room);
        long seed = player.getRandom().nextLong();
        UUID id = offer.id();
        IncidentState incident;
        switch (offer.kind()) {
            case CLEANUP -> {
                var target = IncidentWorld.graffitiTarget(site, room).orElse(null);
                if (target == null || !GraffitiEvents.place(player, site, id, target)) {
                    return false;
                }
                incident = IncidentState.cleanup(id, offer.level(), automatic, offer.room(), positions, GraffitiEvents.PATCH_COUNT, seed);
                if (!IncidentWorld.applyBlocks(site, room, incident)) {
                    GraffitiEvents.remove(site, id);
                    return false;
                }
            }
            case EVICTION -> {
                var survivors = account.failedEvictions.getOrDefault(offer.room(), List.of());
                List<UUID> occupants = survivors.isEmpty() ? IncidentWorld.residentTargets(site, offer.room()) : survivors;
                List<UUID> fresh = new ArrayList<>();
                if (occupants.isEmpty()) {
                    int count = Math.min(3, IncidentWorld.evictionCandidates(site, room).size());
                    for (int index = 0; index < count; index++) {
                        fresh.add(UUID.randomUUID());
                    }
                    occupants = fresh;
                }
                incident = IncidentState.eviction(id, offer.level(), automatic, offer.room(), occupants, seed);
                if (incident.remainingEvictionTargets().isEmpty() || (!fresh.isEmpty()
                        && !IncidentWorld.spawnEvictionTargets(site, room, incident, fresh))) {
                    return false;
                }
                if (!survivors.isEmpty()) {
                    survivors.removeAll(incident.remainingEvictionTargets());
                }
                if (survivors.isEmpty()) {
                    account.failedEvictions.remove(offer.room());
                }
            }
            case FIRE -> {
                var origin = IncidentWorld.fireOrigin(site, room).orElse(null);
                if (origin == null) {
                    return false;
                }
                incident = IncidentState.fire(id, offer.level(), automatic, offer.room(), origin);
                var repair = new FireRepair();
                repair.observe(site, incident, completedRooms(account), unfinishedRoom(account));
                if (!IncidentWorld.applyBlocks(site, room, incident)) {
                    return false;
                }
                repair.observe(site, incident, completedRooms(account), unfinishedRoom(account));
                account.fireRepair = repair;
            }
            case FLOOD -> {
                incident = IncidentState.flood(id, offer.level(), automatic, offer.room(), positions, seed);
                if (!IncidentWorld.applyBlocks(site, room, incident)) {
                    return false;
                }
            }
            default -> throw new IllegalStateException("Unexpected incident kind: " + offer.kind());
        }
        account.incidents.add(incident);
        if (offer.slot() >= 0) {
            account.incidentSlots.put(incident.id(), offer.slot());
        }
        if (incident.kind().pausesConstruction() && account.active != null && account.active.kind() != ContractData.Kind.STAIRS) {
            account.pausedAt = data.time(player.server);
        }
        data.setDirty();
        return true;
    }

    static OptionalInt submit(ServerLevel site, ContractData.Account account, IncidentState incident) {
        if (incident.kind() == IncidentState.Kind.EVICTION) {
            return incident.submitEviction();
        }
        var order = room(account, incident.originRoom());
        if (order == null) {
            return OptionalInt.empty();
        }
        if (incident.kind() == IncidentState.Kind.FIRE && account.fireRepair != null) {
            var bounds = new ArrayList<RoomBlueprint.Bounds>();
            for (BlockPos position : incident.affectedRooms()) {
                if (FireRepair.starterBounds().min().equals(position)) {
                    bounds.add(FireRepair.starterBounds());
                } else if (room(account, position) != null) {
                    bounds.add(room(account, position).blueprint().bounds());
                } else if (account.active != null && account.active.blueprint().bounds().min().equals(position)) {
                    bounds.add(account.active.blueprint().bounds());
                }
            }
            return ContractEvents.withLoadedAreas(site, bounds,
                    () -> account.fireRepair.submit(site, incident, completedRooms(account), unfinishedRoom(account)));
        }
        return ContractEvents.withLoadedArea(site, order.blueprint().bounds(), () -> submitInRoom(site, incident, order));
    }

    private static OptionalInt submitInRoom(ServerLevel site, IncidentState incident, ContractData.Accepted order) {
        return switch (incident.kind()) {
            case CLEANUP -> incident.submitCleanup(pos -> IncidentWorld.cobwebRemoved(site, pos));
            case FLOOD -> incident.submitFlood(pos -> IncidentWorld.roomHasNoWater(site, order.blueprint()),
                    pos -> IncidentWorld.floodFillRemoved(site, pos));
            case EVICTION, FIRE -> OptionalInt.empty();
        };
    }

    static void finish(ServerPlayer player, ContractData data, ContractData.Account account, IncidentState incident) {
        account.incidents.remove(incident);
        if (incident.kind() == IncidentState.Kind.FIRE) {
            account.fireRepair = null;
        }
        Integer slot = account.incidentSlots.remove(incident.id());
        if (slot != null) {
            account.incidentOffers[slot - 3] = null;
        }
        if (incident.kind().pausesConstruction() && account.pausedAt >= 0 && account.active != null) {
            var order = account.active;
            long pause = data.time(player.server) - account.pausedAt;
            account.active = order.withDeadline(order.deadline() + pause, order.lateCount());
        }
        if (incident.kind().pausesConstruction()) {
            account.pausedAt = -1;
        }
        data.setDirty();
    }

    static ContractData.Accepted room(ContractData.Account account, BlockPos position) {
        return account.rooms.stream().filter(order -> order.blueprint().bounds().min().equals(position)).findFirst().orElse(null);
    }

    private static List<RoomBlueprint> completedRooms(ContractData.Account account) {
        return account.rooms.stream().filter(order -> order.kind() != ContractData.Kind.STAIRS).map(ContractData.Accepted::blueprint).toList();
    }

    private static RoomBlueprint unfinishedRoom(ContractData.Account account) {
        return account.active == null ? null : account.active.blueprint();
    }

    private static String call(IncidentState.Kind kind) {
        return switch (kind) {
            case CLEANUP -> "Эй! У нас паутина и художники. Приведи комнату в порядок. Чао.";
            case EVICTION -> "Слушай, в комнате поселились незваные гости. Освободи помещение. Ну давай.";
            case FIRE -> "У тебя на стройке пожар! Потуши и приведи комнаты в порядок. Быстро!";
            case FLOOD -> "Эй, сантехник трубу не так подсоединил. Иди убери за ним. Ну давай, чао.";
        };
    }

    record Offer(UUID id, BlockPos room, IncidentState.Kind kind, ContractBalance.Difficulty level, int slot) {
        net.minecraft.nbt.CompoundTag save() {
            var tag = new net.minecraft.nbt.CompoundTag();
            tag.putUUID("Id", id);
            tag.putLong("Room", room.asLong());
            tag.putString("Kind", kind.name());
            tag.putString("Level", level.name());
            tag.putInt("Slot", slot);
            return tag;
        }

        static Offer load(net.minecraft.nbt.CompoundTag tag) {
            if (!tag.hasUUID("Id")) {
                return null;
            }
            return new Offer(tag.getUUID("Id"), BlockPos.of(tag.getLong("Room")), IncidentState.Kind.valueOf(tag.getString("Kind")),
                    ContractBalance.Difficulty.valueOf(tag.getString("Level")), tag.getInt("Slot"));
        }
    }
}
