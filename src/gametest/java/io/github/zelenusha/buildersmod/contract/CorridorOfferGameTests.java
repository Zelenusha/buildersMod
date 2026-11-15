package io.github.zelenusha.buildersmod.contract;

import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint.Bounds;
import io.github.zelenusha.buildersmod.blueprint.SampleRoomBlueprint;
import io.github.zelenusha.buildersmod.site.ConstructionSite;
import io.github.zelenusha.buildersmod.site.ConstructionSite.SiteDoor;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("buildersmod")
@PrefixGameTestTemplate(false)
public final class CorridorOfferGameTests {
    private static final int FIXTURE_UPDATE = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void firstFloorOffersCompactCorridorThatUnblocksTheOriginalRoom(GameTestHelper helper) {
        ServerLevel site = helper.getLevel().getServer().getLevel(ConstructionSite.SITE_LEVEL);
        helper.assertTrue(site != null, "A construction site is required");
        int floor = ConstructionSite.START_FLOOR_Y;
        Bounds area = new Bounds(new BlockPos(-16, floor, -17), new BlockPos(16, floor + 5, 5));
        ContractEvents.withLoadedArea(site, area, () -> {
            verifyCorridorOffer(helper, site);
            return null;
        });
        helper.succeed();
    }

    private static void verifyCorridorOffer(GameTestHelper helper, ServerLevel site) {
        SiteDoor north = ConstructionSite.STARTER_DOORS.get(0);
        RoomBlueprint proposal = SampleRoomBlueprint.at(north.lower(), north.outward());
        var account = new ContractData.Account();
        var original = new ContractData.Accepted(north.lower(), north.outward(), ContractBalance.Difficulty.ONE, 0,
                false, proposal, 6, ContractData.Kind.SAMPLE, 1, 0, Map.of());
        account.offers[original.slot()] = original;
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        try {
            for (SiteDoor door : ConstructionSite.STARTER_DOORS.subList(1, 3)) {
                setBlock(site, door.lower(), Blocks.STONE_BRICKS.defaultBlockState(), before);
                setBlock(site, door.lower().above(), Blocks.STONE_BRICKS.defaultBlockState(), before);
            }
            // Blocks with inventories cannot be cleared as part of the building order.
            for (int x : new int[] {-3, 3}) {
                setBlock(site, new BlockPos(x, north.lower().getY(), north.lower().getZ() - 2),
                        Blocks.CHEST.defaultBlockState(), before);
            }
            helper.assertTrue(OfferFactory.bind(site, account, original) == null,
                    "The sample room's wings are obstructed and the two alternative starter doors are closed");
            var extension = OfferFactory.extension(site, account);
            helper.assertTrue(extension != null && extension.kind() == ContractData.Kind.CORRIDOR && extension.slot() == -1,
                    "The first floor should offer the usable corridor on its separate card");
            RoomBlueprint corridor = extension.blueprint();
            helper.assertTrue(extension.anchor().equals(north.lower()) && corridor.bounds().max().getX() - corridor.bounds().min().getX() == 4
                    && corridor.bounds().max().getZ() - corridor.bounds().min().getZ() == 4,
                    "The minimum-size corridor fits between the obstacles");
            helper.assertTrue(account.highestFloor == 1 && account.offers[original.slot()] == original,
                    "Preparing the extension changes neither the floor count nor the existing offer");

            corridor.requirements().forEach((pos, requirement) -> setBlock(site, pos, requirement.expectedState(), before));
            helper.assertTrue(corridor.check(site).complete(), "The corridor requirement states form a finished corridor");
            account.rooms.add(extension);
            var available = OfferFactory.bind(site, account, original);
            RoomBlueprint.Door farExit = corridor.doors().get(1);
            helper.assertTrue(available != null && available.anchor().equals(farExit.lowerPosition()) && available.outward() == Direction.NORTH,
                    "The existing sample offer can now attach through the completed corridor's far exit");
            RoomBlueprint expected = proposal.reorient(original.anchor(), original.outward(), farExit.lowerPosition(), farExit.outward());
            helper.assertTrue(account.offers[original.slot()] == original && available.slot() == original.slot()
                    && available.wage() == original.wage() && available.difficulty() == original.difficulty()
                    && available.blueprint().encode().equals(expected.encode()),
                    "Rebinding through the corridor keeps the existing offer and its construction conditions");
        } finally {
            before.forEach((pos, state) -> site.setBlock(pos, state, FIXTURE_UPDATE));
        }
    }

    private static void setBlock(ServerLevel site, BlockPos pos, BlockState state, Map<BlockPos, BlockState> before) {
        before.putIfAbsent(pos, site.getBlockState(pos));
        site.setBlock(pos, state, FIXTURE_UPDATE);
    }
}
