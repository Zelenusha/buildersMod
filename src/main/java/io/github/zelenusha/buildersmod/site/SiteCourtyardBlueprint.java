package io.github.zelenusha.buildersmod.site;

import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Bounds;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Criterion;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Door;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Requirement;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;

/** An open courtyard beside a ground-floor window of an existing room. */
public final class SiteCourtyardBlueprint {
    private static final int DEPTH = 5;
    private static final int MIN_WIDTH = 5;
    private static final int OPEN_HEIGHT = 3;

    public record Attachment(BlockPos firstAtFloor, Direction outward, int windowWidth) {
        public Attachment {
            firstAtFloor = firstAtFloor.immutable();
            if (!outward.getAxis().isHorizontal() || windowWidth < 1) {
                throw new IllegalArgumentException("A courtyard needs a horizontal window wall and a nonempty opening");
            }
        }
    }

    private SiteCourtyardBlueprint() {}

    /** Locates window openings on the bottom storey's exterior. The caller chooses which to reserve for courtyards. */
    public static List<Attachment> windows(RoomBlueprint room) {
        Map<Direction, SortedSet<Integer>> horizontalTiles = new EnumMap<>(Direction.class);
        int y = room.bounds().min().getY();
        int ceiling = room.interiorVolumes().get(0).max().getY();
        room.requirements().forEach((pos, requirement) -> {
            if (requirement.criterion() != Criterion.WINDOWS || pos.getY() <= y || pos.getY() > ceiling) {
                return;
            }
            outsideFace(room.bounds(), pos).ifPresent(direction ->
                    horizontalTiles.computeIfAbsent(direction, ignored -> new TreeSet<>())
                            .add(coordinateAlongWall(pos, direction)));
        });

        List<Attachment> windows = new ArrayList<>();
        horizontalTiles.forEach((direction, offsets) -> {
            int begin = offsets.first();
            int previous = begin;
            for (int offset : offsets) {
                if (offset > previous + 1) {
                    windows.add(attachment(room.bounds(), direction, begin, previous));
                    begin = offset;
                }
                previous = offset;
            }
            windows.add(attachment(room.bounds(), direction, begin, previous));
        });
        return List.copyOf(windows);
    }

    public static Optional<RoomBlueprint> createFitting(RoomBlueprint room, Predicate<RoomBlueprint> isAvailable) {
        return windows(room).stream().map(window -> create(room, window)).filter(isAvailable).findFirst();
    }

    public static RoomBlueprint create(RoomBlueprint room, BlockPos windowPosition, Direction outward) {
        Requirement requirement = room.requirements().get(windowPosition);
        if (requirement == null || requirement.criterion() != Criterion.WINDOWS
                || outsideFace(room.bounds(), windowPosition).orElse(null) != outward
                || windowPosition.getY() > room.interiorVolumes().get(0).max().getY()) {
            throw new IllegalArgumentException("The courtyard must face a bottom-storey exterior window");
        }
        int offset = coordinateAlongWall(windowPosition, outward);
        return windows(room).stream().filter(window -> window.outward() == outward)
                .filter(window -> {
                    int first = coordinateAlongWall(window.firstAtFloor(), outward);
                    return offset >= first && offset < first + window.windowWidth();
                })
                .findFirst().map(window -> create(room, window)).orElseThrow();
    }

    public static RoomBlueprint create(RoomBlueprint room, Attachment window) {
        if (!windows(room).contains(window)) {
            throw new IllegalArgumentException("Window attachment does not belong to this room");
        }
        int width = Math.max(MIN_WIDTH, window.windowWidth() + 2);
        Direction outward = window.outward();
        int margin = (width - window.windowWidth()) / 2;
        BlockPos nearest = offsetAlongWall(window.firstAtFloor().relative(outward), outward, -margin);
        BlockPos farthest = offsetAlongWall(nearest.relative(outward, DEPTH - 1), outward, width - 1);
        BlockPos gate = offsetAlongWall(nearest.relative(outward, DEPTH - 1), outward, width / 2).above();

        Requirement floor = new Requirement(Blocks.DIRT.defaultBlockState(), Criterion.FLOOR,
                List.of(Blocks.GRASS_BLOCK.defaultBlockState()));
        Requirement fence = new Requirement(Blocks.OAK_FENCE.defaultBlockState(), Criterion.WALLS);
        Requirement gateBlock = new Requirement(Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, outward),
                Criterion.DOORWAYS);
        Requirement air = new Requirement(Blocks.AIR.defaultBlockState(), Criterion.CLEARANCE);
        Map<BlockPos, Requirement> blocks = new LinkedHashMap<>();

        for (int depth = 0; depth < DEPTH; depth++) {
            for (int side = 0; side < width; side++) {
                BlockPos deck = offsetAlongWall(nearest.relative(outward, depth), outward, side);
                boolean edge = depth == 0 || depth == DEPTH - 1 || side == 0 || side == width - 1;
                blocks.put(deck, floor);
                BlockPos first = deck.above();
                blocks.put(first, first.equals(gate) ? gateBlock : edge ? fence : air);
                for (int height = 2; height <= OPEN_HEIGHT; height++) {
                    blocks.put(deck.above(height), air);
                }
            }
        }

        Bounds bounds = bounds(nearest, farthest.above(OPEN_HEIGHT));
        Bounds interior = bounds(offsetAlongWall(nearest.relative(outward).above(), outward, 1),
                offsetAlongWall(farthest.relative(outward.getOpposite()).above(OPEN_HEIGHT), outward, -1));
        return RoomBlueprint.of(bounds, List.of(interior), blocks, List.of(new Door(gate, outward)), Optional.empty());
    }

    private static Optional<Direction> outsideFace(Bounds room, BlockPos pos) {
        if (pos.getZ() == room.min().getZ() && pos.getX() > room.min().getX() && pos.getX() < room.max().getX()) {
            return Optional.of(Direction.NORTH);
        }
        if (pos.getZ() == room.max().getZ() && pos.getX() > room.min().getX() && pos.getX() < room.max().getX()) {
            return Optional.of(Direction.SOUTH);
        }
        if (pos.getX() == room.min().getX() && pos.getZ() > room.min().getZ() && pos.getZ() < room.max().getZ()) {
            return Optional.of(Direction.WEST);
        }
        if (pos.getX() == room.max().getX() && pos.getZ() > room.min().getZ() && pos.getZ() < room.max().getZ()) {
            return Optional.of(Direction.EAST);
        }
        return Optional.empty();
    }

    private static Attachment attachment(Bounds room, Direction outward, int first, int last) {
        return new Attachment(new BlockPos(
                outward.getAxis() == Direction.Axis.X ? fixedWallCoordinate(room, outward) : first,
                room.min().getY(), outward.getAxis() == Direction.Axis.Z ? fixedWallCoordinate(room, outward) : first),
                outward, last - first + 1);
    }

    private static int fixedWallCoordinate(Bounds room, Direction outward) {
        BlockPos edge = outward.getAxisDirection() == Direction.AxisDirection.NEGATIVE ? room.min() : room.max();
        return outward.getAxis() == Direction.Axis.X ? edge.getX() : edge.getZ();
    }

    private static int coordinateAlongWall(BlockPos pos, Direction outward) {
        return outward.getAxis() == Direction.Axis.X ? pos.getZ() : pos.getX();
    }

    private static BlockPos offsetAlongWall(BlockPos pos, Direction outward, int offset) {
        return outward.getAxis() == Direction.Axis.X ? pos.south(offset) : pos.east(offset);
    }

    private static Bounds bounds(BlockPos first, BlockPos second) {
        return new Bounds(new BlockPos(Math.min(first.getX(), second.getX()), first.getY(), Math.min(first.getZ(), second.getZ())),
                new BlockPos(Math.max(first.getX(), second.getX()), second.getY(), Math.max(first.getZ(), second.getZ())));
    }
}
