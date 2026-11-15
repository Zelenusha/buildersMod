package io.github.zelenusha.buildersmod.contract;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint;
import io.github.zelenusha.buildersmod.blueprint.SampleRoomBlueprint;
import io.github.zelenusha.buildersmod.maintenance.IncidentState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

public final class ContractData extends SavedData {
    private static final String SAVE_ID = "buildersmod_contracts";

    public enum Kind {
        SAMPLE("Пробная комната"), ROOM("Строительство комнаты"), STAIRS("Лестница и новый этаж"),
        COURTYARD("Дворик"), CORRIDOR("Запасной коридор");

        public final String title;

        Kind(String title) {
            this.title = title;
        }
    }

    enum StairInspection {
        WAITING, READY_AT_DEADLINE, NEEDS_REPAIR_DAY;

        boolean canBuyDay(boolean alreadyBought) {
            return this == NEEDS_REPAIR_DAY || (this == WAITING && !alreadyBought);
        }

        static StairInspection load(String name) {
            for (StairInspection inspection : values()) {
                if (inspection.name().equals(name)) {
                    return inspection;
                }
            }
            return WAITING;
        }
    }

    public record Accepted(BlockPos anchor, Direction outward, ContractBalance.Difficulty difficulty,
                           long deadline, boolean extraDay, RoomBlueprint blueprint, int wage, Kind kind, int slot,
                           int lateCount, Map<BlockPos, net.minecraft.world.level.block.state.BlockState> clearanceOriginals) {
        public Accepted withExtraDay(long now) {
            return new Accepted(anchor, outward, difficulty, Math.max(now, deadline) + ContractBalance.TICKS_PER_DAY, true,
                    blueprint, wage, kind, slot, lateCount, clearanceOriginals);
        }

        public Accepted started(long time) {
            return withDeadline(time + difficulty.duration(), lateCount);
        }

        public Accepted withDeadline(long nextDeadline, int nextLateCount) {
            return new Accepted(anchor, outward, difficulty, nextDeadline, extraDay, blueprint, wage, kind, slot,
                    nextLateCount, clearanceOriginals);
        }

        public Accepted rebound(BlockPos nextAnchor, Direction nextOutward, RoomBlueprint placed,
                                Map<BlockPos, net.minecraft.world.level.block.state.BlockState> originals) {
            return new Accepted(nextAnchor, nextOutward, difficulty, deadline, extraDay, placed, wage, kind, slot,
                    lateCount, originals);
        }
    }

    public static final class Account {
        Accepted active;
        StairInspection stairInspection = StairInspection.WAITING;
        int completed;
        long pendingWage;
        long unpaid;
        FineLedger fines = new FineLedger();
        AppealPuzzle appeal;
        int highestFloor = 1;
        int sequence;
        Accepted special;
        Accepted pendingCourtyard;
        int nextCourtyardSlot = -2;
        final Accepted[] offers = new Accepted[5];
        final List<Accepted> courtyards = new ArrayList<>();
        final List<Accepted> rooms = new ArrayList<>();
        final List<DemolitionWork> failed = new ArrayList<>();
        IncidentState.Schedule incidentSchedule;
        IncidentWork.Offer pendingIncident;
        final IncidentWork.Offer[] incidentOffers = new IncidentWork.Offer[2];
        final List<IncidentState> incidents = new ArrayList<>();
        final Map<UUID, Integer> incidentSlots = new HashMap<>();
        io.github.zelenusha.buildersmod.maintenance.FireRepair fireRepair;
        final Map<BlockPos, List<UUID>> failedEvictions = new HashMap<>();
        long pausedAt = -1;
        String lastCall = "";
    }

    private final Map<UUID, Account> accounts = new HashMap<>();
    private long sleepTicks;

    public static ContractData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(ContractData::load, ContractData::new, SAVE_ID);
    }

    Account account(UUID id) {
        return accounts.computeIfAbsent(id, ignored -> new Account());
    }

    long time(MinecraftServer server) {
        return server.overworld().getGameTime() + sleepTicks;
    }

    void advanceSleep(long ticks) {
        if (ticks > 0) {
            sleepTicks += ticks;
            setDirty();
        }
    }

    void recordEvictionDeath(net.minecraft.world.entity.LivingEntity entity) {
        for (Account account : accounts.values()) {
            for (IncidentState incident : account.incidents) {
                if (io.github.zelenusha.buildersmod.maintenance.IncidentWorld.recordEvictionDeath(incident, entity)) {
                    setDirty();
                }
            }
            for (List<UUID> survivors : account.failedEvictions.values()) {
                if (survivors.remove(entity.getUUID())) {
                    setDirty();
                }
            }
        }
    }

    static ContractData load(CompoundTag root) {
        ContractData data = new ContractData();
        data.sleepTicks = root.getLong("SleepTicks");
        ListTag players = root.getList("Players", Tag.TAG_COMPOUND);
        for (int index = 0; index < players.size(); index++) {
            CompoundTag player = players.getCompound(index);
            if (!player.hasUUID("Player")) {
                continue;
            }
            Account account = data.account(player.getUUID("Player"));
            account.completed = Math.max(0, player.getInt("Completed"));
            account.unpaid = Math.max(0, player.getLong("Unpaid"));
            account.pendingWage = Math.max(0, player.getLong("PendingWage"));
            account.highestFloor = Math.max(1, Math.min(8, player.getInt("HighestFloor")));
            account.sequence = player.getInt("Sequence");
            account.fines = loadFines(player.getList("Fines", Tag.TAG_COMPOUND));
            int legacyFine = (int) Math.min(Integer.MAX_VALUE, player.getLong("Fine"));
            if (legacyFine > 0) {
                account.fines.issue(legacyFine, "Недострой", 1);
            }
            account.active = loadContract(player.getCompound("Active"));
            if (account.active != null && account.active.kind() == Kind.STAIRS) {
                account.stairInspection = StairInspection.load(player.getString("StairInspection"));
            }
            account.special = loadContract(player.getCompound("Special"));
            account.pendingCourtyard = loadContract(player.getCompound("PendingCourtyard"));
            account.nextCourtyardSlot = player.contains("NextCourtyardSlot") ? player.getInt("NextCourtyardSlot") : -2;
            loadContracts(account.courtyards, player.getList("Courtyards", Tag.TAG_COMPOUND));
            for (int slot = 0; slot < account.offers.length; slot++) {
                account.offers[slot] = loadContract(player.getCompound("Offer" + slot));
            }
            loadContracts(account.rooms, player.getList("Rooms", Tag.TAG_COMPOUND));
            ListTag failures = player.getList("Failed", Tag.TAG_COMPOUND);
            for (int failureIndex = 0; failureIndex < failures.size(); failureIndex++) {
                CompoundTag failure = failures.getCompound(failureIndex);
                Accepted order = loadContract(failure);
                if (order != null) {
                    account.failed.add(DemolitionWork.load(order, failure.getCompound("Demolition")));
                }
            }
            loadIncidents(player, account);
        }
        return data;
    }

    private static Accepted loadContract(CompoundTag tag) {
        Direction outward = Direction.byName(tag.getString("Outward"));
        int difficulty = tag.getInt("Difficulty");
        if (outward == null || !outward.getAxis().isHorizontal()
                || difficulty < 0 || difficulty >= ContractBalance.Difficulty.values().length) {
            return null;
        }
        BlockPos anchor = BlockPos.of(tag.getLong("Anchor"));
        try {
            Kind kind = tag.contains("Kind") ? Kind.valueOf(tag.getString("Kind")) : Kind.SAMPLE;
            RoomBlueprint blueprint = tag.contains("Blueprint", Tag.TAG_COMPOUND)
                    ? RoomBlueprint.decode(tag.getCompound("Blueprint")) : SampleRoomBlueprint.at(anchor, outward);
            var originals = new HashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
            ListTag states = tag.getList("Originals", Tag.TAG_COMPOUND);
            for (int index = 0; index < states.size(); index++) {
                CompoundTag entry = states.getCompound(index);
                originals.put(BlockPos.of(entry.getLong("Pos")), net.minecraft.nbt.NbtUtils.readBlockState(
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup(), entry.getCompound("State")));
            }
            return new Accepted(anchor, outward, ContractBalance.Difficulty.values()[difficulty], tag.getLong("Deadline"),
                    tag.getBoolean("ExtraDay"), blueprint, tag.contains("Wage") ? tag.getInt("Wage") : 6, kind,
                    tag.getInt("Slot"), tag.getInt("LateCount"), Map.copyOf(originals));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static void loadContracts(List<Accepted> target, ListTag tags) {
        for (int index = 0; index < tags.size(); index++) {
            Accepted contract = loadContract(tags.getCompound(index));
            if (contract != null) {
                target.add(contract);
            }
        }
    }

    private static CompoundTag saveContract(Accepted contract) {
        CompoundTag order = new CompoundTag();
        order.putLong("Anchor", contract.anchor().asLong());
        order.putString("Outward", contract.outward().getName());
        order.putInt("Difficulty", contract.difficulty().ordinal());
        order.putLong("Deadline", contract.deadline());
        order.putBoolean("ExtraDay", contract.extraDay());
        order.put("Blueprint", contract.blueprint().encode());
        order.putString("Kind", contract.kind().name());
        order.putInt("Wage", contract.wage());
        order.putInt("Slot", contract.slot());
        order.putInt("LateCount", contract.lateCount());
        ListTag states = new ListTag();
        contract.clearanceOriginals().forEach((position, state) -> {
            CompoundTag entry = new CompoundTag();
            entry.putLong("Pos", position.asLong());
            entry.put("State", net.minecraft.nbt.NbtUtils.writeBlockState(state));
            states.add(entry);
        });
        order.put("Originals", states);
        return order;
    }

    private static FineLedger loadFines(ListTag tags) {
        List<FineLedger.Fine> entries = new ArrayList<>();
        for (int index = 0; index < tags.size(); index++) {
            CompoundTag tag = tags.getCompound(index);
            if (tag.hasUUID("Id")) {
                entries.add(new FineLedger.Fine(tag.getUUID("Id"), tag.getInt("Amount"), tag.getInt("Withheld"),
                        tag.getString("Reason"), tag.getInt("Audacity"), tag.getInt("Attempts"), tag.getBoolean("Overturned")));
            }
        }
        return new FineLedger(entries);
    }

    private static ListTag saveFines(FineLedger ledger) {
        ListTag tags = new ListTag();
        for (FineLedger.Fine fine : ledger.entries()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", fine.id());
            entry.putInt("Amount", fine.amount());
            entry.putInt("Withheld", fine.withheld());
            entry.putString("Reason", fine.reason());
            entry.putInt("Audacity", fine.audacity());
            entry.putInt("Attempts", fine.attemptsUsed());
            entry.putBoolean("Overturned", fine.overturned());
            tags.add(entry);
        }
        return tags;
    }

    private static ListTag saveContracts(List<Accepted> contracts) {
        ListTag tags = new ListTag();
        contracts.forEach(contract -> tags.add(saveContract(contract)));
        return tags;
    }

    private static void loadIncidents(CompoundTag player, Account account) {
        if (player.contains("IncidentSchedule", Tag.TAG_COMPOUND)) {
            account.incidentSchedule = IncidentState.Schedule.load(player.getCompound("IncidentSchedule"));
        }
        account.pendingIncident = IncidentWork.Offer.load(player.getCompound("PendingIncident"));
        for (int index = 0; index < account.incidentOffers.length; index++) {
            account.incidentOffers[index] = IncidentWork.Offer.load(player.getCompound("IncidentOffer" + index));
        }
        ListTag active = player.getList("Incidents", Tag.TAG_COMPOUND);
        for (int index = 0; index < active.size(); index++) {
            CompoundTag entry = active.getCompound(index);
            IncidentState incident = IncidentState.load(entry);
            account.incidents.add(incident);
            if (entry.contains("Slot")) {
                account.incidentSlots.put(incident.id(), entry.getInt("Slot"));
            }
        }
        ListTag evictions = player.getList("FailedEvictions", Tag.TAG_COMPOUND);
        for (int index = 0; index < evictions.size(); index++) {
            CompoundTag entry = evictions.getCompound(index);
            ListTag targets = entry.getList("Survivors", Tag.TAG_COMPOUND);
            List<UUID> ids = new ArrayList<>();
            for (int target = 0; target < targets.size(); target++) {
                ids.add(targets.getCompound(target).getUUID("Id"));
            }
            account.failedEvictions.put(BlockPos.of(entry.getLong("Room")), ids);
        }
        if (player.contains("FireRepair", Tag.TAG_COMPOUND)) {
            account.fireRepair = io.github.zelenusha.buildersmod.maintenance.FireRepair.load(player.getCompound("FireRepair"));
        }
        account.pausedAt = player.contains("PausedAt") ? player.getLong("PausedAt") : -1;
        account.lastCall = player.getString("LastCall");
    }

    private static void saveIncidents(CompoundTag player, Account account) {
        if (account.incidentSchedule != null) {
            player.put("IncidentSchedule", account.incidentSchedule.save());
        }
        if (account.pendingIncident != null) {
            player.put("PendingIncident", account.pendingIncident.save());
        }
        for (int index = 0; index < account.incidentOffers.length; index++) {
            if (account.incidentOffers[index] != null) {
                player.put("IncidentOffer" + index, account.incidentOffers[index].save());
            }
        }
        ListTag active = new ListTag();
        for (IncidentState incident : account.incidents) {
            CompoundTag entry = incident.save();
            Integer slot = account.incidentSlots.get(incident.id());
            if (slot != null) {
                entry.putInt("Slot", slot);
            }
            active.add(entry);
        }
        player.put("Incidents", active);
        ListTag evictions = new ListTag();
        account.failedEvictions.forEach((room, ids) -> {
            var entry = new CompoundTag();
            entry.putLong("Room", room.asLong());
            var targets = new ListTag();
            for (UUID id : ids) {
                var target = new CompoundTag();
                target.putUUID("Id", id);
                targets.add(target);
            }
            entry.put("Survivors", targets);
            evictions.add(entry);
        });
        player.put("FailedEvictions", evictions);
        if (account.fireRepair != null) {
            player.put("FireRepair", account.fireRepair.save());
        }
        player.putLong("PausedAt", account.pausedAt);
        player.putString("LastCall", account.lastCall);
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        ListTag players = new ListTag();
        accounts.forEach((id, account) -> {
            CompoundTag player = new CompoundTag();
            player.putUUID("Player", id);
            player.putInt("Completed", account.completed);
            player.putLong("Unpaid", account.unpaid);
            player.putLong("PendingWage", account.pendingWage);
            player.putInt("HighestFloor", account.highestFloor);
            player.putInt("Sequence", account.sequence);
            player.put("Fines", saveFines(account.fines));
            if (account.active != null) {
                player.put("Active", saveContract(account.active));
                player.putString("StairInspection", account.stairInspection.name());
            }
            if (account.special != null) {
                player.put("Special", saveContract(account.special));
            }
            if (account.pendingCourtyard != null) {
                player.put("PendingCourtyard", saveContract(account.pendingCourtyard));
            }
            player.putInt("NextCourtyardSlot", account.nextCourtyardSlot);
            player.put("Courtyards", saveContracts(account.courtyards));
            for (int slot = 0; slot < account.offers.length; slot++) {
                if (account.offers[slot] != null) {
                    player.put("Offer" + slot, saveContract(account.offers[slot]));
                }
            }
            player.put("Rooms", saveContracts(account.rooms));
            ListTag failures = new ListTag();
            account.failed.forEach(work -> {
                CompoundTag failure = saveContract(work.order());
                failure.put("Demolition", work.save());
                failures.add(failure);
            });
            player.put("Failed", failures);
            saveIncidents(player, account);
            players.add(player);
        });
        root.putInt("Version", 2);
        root.putLong("SleepTicks", sleepTicks);
        root.put("Players", players);
        return root;
    }
}
