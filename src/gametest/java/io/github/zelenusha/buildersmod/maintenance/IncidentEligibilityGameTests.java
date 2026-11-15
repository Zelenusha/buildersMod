package io.github.zelenusha.buildersmod.maintenance;

import io.github.zelenusha.buildersmod.BuildersMod;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Bounds;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Criterion;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Door;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Requirement;
import io.github.zelenusha.buildersmod.maintenance.IncidentState.Kind;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(BuildersMod.MOD_ID)
@PrefixGameTestTemplate(false)
public class IncidentEligibilityGameTests {
    @GameTest(template = "empty")
    public static void excludesWorkWithoutSafeTargets(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos wet = helper.absolutePos(new BlockPos(0, 11, 0));
        BlockPos empty = wet.east();
        BlockPos wall = empty.east();
        Bounds interior = new Bounds(wet, empty);
        Bounds frame = new Bounds(wet.below(), wall.above());
        RoomBlueprint room = RoomBlueprint.of(frame, List.of(interior),
                Map.of(wall, new Requirement(Blocks.STONE.defaultBlockState(), Criterion.WALLS)),
                List.of(new Door(wet, Direction.WEST)), Optional.empty());

        for (BlockPos position : frame.positions()) {
            level.setBlock(position, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        level.setBlock(wall, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(wet, Blocks.WATER.defaultBlockState(), Block.UPDATE_CLIENTS);
        helper.assertTrue(IncidentEligibility.kinds(level, room, false).equals(Set.of(Kind.CLEANUP)),
                "A wet room with a cleanable wall has no supported ignition, flood site or standing spot");
        helper.assertTrue(IncidentEligibility.kinds(level, room, true).equals(Set.of(Kind.CLEANUP, Kind.EVICTION)),
                "Known surviving targets permit eviction without an available spawn spot");

        level.setBlock(wet, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        helper.assertTrue(IncidentEligibility.kinds(level, room, false).equals(Set.of(Kind.CLEANUP, Kind.FLOOD)),
                "A dry interior leaves space for a flood source");
        helper.succeed();
    }
}
