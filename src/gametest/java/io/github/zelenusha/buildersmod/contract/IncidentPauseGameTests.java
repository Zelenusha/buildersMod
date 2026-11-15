package io.github.zelenusha.buildersmod.contract;

import com.mojang.authlib.GameProfile;
import io.github.zelenusha.buildersmod.BuildersMod;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint;
import io.github.zelenusha.buildersmod.blueprint.SampleRoomBlueprint;
import io.github.zelenusha.buildersmod.contract.ContractBalance.Difficulty;
import io.github.zelenusha.buildersmod.contract.ContractData.Kind;
import io.github.zelenusha.buildersmod.maintenance.IncidentState;
import io.github.zelenusha.buildersmod.site.ConstructionSite;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(BuildersMod.MOD_ID)
@PrefixGameTestTemplate(false)
public class IncidentPauseGameTests {
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void floodPausesEligibleConstructionAcrossReloadAndLeavesStairsRunning(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        ServerLevel site = server.getLevel(ConstructionSite.SITE_LEVEL);
        if (site == null) {
            helper.fail("Missing construction dimension");
            return;
        }
        FakePlayer player = new FakePlayer(server.overworld(), new GameProfile(UUID.randomUUID(), "incident-office"));
        BlockPos office = helper.absolutePos(BlockPos.ZERO);
        player.moveTo(office.getX() + 0.5, office.getY(), office.getZ() + 0.5, 0, 0);
        BlockPos anchor = new BlockPos(392, ConstructionSite.START_FLOOR_Y + 1, 388);
        var completed = accepted(anchor, Kind.ROOM, 0, 0);
        var second = accepted(anchor.east(24), Kind.ROOM, 0, 1);
        RoomBlueprint blueprint = completed.blueprint();
        RoomBlueprint other = second.blueprint();
        ContractEvents.withLoadedAreas(site, List.of(blueprint.bounds(), other.bounds()), () -> {
            Map<BlockPos, BlockState> original = new HashMap<>();
            try {
                fixture(site, blueprint, original);
                fixture(site, other, original);
                for (Kind kind : List.of(Kind.ROOM, Kind.COURTYARD, Kind.CORRIDOR, Kind.STAIRS)) {
                    verifyPause(helper, site, player, completed, second, kind);
                }
            } finally {
                original.forEach((position, state) -> set(site, position, state));
            }
            return null;
        });
        helper.succeed();
    }

    private static void verifyPause(GameTestHelper helper, ServerLevel site, FakePlayer player,
                                    ContractData.Accepted completed, ContractData.Accepted second, Kind kind) {
        ContractData data = new ContractData();
        UUID playerId = player.getUUID();
        var account = data.account(playerId);
        account.rooms.add(completed);
        account.rooms.add(second);
        long start = data.time(player.server);
        long remaining = 30_000;
        long elapsed = 24_000;
        BlockPos construction = completed.anchor().east(48);
        account.active = accepted(construction, kind, start + remaining, kind == Kind.ROOM ? 0 : -2);
        var flood = new IncidentWork.Offer(UUID.randomUUID(), completed.blueprint().bounds().min(),
                IncidentState.Kind.FLOOD, Difficulty.TWO, 3);
        var competing = new IncidentWork.Offer(UUID.randomUUID(), second.blueprint().bounds().min(),
                IncidentState.Kind.FLOOD, Difficulty.TWO, 4);
        account.incidentOffers[0] = flood;
        account.incidentOffers[1] = competing;
        boolean paused = kind != Kind.STAIRS;

        helper.assertTrue(IncidentWork.accept(player, data, account, flood), "A manual flood starts from the office for " + kind);
        helper.assertTrue(account.pausedAt == (paused ? start : -1), "Correct initial pause status for " + kind);
        IncidentState active = account.incidents.get(0);
        helper.assertTrue(active.id().equals(flood.id()) && !active.automatic()
                        && active.initialBlocks().stream().allMatch(pos -> site.getFluidState(pos).is(FluidTags.WATER)
                        && site.getFluidState(pos).isSource()), "Acceptance places actual flood sources");
        helper.assertTrue(!IncidentWork.accept(player, data, account, competing) && account.incidents.size() == 1
                        && account.pausedAt == (paused ? start : -1), "A second manual flood changes neither active work nor timing");

        data.advanceSleep(elapsed);
        data = ContractData.load(data.save(new CompoundTag()));
        account = data.account(playerId);
        long now = data.time(player.server);
        helper.assertTrue(now == start + elapsed && account.pausedAt == (paused ? start : -1)
                        && account.active.kind() == kind && account.active.deadline() == start + remaining,
                "Schedule, remaining time and pause survive NBT for " + kind);
        active = account.incidents.get(0);
        helper.assertTrue(active.id().equals(flood.id()) && IncidentWork.submit(site, account, active).isEmpty(),
                "The persisted flood cannot be submitted with its source still present");
        active.initialBlocks().forEach(pos -> set(site, pos, Blocks.AIR.defaultBlockState()));
        helper.assertTrue(IncidentWork.submit(site, account, active).orElseThrow() == Difficulty.TWO.emeralds(),
                "The drained flood qualifies for its wage");
        IncidentWork.finish(player, data, account, active);
        helper.assertTrue(account.pausedAt == -1 && account.active.deadline() - now == (paused ? remaining : remaining - elapsed),
                "Submission resumes the original remaining time only when construction was paused for " + kind);
        helper.assertTrue(account.incidents.isEmpty() && account.incidentOffers[0] == null
                        && competing.equals(account.incidentOffers[1]), "Only the completed incident slot is released");
    }

    private static ContractData.Accepted accepted(BlockPos anchor, Kind kind, long deadline, int slot) {
        Direction outward = Direction.NORTH;
        return new ContractData.Accepted(anchor, outward, Difficulty.TWO, deadline, false,
                SampleRoomBlueprint.at(anchor, outward), 10, kind, slot, 0, Map.of());
    }

    private static void fixture(ServerLevel site, RoomBlueprint blueprint, Map<BlockPos, BlockState> original) {
        for (BlockPos position : blueprint.bounds().positions()) {
            original.put(position, site.getBlockState(position));
            var requirement = blueprint.requirements().get(position);
            set(site, position, requirement == null ? Blocks.AIR.defaultBlockState() : requirement.expectedState());
        }
    }

    private static void set(ServerLevel level, BlockPos position, BlockState state) {
        level.setBlock(position, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }
}
