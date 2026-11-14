package io.github.zelenusha.buildersmod.contract;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint;
import io.github.zelenusha.buildersmod.blueprint.SampleRoomBlueprint;
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

    public record Accepted(BlockPos anchor, Direction outward, ContractBalance.Difficulty difficulty,
                           long deadline, boolean extraDay, RoomBlueprint blueprint, int wage, Kind kind, int slot,
                           int lateCount, Map<BlockPos, net.minecraft.world.level.block.state.BlockState> clearanceOriginals) {
        public Accepted withExtraDay() {
            return new Accepted(anchor, outward, difficulty, deadline + ContractBalance.TICKS_PER_DAY, true,
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
        int completed;
        long unpaid;
        FineLedger fines = new FineLedger();
        int highestFloor = 1;
        int sequence;
        Accepted special;
        final Accepted[] offers = new Accepted[5];
        final List<Accepted> rooms = new ArrayList<>();
        final List<Accepted> failed = new ArrayList<>();
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

    private static ContractData load(CompoundTag root) {
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
            account.highestFloor = Math.max(1, Math.min(8, player.getInt("HighestFloor")));
            account.sequence = player.getInt("Sequence");
            account.fines = loadFines(player.getList("Fines", Tag.TAG_COMPOUND));
            int legacyFine = (int) Math.min(Integer.MAX_VALUE, player.getLong("Fine"));
            if (legacyFine > 0) {
                account.fines.issue(legacyFine, "Недострой", 1);
            }
            account.active = loadContract(player.getCompound("Active"));
            account.special = loadContract(player.getCompound("Special"));
            for (int slot = 0; slot < account.offers.length; slot++) {
                account.offers[slot] = loadContract(player.getCompound("Offer" + slot));
            }
            loadContracts(account.rooms, player.getList("Rooms", Tag.TAG_COMPOUND));
            loadContracts(account.failed, player.getList("Failed", Tag.TAG_COMPOUND));
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

    @Override
    public CompoundTag save(CompoundTag root) {
        ListTag players = new ListTag();
        accounts.forEach((id, account) -> {
            CompoundTag player = new CompoundTag();
            player.putUUID("Player", id);
            player.putInt("Completed", account.completed);
            player.putLong("Unpaid", account.unpaid);
            player.putInt("HighestFloor", account.highestFloor);
            player.putInt("Sequence", account.sequence);
            player.put("Fines", saveFines(account.fines));
            if (account.active != null) {
                player.put("Active", saveContract(account.active));
            }
            if (account.special != null) {
                player.put("Special", saveContract(account.special));
            }
            for (int slot = 0; slot < account.offers.length; slot++) {
                if (account.offers[slot] != null) {
                    player.put("Offer" + slot, saveContract(account.offers[slot]));
                }
            }
            player.put("Rooms", saveContracts(account.rooms));
            player.put("Failed", saveContracts(account.failed));
            players.add(player);
        });
        root.putInt("Version", 2);
        root.putLong("SleepTicks", sleepTicks);
        root.put("Players", players);
        return root;
    }
}
