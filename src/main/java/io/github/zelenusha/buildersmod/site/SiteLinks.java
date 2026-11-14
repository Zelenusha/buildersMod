package io.github.zelenusha.buildersmod.site;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

final class SiteLinks extends SavedData {
    private final Map<UUID, PortalLocation> offices = new HashMap<>();

    static SiteLinks get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(SiteLinks::load, SiteLinks::new, "buildersmod_site_links");
    }

    private static SiteLinks load(CompoundTag root) {
        SiteLinks links = new SiteLinks();
        for (Tag raw : root.getList("Offices", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) raw;
            if (entry.hasUUID("Player") && entry.contains("Frame", Tag.TAG_LONG) && entry.contains("Arrival", Tag.TAG_LONG)) {
                Direction.Axis axis = entry.getBoolean("AxisZ") ? Direction.Axis.Z : Direction.Axis.X;
                links.offices.put(entry.getUUID("Player"), new PortalLocation(
                        BlockPos.of(entry.getLong("Frame")), axis, BlockPos.of(entry.getLong("Arrival"))));
            }
        }
        return links;
    }

    void put(UUID player, PortalLocation location) {
        offices.put(player, location);
        setDirty();
    }

    PortalLocation forPlayer(UUID player) {
        return offices.get(player);
    }

    boolean protectsArrival(BlockPos pos) {
        return offices.values().stream().anyMatch(office -> office.containsArrival(pos));
    }

    boolean protectsFromBreak(BlockPos pos) {
        return offices.values().stream().anyMatch(office -> office.protectsFromBreak(pos));
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        ListTag entries = new ListTag();
        offices.forEach((player, location) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Player", player);
            entry.putLong("Frame", location.lowerLeft().asLong());
            entry.putBoolean("AxisZ", location.axis() == Direction.Axis.Z);
            entry.putLong("Arrival", location.arrival().asLong());
            entries.add(entry);
        });
        root.put("Offices", entries);
        return root;
    }
}
