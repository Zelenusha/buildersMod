package io.github.zelenusha.buildersmod.intro;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class IntroData extends SavedData {
    private static final String FILE_NAME = "buildersmod_intro";
    private final Map<UUID, Work> players = new HashMap<>();
    private final Set<Long> protectedBlocks = new HashSet<>();

    public static IntroData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(IntroData::load, IntroData::new, FILE_NAME);
    }

    Work work(UUID player) {
        return players.computeIfAbsent(player, unused -> new Work());
    }

    public boolean hasAccepted(UUID player) {
        Work work = players.get(player);
        return work != null && work.office != null;
    }

    public long firstOffersDay(UUID player) {
        Work work = players.get(player);
        return work != null && work.office != null ? work.firstOffersDay : Long.MAX_VALUE;
    }

    public BlockPos dugout(UUID player) {
        Work work = players.get(player);
        return work == null || work.dugout == null ? null : work.dugout.offset(2, 0, 2);
    }

    public BlockPos officeArrival(UUID player) {
        Work work = players.get(player);
        return work == null || work.office == null ? null : IntroStructures.officeArrival(work.office);
    }

    public BlockPos officePortalOpening(UUID player) {
        Work work = players.get(player);
        return work == null || work.office == null ? null : IntroStructures.officePortalFrame(work.office).above().east();
    }

    boolean isProtected(BlockPos position) {
        return protectedBlocks.contains(position.asLong());
    }

    void protect(BlockPos position) {
        if (protectedBlocks.add(position.asLong())) {
            setDirty();
        }
    }

    void unprotect(BlockPos position) {
        if (protectedBlocks.remove(position.asLong())) {
            setDirty();
        }
    }

    private static IntroData load(CompoundTag tag) {
        IntroData data = new IntroData();
        for (Tag entry : tag.getList("Players", Tag.TAG_COMPOUND)) {
            CompoundTag saved = (CompoundTag) entry;
            Work work = data.work(saved.getUUID("Player"));
            if (saved.hasUUID("Builder")) {
                work.builder = saved.getUUID("Builder");
            }
            if (saved.contains("BuilderReturnDay")) {
                work.builderReturnDay = saved.getLong("BuilderReturnDay");
            }
            if (saved.contains("Office")) {
                work.office = BlockPos.of(saved.getLong("Office"));
                work.dugout = BlockPos.of(saved.getLong("Dugout"));
                work.firstOffersDay = saved.getLong("FirstOffersDay");
            }
            if (saved.hasUUID("Trader")) {
                work.trader = saved.getUUID("Trader");
            }
        }
        for (long position : tag.getLongArray("ProtectedBlocks")) {
            data.protectedBlocks.add(position);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag savedPlayers = new ListTag();
        players.forEach((player, work) -> {
            CompoundTag saved = new CompoundTag();
            saved.putUUID("Player", player);
            if (work.builder != null) {
                saved.putUUID("Builder", work.builder);
            }
            if (work.builderReturnDay >= 0) {
                saved.putLong("BuilderReturnDay", work.builderReturnDay);
            }
            if (work.office != null) {
                saved.putLong("Office", work.office.asLong());
                saved.putLong("Dugout", work.dugout.asLong());
                saved.putLong("FirstOffersDay", work.firstOffersDay);
            }
            if (work.trader != null) {
                saved.putUUID("Trader", work.trader);
            }
            savedPlayers.add(saved);
        });
        tag.put("Players", savedPlayers);
        tag.putLongArray("ProtectedBlocks", protectedBlocks.stream().mapToLong(Long::longValue).toArray());
        return tag;
    }

    static final class Work {
        UUID builder;
        long builderReturnDay = -1;
        BlockPos office;
        BlockPos dugout;
        long firstOffersDay = Long.MAX_VALUE;
        UUID trader;
    }
}
