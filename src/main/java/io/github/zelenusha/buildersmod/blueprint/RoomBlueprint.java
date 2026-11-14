package io.github.zelenusha.buildersmod.blueprint;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

public final class RoomBlueprint {
    public enum Criterion {
        FLOOR,
        WALLS,
        CEILING,
        DOORWAYS,
        LIGHTING,
        FUNCTIONAL_BLOCKS,
        WINDOWS,
        STAIRWAYS,
        CLEARANCE
    }

    public enum Status {
        MISSING,
        WRONG,
        CORRECT
    }

    public record Requirement(BlockState expectedState, Criterion criterion, List<BlockState> replacements) {
        public Requirement {
            Objects.requireNonNull(expectedState, "expectedState");
            Objects.requireNonNull(criterion, "criterion");
            replacements = List.copyOf(replacements);
            if (expectedState.isAir() != (criterion == Criterion.CLEARANCE)) {
                throw new IllegalArgumentException("Only clearance can require an air block");
            }
            if (criterion == Criterion.DOORWAYS && !(expectedState.getBlock() instanceof DoorBlock)
                    && !(expectedState.getBlock() instanceof FenceGateBlock)) {
                throw new IllegalArgumentException("A doorway must require a door or gate block");
            }
            if (!replacements.isEmpty() && (criterion == Criterion.CLEARANCE || criterion == Criterion.DOORWAYS
                    || replacements.stream().anyMatch(BlockState::isAir))) {
                throw new IllegalArgumentException("Only a solid requirement can allow replacement blocks");
            }
        }

        public Requirement(BlockState expectedState, Criterion criterion) {
            this(expectedState, criterion, List.of());
        }

        Status check(BlockPos position, Function<BlockPos, BlockState> blocks) {
            BlockState actual = blocks.apply(position);
            if (clearance()) {
                return actual.isAir() ? Status.CORRECT : Status.WRONG;
            }
            if (actual.isAir()) {
                return Status.MISSING;
            }
            if (replacements.contains(actual)) {
                return Status.CORRECT;
            }
            if (!actual.is(expectedState.getBlock())) {
                return Status.WRONG;
            }
            if (criterion != Criterion.DOORWAYS) {
                return checkBlockState(actual) ? Status.CORRECT : Status.WRONG;
            }
            if (expectedState.getBlock() instanceof FenceGateBlock) {
                return actual.getValue(FenceGateBlock.FACING).getAxis() == expectedState.getValue(FenceGateBlock.FACING).getAxis()
                        ? Status.CORRECT : Status.WRONG;
            }

            DoubleBlockHalf half = expectedState.getValue(DoorBlock.HALF);
            Direction facing = actual.getValue(DoorBlock.FACING);
            if (actual.getValue(DoorBlock.HALF) != half
                    || facing.getAxis() != expectedState.getValue(DoorBlock.FACING).getAxis()) {
                return Status.WRONG;
            }
            if (half == DoubleBlockHalf.UPPER) {
                BlockState lower = blocks.apply(position.below());
                if (lower.is(expectedState.getBlock()) && lower.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                        && lower.getValue(DoorBlock.FACING).getAxis() == facing.getAxis()
                        && lower.getValue(DoorBlock.FACING) != facing) {
                    return Status.WRONG;
                }
            }
            return Status.CORRECT;
        }

        public boolean clearance() {
            return criterion == Criterion.CLEARANCE;
        }

        Requirement rotate(Rotation rotation) {
            return new Requirement(expectedState.rotate(rotation), criterion,
                    replacements.stream().map(state -> state.rotate(rotation)).toList());
        }

        private boolean checkBlockState(BlockState actual) {
            if (criterion == Criterion.WALLS && expectedState.getBlock() instanceof FenceBlock) {
                return true;
            }
            if (criterion == Criterion.WINDOWS && expectedState.getBlock() instanceof IronBarsBlock) {
                return true;
            }
            if (criterion == Criterion.FUNCTIONAL_BLOCKS && expectedState.getBlock() instanceof FurnaceBlock) {
                return actual.getValue(BlockStateProperties.HORIZONTAL_FACING)
                        == expectedState.getValue(BlockStateProperties.HORIZONTAL_FACING);
            }
            if (criterion == Criterion.FUNCTIONAL_BLOCKS && expectedState.hasProperty(BlockStateProperties.BED_PART)) {
                return actual.getValue(BlockStateProperties.BED_PART) == expectedState.getValue(BlockStateProperties.BED_PART)
                        && actual.getValue(BlockStateProperties.HORIZONTAL_FACING)
                        == expectedState.getValue(BlockStateProperties.HORIZONTAL_FACING);
            }
            if (criterion == Criterion.STAIRWAYS && expectedState.getBlock() instanceof StairBlock) {
                return actual.getValue(BlockStateProperties.HORIZONTAL_FACING)
                        == expectedState.getValue(BlockStateProperties.HORIZONTAL_FACING)
                        && actual.getValue(BlockStateProperties.HALF) == expectedState.getValue(BlockStateProperties.HALF)
                        && actual.getValue(BlockStateProperties.STAIRS_SHAPE)
                        == expectedState.getValue(BlockStateProperties.STAIRS_SHAPE);
            }
            return actual.equals(expectedState);
        }
    }

    public record Door(BlockPos lowerPosition, Direction outward) {
        public Door {
            lowerPosition = lowerPosition.immutable();
            if (!outward.getAxis().isHorizontal()) {
                throw new IllegalArgumentException("Door direction must be horizontal");
            }
        }
    }

    public record Bounds(BlockPos min, BlockPos max) {
        public Bounds {
            min = min.immutable();
            max = max.immutable();
            if (min.getX() > max.getX() || min.getY() > max.getY() || min.getZ() > max.getZ()) {
                throw new IllegalArgumentException("Bounds must be ordered on all axes");
            }
        }

        public boolean contains(BlockPos position) {
            return position.getX() >= min.getX() && position.getX() <= max.getX()
                    && position.getY() >= min.getY() && position.getY() <= max.getY()
                    && position.getZ() >= min.getZ() && position.getZ() <= max.getZ();
        }

        public Iterable<BlockPos> positions() {
            return () -> BlockPos.betweenClosedStream(min, max).map(BlockPos::immutable).iterator();
        }
    }

    public record BlueprintCheck(Map<BlockPos, Status> statuses, int mismatchCount,
                                 Set<Criterion> violatedCriteria, int bonusBlockCount, int bonusEmeralds) {
        public BlueprintCheck {
            statuses = Collections.unmodifiableMap(new LinkedHashMap<>(statuses));
            violatedCriteria = Set.copyOf(violatedCriteria);
        }

        public boolean complete() {
            return mismatchCount == 0;
        }

        public int violatedCriteriaCount() {
            return violatedCriteria.size();
        }

        public int cakeCount() {
            return bonusBlockCount;
        }
    }

    public record Bonus(Block block, int exactCount, int emeralds) {
        public Bonus {
            Objects.requireNonNull(block, "block");
            if (block.defaultBlockState().isAir() || exactCount < 1 || emeralds < 1) {
                throw new IllegalArgumentException("Bonus needs a block type, positive count and positive payout");
            }
        }
    }

    private final Bounds bounds;
    private final Bounds interior;
    private final List<Bounds> interiorVolumes;
    private final Map<BlockPos, Requirement> requirements;
    private final List<Door> doors;
    private final Optional<Bonus> bonus;

    RoomBlueprint(Bounds bounds, List<Bounds> interiorVolumes, Map<BlockPos, Requirement> requirements,
                  List<Door> doors, Optional<Bonus> bonus) {
        this.bounds = bounds;
        this.interiorVolumes = List.copyOf(interiorVolumes);
        if (this.interiorVolumes.isEmpty() || requirements.isEmpty() || doors.isEmpty()) {
            throw new IllegalArgumentException("A room blueprint requires an interior, blocks and doors");
        }
        this.interior = encompassing(this.interiorVolumes);
        this.requirements = Collections.unmodifiableMap(new LinkedHashMap<>(requirements));
        this.doors = List.copyOf(doors);
        this.bonus = bonus;
    }

    public static RoomBlueprint of(Bounds bounds, List<Bounds> interiorVolumes, Map<BlockPos, Requirement> requirements,
                                   List<Door> doors, Optional<Bonus> bonus) {
        return new RoomBlueprint(bounds, interiorVolumes, requirements, doors, bonus);
    }

    private static Bounds encompassing(List<Bounds> volumes) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Bounds volume : volumes) {
            minX = Math.min(minX, volume.min().getX());
            minY = Math.min(minY, volume.min().getY());
            minZ = Math.min(minZ, volume.min().getZ());
            maxX = Math.max(maxX, volume.max().getX());
            maxY = Math.max(maxY, volume.max().getY());
            maxZ = Math.max(maxZ, volume.max().getZ());
        }
        return new Bounds(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
    }

    public Bounds bounds() {
        return bounds;
    }

    public Bounds interior() {
        return interior;
    }

    public List<Bounds> interiorVolumes() {
        return interiorVolumes;
    }

    public boolean containsInterior(BlockPos position) {
        return interiorVolumes.stream().anyMatch(volume -> volume.contains(position));
    }

    public Map<BlockPos, Requirement> requirements() {
        return requirements;
    }

    public List<Door> doors() {
        return doors;
    }

    public Optional<Bonus> bonus() {
        return bonus;
    }

    public CompoundTag encode() {
        return BlueprintTag.write(this);
    }

    public static RoomBlueprint decode(CompoundTag tag) {
        return BlueprintTag.read(tag);
    }

    public RoomBlueprint reorient(BlockPos oldAnchor, Direction oldOutward, BlockPos newAnchor, Direction newOutward) {
        if (!oldOutward.getAxis().isHorizontal() || !newOutward.getAxis().isHorizontal()) {
            throw new IllegalArgumentException("Blueprint directions must be horizontal");
        }
        Rotation rotation = switch (Math.floorMod(newOutward.get2DDataValue() - oldOutward.get2DDataValue(), 4)) {
            case 0 -> Rotation.NONE;
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            default -> Rotation.COUNTERCLOCKWISE_90;
        };
        Function<BlockPos, BlockPos> transform = position -> position.subtract(oldAnchor).rotate(rotation).offset(newAnchor);
        Map<BlockPos, Requirement> transformed = new LinkedHashMap<>();
        requirements.forEach((position, requirement) -> transformed.put(transform.apply(position), requirement.rotate(rotation)));
        List<Bounds> interiors = interiorVolumes.stream().map(volume -> transformBounds(volume, transform)).toList();
        List<Door> transformedDoors = doors.stream().map(door -> new Door(transform.apply(door.lowerPosition()),
                rotation.rotate(door.outward()))).toList();
        return new RoomBlueprint(transformBounds(bounds, transform), interiors, transformed, transformedDoors, bonus);
    }

    private static Bounds transformBounds(Bounds bounds, Function<BlockPos, BlockPos> transform) {
        BlockPos first = transform.apply(bounds.min());
        BlockPos second = transform.apply(bounds.max());
        return new Bounds(new BlockPos(Math.min(first.getX(), second.getX()), Math.min(first.getY(), second.getY()),
                 Math.min(first.getZ(), second.getZ())),
                new BlockPos(Math.max(first.getX(), second.getX()), Math.max(first.getY(), second.getY()),
                 Math.max(first.getZ(), second.getZ())));
    }

    public Status checkPosition(BlockGetter world, BlockPos position) {
        Requirement requirement = Objects.requireNonNull(requirements.get(position), "No requirement at this position");
        return requirement.check(position, world::getBlockState);
    }

    public BlueprintCheck check(BlockGetter world) {
        return check(world::getBlockState);
    }

    public BlueprintCheck check(Function<BlockPos, BlockState> blocks) {
        Map<BlockPos, Status> statuses = new LinkedHashMap<>();
        Set<Criterion> violatedCriteria = EnumSet.noneOf(Criterion.class);
        int mismatchCount = 0;

        for (Map.Entry<BlockPos, Requirement> entry : requirements.entrySet()) {
            BlockPos position = entry.getKey();
            Requirement requirement = entry.getValue();
            Status status = requirement.check(position, blocks);
            statuses.put(position, status);
            if (status != Status.CORRECT) {
                mismatchCount++;
                violatedCriteria.add(requirement.criterion());
            }
        }

        int matchingBonusBlocks = 0;
        int earnedBonus = 0;
        if (bonus.isPresent()) {
            Bonus condition = bonus.orElseThrow();
            for (Bounds volume : interiorVolumes) {
                for (BlockPos position : volume.positions()) {
                    if (blocks.apply(position).is(condition.block())) {
                        matchingBonusBlocks++;
                    }
                }
            }
            earnedBonus = matchingBonusBlocks == condition.exactCount() ? condition.emeralds() : 0;
        }
        return new BlueprintCheck(statuses, mismatchCount, violatedCriteria, matchingBonusBlocks, earnedBonus);
    }
}
