package io.github.zelenusha.buildersmod.site;

import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Bounds;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Criterion;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Door;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Requirement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** A compact extension connecting an existing door to three new room attachment points. */
public final class SiteCorridorBlueprint {
    private static final int WIDTH = 5;
    private static final int MIN_LENGTH = 5;
    private static final int MAX_LENGTH = 15;
    private static final int CLEAR_HEIGHT = 4;

    private SiteCorridorBlueprint() {}

    public static Optional<RoomBlueprint> createFitting(List<ConstructionSite.SiteDoor> entrances,
                                                       Predicate<RoomBlueprint> fitsAndAddsRoom) {
        for (ConstructionSite.SiteDoor entrance : entrances) {
            for (int length = MIN_LENGTH; length <= MAX_LENGTH; length += 2) {
                RoomBlueprint blueprint = create(entrance, length);
                if (fitsAndAddsRoom.test(blueprint)) {
                    return Optional.of(blueprint);
                }
            }
        }
        return Optional.empty();
    }

    /** Length includes the panels containing the doors. The first door in the result is the entry. */
    public static RoomBlueprint create(ConstructionSite.SiteDoor entrance, int length) {
        if (length < MIN_LENGTH || length > MAX_LENGTH || !entrance.outward().getAxis().isHorizontal()) {
            throw new IllegalArgumentException("The corridor needs a horizontal entrance and a length of 5–15 blocks");
        }
        Direction forward = entrance.outward();
        Direction cross = forward.getClockWise();
        int half = WIDTH / 2;
        BlockPos from = entrance.lower().relative(forward).below();
        BlockPos end = from.relative(forward, length - 1);

        List<Door> doors = List.of(
                new Door(from.above(), forward.getOpposite()),
                new Door(end.above(), forward),
                new Door(end.relative(forward.getOpposite()).relative(cross, half).above(), cross),
                new Door(end.relative(forward.getOpposite()).relative(cross.getOpposite(), half).above(), cross.getOpposite()));

        Requirement floor = new Requirement(Blocks.STONE.defaultBlockState(), Criterion.FLOOR);
        Requirement wall = new Requirement(Blocks.STONE_BRICKS.defaultBlockState(), Criterion.WALLS);
        Requirement ceiling = new Requirement(Blocks.STONE_BRICKS.defaultBlockState(), Criterion.CEILING);
        Requirement air = new Requirement(Blocks.AIR.defaultBlockState(), Criterion.CLEARANCE);
        Map<BlockPos, Requirement> requirements = new LinkedHashMap<>();
        for (int along = 0; along < length; along++) {
            for (int aside = -half; aside <= half; aside++) {
                BlockPos deck = from.relative(forward, along).relative(cross, aside);
                requirements.put(deck, floor);
                requirements.put(deck.above(CLEAR_HEIGHT + 1), ceiling);
                boolean outer = along == 0 || along == length - 1 || Math.abs(aside) == half;
                for (int height = 1; height <= CLEAR_HEIGHT; height++) {
                    requirements.put(deck.above(height), outer ? wall : air);
                }
            }
        }

        for (Door door : doors) {
            BlockState state = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, door.outward());
            requirements.put(door.lowerPosition(), new Requirement(state, Criterion.DOORWAYS));
            requirements.put(door.lowerPosition().above(),
                    new Requirement(state.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Criterion.DOORWAYS));
        }
        // Light outside the travel headroom so every exit can be reached along the clear floor.
        BlockPos light = from.relative(forward).above(CLEAR_HEIGHT);
        requirements.put(light, new Requirement(Blocks.WALL_TORCH.defaultBlockState()
                .setValue(WallTorchBlock.FACING, forward), Criterion.LIGHTING));

        Bounds bounds = enclosing(from.relative(cross.getOpposite(), half), end.relative(cross, half).above(CLEAR_HEIGHT + 1));
        Bounds interior = enclosing(from.relative(forward).relative(cross.getOpposite(), half - 1).above(),
                end.relative(forward.getOpposite()).relative(cross, half - 1).above(CLEAR_HEIGHT));
        return RoomBlueprint.of(bounds, List.of(interior), requirements, doors, Optional.empty());
    }

    private static Bounds enclosing(BlockPos first, BlockPos second) {
        return new Bounds(new BlockPos(Math.min(first.getX(), second.getX()), first.getY(), Math.min(first.getZ(), second.getZ())),
                new BlockPos(Math.max(first.getX(), second.getX()), second.getY(), Math.max(first.getZ(), second.getZ())));
    }
}
