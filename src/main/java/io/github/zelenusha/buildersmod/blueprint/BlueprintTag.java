package io.github.zelenusha.buildersmod.blueprint;

import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Bonus;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Bounds;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Criterion;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Door;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Requirement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

final class BlueprintTag {
    private static final int VERSION = 1;

    private BlueprintTag() {
    }

    static CompoundTag write(RoomBlueprint blueprint) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("version", VERSION);
        tag.put("bounds", writeBounds(blueprint.bounds()));

        ListTag interiors = new ListTag();
        for (Bounds volume : blueprint.interiorVolumes()) {
            interiors.add(writeBounds(volume));
        }
        tag.put("interiors", interiors);

        ListTag requirements = new ListTag();
        blueprint.requirements().forEach((position, requirement) -> {
            CompoundTag entry = new CompoundTag();
            entry.putLong("position", position.asLong());
            entry.put("state", NbtUtils.writeBlockState(requirement.expectedState()));
            entry.putString("criterion", requirement.criterion().name());
            if (!requirement.replacements().isEmpty()) {
                ListTag replacements = new ListTag();
                requirement.replacements().forEach(state -> replacements.add(NbtUtils.writeBlockState(state)));
                entry.put("replacements", replacements);
            }
            requirements.add(entry);
        });
        tag.put("requirements", requirements);

        ListTag doors = new ListTag();
        for (Door door : blueprint.doors()) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("position", door.lowerPosition().asLong());
            entry.putString("outward", door.outward().getName());
            doors.add(entry);
        }
        tag.put("doors", doors);

        blueprint.bonus().ifPresent(bonus -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("block", BuiltInRegistries.BLOCK.getKey(bonus.block()).toString());
            entry.putInt("count", bonus.exactCount());
            entry.putInt("emeralds", bonus.emeralds());
            tag.put("bonus", entry);
        });
        return tag;
    }

    static RoomBlueprint read(CompoundTag tag) {
        if (tag.getInt("version") != VERSION || !tag.contains("bounds", Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("Unsupported or missing room blueprint version");
        }
        Bounds bounds = readBounds(tag.getCompound("bounds"));
        List<Bounds> interiors = new ArrayList<>();
        ListTag interiorTags = tag.getList("interiors", Tag.TAG_COMPOUND);
        for (int index = 0; index < interiorTags.size(); index++) {
            Bounds volume = readBounds(interiorTags.getCompound(index));
            if (!bounds.contains(volume.min()) || !bounds.contains(volume.max())) {
                throw new IllegalArgumentException("Interior volume falls outside the room");
            }
            interiors.add(volume);
        }

        Map<BlockPos, Requirement> requirements = new LinkedHashMap<>();
        ListTag requirementTags = tag.getList("requirements", Tag.TAG_COMPOUND);
        for (int index = 0; index < requirementTags.size(); index++) {
            CompoundTag entry = requirementTags.getCompound(index);
            BlockPos position = readPosition(entry);
            Criterion criterion = Criterion.valueOf(entry.getString("criterion"));
            BlockState state = readState(entry.getCompound("state"));
            List<BlockState> replacements = new ArrayList<>();
            ListTag replacementTags = entry.getList("replacements", Tag.TAG_COMPOUND);
            for (int replacement = 0; replacement < replacementTags.size(); replacement++) {
                replacements.add(readState(replacementTags.getCompound(replacement)));
            }
            Requirement requirement = new Requirement(state, criterion, replacements);
            if (!bounds.contains(position) || requirements.putIfAbsent(position, requirement) != null) {
                throw new IllegalArgumentException("Invalid or duplicate blueprint position: " + position);
            }
        }

        List<Door> doors = new ArrayList<>();
        ListTag doorTags = tag.getList("doors", Tag.TAG_COMPOUND);
        for (int index = 0; index < doorTags.size(); index++) {
            CompoundTag entry = doorTags.getCompound(index);
            BlockPos position = readPosition(entry);
            Direction outward = Direction.byName(entry.getString("outward"));
            if (outward == null || !bounds.contains(position)) {
                throw new IllegalArgumentException("Invalid blueprint doorway: " + position);
            }
            doors.add(new Door(position, outward));
        }

        Optional<Bonus> bonus = Optional.empty();
        if (tag.contains("bonus", Tag.TAG_COMPOUND)) {
            CompoundTag entry = tag.getCompound("bonus");
            bonus = Optional.of(new Bonus(readBlock(entry.getString("block")), entry.getInt("count"),
                    entry.getInt("emeralds")));
        }
        return new RoomBlueprint(bounds, interiors, requirements, doors, bonus);
    }

    private static CompoundTag writeBounds(Bounds bounds) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("min", bounds.min().asLong());
        tag.putLong("max", bounds.max().asLong());
        return tag;
    }

    private static Bounds readBounds(CompoundTag tag) {
        if (!tag.contains("min", Tag.TAG_LONG) || !tag.contains("max", Tag.TAG_LONG)) {
            throw new IllegalArgumentException("Invalid blueprint bounds");
        }
        return new Bounds(BlockPos.of(tag.getLong("min")), BlockPos.of(tag.getLong("max")));
    }

    private static BlockPos readPosition(CompoundTag tag) {
        if (!tag.contains("position", Tag.TAG_LONG)) {
            throw new IllegalArgumentException("Missing blueprint position");
        }
        return BlockPos.of(tag.getLong("position"));
    }

    private static Block readBlock(String name) {
        ResourceLocation id = ResourceLocation.tryParse(name);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            throw new IllegalArgumentException("Missing blueprint block: " + name);
        }
        return BuiltInRegistries.BLOCK.get(id);
    }

    private static BlockState readState(CompoundTag stateTag) {
        readBlock(stateTag.getString("Name"));
        return NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), stateTag);
    }
}
