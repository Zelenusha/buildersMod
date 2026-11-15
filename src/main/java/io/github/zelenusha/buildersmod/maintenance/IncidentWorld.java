package io.github.zelenusha.buildersmod.maintenance;

import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Bounds;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Criterion;
import io.github.zelenusha.buildersmod.site.ConstructionSite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class IncidentWorld {
    private static final String RESIDENT_ROOM = "buildersmod_resident_room";

    public record GraffitiTarget(BlockPos pos, Direction face) {
        public GraffitiTarget {
            pos = pos.immutable();
        }
    }

    private IncidentWorld() {}

    public static boolean loaded(ServerLevel level, Bounds bounds) {
        if (!level.isInWorldBounds(bounds.min()) || !level.isInWorldBounds(bounds.max())) {
            return false;
        }
        for (int x = SectionPos.blockToSectionCoord(bounds.min().getX()); x <= SectionPos.blockToSectionCoord(bounds.max().getX()); x++) {
            for (int z = SectionPos.blockToSectionCoord(bounds.min().getZ()); z <= SectionPos.blockToSectionCoord(bounds.max().getZ()); z++) {
                if (level.getChunkSource().getChunkNow(x, z) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    public static List<BlockPos> interiorCandidates(ServerLevel level, RoomBlueprint room) {
        if (!loaded(level, room.bounds())) {
            return List.of();
        }
        List<BlockPos> candidates = new ArrayList<>();
        for (Bounds volume : room.interiorVolumes()) {
            for (BlockPos pos : volume.positions()) {
                if (canPlaceIncidentBlock(level, room, pos)) {
                    candidates.add(pos);
                }
            }
        }
        return candidates;
    }

    public static Optional<GraffitiTarget> graffitiTarget(ServerLevel level, RoomBlueprint room) {
        if (!loaded(level, room.bounds())) {
            return Optional.empty();
        }
        var walls = room.requirements().entrySet().stream()
                .filter(entry -> entry.getValue().criterion() == Criterion.WALLS)
                .map(Map.Entry::getKey)
                .sorted().toList();
        for (BlockPos wall : walls) {
            if (protectedPosition(level, wall)) {
                continue;
            }
            BlockState surface = level.getBlockState(wall);
            if (surface.hasBlockEntity()) {
                continue;
            }
            for (Direction face : Direction.Plane.HORIZONTAL) {
                if (canOccupy(level, room, wall.relative(face)) && surface.isFaceSturdy(level, wall, face)) {
                    return Optional.of(new GraffitiTarget(wall, face));
                }
            }
        }
        return Optional.empty();
    }

    public static Optional<BlockPos> fireOrigin(ServerLevel level, RoomBlueprint room) {
        return interiorCandidates(level, room).stream()
                .filter(pos -> BaseFireBlock.getState(level, pos).canSurvive(level, pos))
                .findFirst();
    }

    public static boolean applyBlocks(ServerLevel level, RoomBlueprint room, IncidentState incident) {
        if (incident.status() != IncidentState.Status.ACTIVE || !loaded(level, room.bounds())) {
            return false;
        }
        if (incident.kind() == IncidentState.Kind.EVICTION) {
            return false;
        }
        if (incident.kind() == IncidentState.Kind.FLOOD
                && (incident.initialBlocks().isEmpty() || !roomHasNoWater(level, room))) {
            return false;
        }
        for (BlockPos pos : incident.initialBlocks()) {
            if (!canPlaceIncidentBlock(level, room, pos)
                    || incident.kind() == IncidentState.Kind.FIRE && !BaseFireBlock.getState(level, pos).canSurvive(level, pos)) {
                return false;
            }
        }

        List<BlockPos> placed = new ArrayList<>();
        for (BlockPos pos : incident.initialBlocks()) {
            BlockState state = switch (incident.kind()) {
                case CLEANUP -> Blocks.COBWEB.defaultBlockState();
                case FIRE -> BaseFireBlock.getState(level, pos);
                case FLOOD -> Blocks.WATER.defaultBlockState();
                case EVICTION -> throw new IllegalStateException("Eviction requires entity targets");
            };
            if (!level.setBlock(pos, state, Block.UPDATE_ALL)) {
                placed.forEach(location -> level.removeBlock(location, false));
                return false;
            }
            placed.add(pos);
        }
        return true;
    }

    public static boolean cobwebRemoved(ServerLevel level, BlockPos pos) {
        return loaded(level, new Bounds(pos, pos)) && !level.getBlockState(pos).is(Blocks.COBWEB);
    }

    public static boolean floodFillRemoved(ServerLevel level, BlockPos pos) {
        return loaded(level, new Bounds(pos, pos)) && level.isEmptyBlock(pos);
    }

    public static boolean roomHasNoWater(ServerLevel level, RoomBlueprint room) {
        if (!loaded(level, room.bounds())) {
            return false;
        }
        for (Bounds volume : room.interiorVolumes()) {
            for (BlockPos pos : volume.positions()) {
                if (level.getFluidState(pos).is(FluidTags.WATER)) {
                    return false;
                }
            }
        }
        return true;
    }

    public static boolean hasFire(ServerLevel level, Bounds area) {
        if (loaded(level, area)) {
            for (BlockPos pos : area.positions()) {
                if (level.getBlockState(pos).is(BlockTags.FIRE)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static List<BlockPos> evictionCandidates(ServerLevel level, RoomBlueprint room) {
        return interiorCandidates(level, room).stream().filter(pos -> canStand(level, room, pos)).toList();
    }

    public static List<UUID> residentTargets(ServerLevel level, BlockPos room) {
        List<UUID> residents = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (isLiveResident(entity, room)) {
                residents.add(entity.getUUID());
            }
        }
        residents.sort(UUID::compareTo);
        return residents;
    }

    public static boolean spawnEvictionTargets(ServerLevel level, RoomBlueprint room, IncidentState incident,
                                               Collection<UUID> newTargets) {
        if (incident.kind() != IncidentState.Kind.EVICTION || incident.status() != IncidentState.Status.ACTIVE
                || !loaded(level, room.bounds())) {
            return false;
        }
        List<UUID> targets = incident.remainingEvictionTargets();
        if (targets.isEmpty()) {
            return false;
        }
        List<UUID> missing = new ArrayList<>();
        for (UUID id : targets) {
            Entity existing = level.getEntity(id);
            if (existing == null && newTargets.contains(id)) {
                missing.add(id);
            } else if (existing != null && !isLiveResident(existing, incident.originRoom())) {
                return false;
            }
        }
        if (missing.isEmpty()) {
            return true;
        }
        List<BlockPos> positions = evictionCandidates(level, room);
        if (positions.size() < missing.size()) {
            return false;
        }

        List<Vindicator> spawned = new ArrayList<>();
        for (int index = 0; index < missing.size(); index++) {
            RoomResident resident = RoomResident.TYPE.get().create(level);
            if (resident == null) {
                spawned.forEach(Entity::discard);
                return false;
            }
            BlockPos pos = positions.get(index);
            resident.setUUID(missing.get(index));
            resident.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
            resident.setCustomName(Component.translatableWithFallback("entity.buildersmod.resident", "Самозваный жилец"));
            resident.setCustomNameVisible(true);
            resident.setPersistenceRequired();
            resident.getPersistentData().putLong(RESIDENT_ROOM, incident.originRoom().asLong());
            if (!level.addFreshEntity(resident)) {
                spawned.forEach(Entity::discard);
                return false;
            }
            spawned.add(resident);
        }
        return true;
    }

    public static boolean recordEvictionDeath(IncidentState incident, LivingEntity entity) {
        return isResident(entity, incident.originRoom()) && incident.recordTargetDeath(entity.getUUID());
    }

    private static boolean canOccupy(ServerLevel level, RoomBlueprint room, BlockPos pos) {
        if (!room.containsInterior(pos) || protectedPosition(level, pos)) {
            return false;
        }
        RoomBlueprint.Requirement requirement = room.requirements().get(pos);
        return (requirement == null || requirement.clearance()) && level.isEmptyBlock(pos);
    }

    private static boolean canPlaceIncidentBlock(ServerLevel level, RoomBlueprint room, BlockPos pos) {
        return canOccupy(level, room, pos) && level.getEntities(null, new AABB(pos)).isEmpty();
    }

    private static boolean canStand(ServerLevel level, RoomBlueprint room, BlockPos pos) {
        if (!canOccupy(level, room, pos.above())
                || !level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) {
            return false;
        }
        AABB space = RoomResident.TYPE.get().getDimensions().makeBoundingBox(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        return level.noCollision(space) && level.getEntities(null, space).isEmpty();
    }

    private static boolean protectedPosition(ServerLevel level, BlockPos pos) {
        return !level.isInWorldBounds(pos) || !level.getWorldBorder().isWithinBounds(pos)
                || ConstructionSite.isProtectedArrival(level, pos)
                || level.dimension() == ConstructionSite.SITE_LEVEL && (ConstructionSite.isReservedSiteBlock(pos)
                || ConstructionSite.isOriginalColumnPosition(pos));
    }

    private static boolean isResident(Entity entity, BlockPos room) {
        return entity instanceof Vindicator && entity.getPersistentData().contains(RESIDENT_ROOM)
                && entity.getPersistentData().getLong(RESIDENT_ROOM) == room.asLong();
    }

    private static boolean isLiveResident(Entity entity, BlockPos room) {
        return isResident(entity, room) && entity.isAlive();
    }
}
