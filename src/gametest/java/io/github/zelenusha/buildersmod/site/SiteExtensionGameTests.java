package io.github.zelenusha.buildersmod.site;

import io.github.zelenusha.buildersmod.blueprint.RectangularRoomBlueprint;
import io.github.zelenusha.buildersmod.blueprint.RectangularRoomBlueprint.Fixture;
import io.github.zelenusha.buildersmod.blueprint.RectangularRoomBlueprint.Spec;
import io.github.zelenusha.buildersmod.blueprint.RectangularRoomBlueprint.Surface;
import io.github.zelenusha.buildersmod.blueprint.RectangularRoomBlueprint.Wall;
import io.github.zelenusha.buildersmod.blueprint.RectangularRoomBlueprint.Window;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Criterion;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("buildersmod")
@PrefixGameTestTemplate(false)
public class SiteExtensionGameTests {
    @GameTest(template = "empty")
    public static void courtyardFacesWindowAndPreservesTheRoom(GameTestHelper helper) {
        Surface stone = Surface.solid(Blocks.STONE);
        Spec spec = new Spec(7, 5, List.of(4), stone, stone, stone, Set.of(Wall.OPPOSITE),
                List.of(new Window(0, Wall.LEFT, 1, 1, 2, 2, Blocks.GLASS)),
                List.of(new Fixture(0, new BlockPos(2, 1, 2), Blocks.TORCH.defaultBlockState())), List.of(), Optional.empty());
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            RoomBlueprint room = RectangularRoomBlueprint.at(new BlockPos(0, 65, 0), facing, spec);
            List<SiteCourtyardBlueprint.Attachment> windows = SiteCourtyardBlueprint.windows(room);
            helper.assertTrue(windows.size() == 1 && windows.get(0).windowWidth() == 2,
                    "Four glass tiles form one two-block-wide opening");
            RoomBlueprint courtyard = RoomBlueprint.decode(SiteCourtyardBlueprint.create(room, windows.get(0)).encode());
            helper.assertTrue(courtyard.requirements().keySet().stream().noneMatch(room.bounds()::contains),
                    "Courtyard floor, fence and open top must sit outside the existing room");
            helper.assertTrue(courtyard.requirements().values().stream().filter(r -> r.criterion() == Criterion.FLOOR).count() == 25,
                    "A two-block opening uses a five-by-five courtyard");
            helper.assertTrue(courtyard.requirements().values().stream().filter(r -> r.criterion() == Criterion.WALLS).count() == 15,
                    "The single perimeter row uses fifteen fences and one gate");

            Map<BlockPos, BlockState> world = snapshot(courtyard);
            BlockPos center = courtyard.interior().min();
            world.put(center.below(), Blocks.GRASS_BLOCK.defaultBlockState());
            BlockPos fencePos = courtyard.bounds().min().above();
            world.put(fencePos, world.get(fencePos).setValue(FenceBlock.NORTH, true));
            BlockPos gatePos = courtyard.doors().get(0).lowerPosition();
            BlockState gate = world.get(gatePos).setValue(FenceGateBlock.OPEN, true);
            world.put(gatePos, gate);
            world.put(center.above(25), Blocks.STONE.defaultBlockState());
            helper.assertTrue(courtyard.check(world::get).complete(), "Grass, connected fences and an open gate satisfy the courtyard");

            world.put(gatePos, gate.setValue(FenceGateBlock.FACING, windows.get(0).outward().getClockWise()));
            world.put(center.above(), Blocks.STONE.defaultBlockState());
            helper.assertTrue(courtyard.check(world::get).violatedCriteria().equals(Set.of(Criterion.CLEARANCE, Criterion.DOORWAYS)),
                    "A crosswise gate and a covered courtyard fail separately");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void corridorKeepsEntryAndCreatesThreeClearExits(GameTestHelper helper) {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            ConstructionSite.SiteDoor entrance = new ConstructionSite.SiteDoor(new BlockPos(3, 90, -2), facing);
            RoomBlueprint corridor = SiteCorridorBlueprint.create(entrance, 7);
            helper.assertTrue(!corridor.bounds().contains(entrance.lower()), "The existing entrance must remain intact");
            RoomBlueprint.Door joined = corridor.doors().get(0);
            helper.assertTrue(joined.lowerPosition().equals(entrance.lower().relative(facing))
                    && joined.outward() == facing.getOpposite(), "The new entry must be joined to the existing door");
            helper.assertTrue(corridor.doors().size() == 4, "The corridor provides three outbound doors");
            for (RoomBlueprint.Door door : corridor.doors()) {
                BlockPos inside = door.lowerPosition().relative(door.outward().getOpposite());
                helper.assertTrue(corridor.requirements().get(inside).clearance()
                        && corridor.requirements().get(inside.above()).clearance(), "Each door must connect to a clear passage");
            }
            Map<BlockPos, BlockState> world = snapshot(corridor);
            helper.assertTrue(corridor.check(world::get).complete(), "The occupied blueprint states must pass");
            world.put(joined.lowerPosition().relative(facing), Blocks.CHEST.defaultBlockState());
            helper.assertTrue(corridor.check(world::get).violatedCriteria().equals(Set.of(Criterion.CLEARANCE)),
                    "An obstructed corridor cannot pass");
        }
        helper.succeed();
    }

    private static Map<BlockPos, BlockState> snapshot(RoomBlueprint blueprint) {
        Map<BlockPos, BlockState> world = new HashMap<>();
        blueprint.requirements().forEach((pos, requirement) -> world.put(pos, requirement.expectedState()));
        return world;
    }
}
