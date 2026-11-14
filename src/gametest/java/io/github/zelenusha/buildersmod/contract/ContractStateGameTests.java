package io.github.zelenusha.buildersmod.contract;

import io.github.zelenusha.buildersmod.blueprint.SampleRoomBlueprint;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("buildersmod")
@PrefixGameTestTemplate(false)
public final class ContractStateGameTests {
    @GameTest(template = "empty")
    public static void stairRepairsAndOrdinaryExtensionsHaveSeparateEligibility(GameTestHelper helper) {
        long day = ContractBalance.TICKS_PER_DAY;
        BlockPos anchor = new BlockPos(0, 65, -4);
        var order = new ContractData.Accepted(anchor, Direction.NORTH, ContractBalance.Difficulty.ONE,
                3 * day, false, SampleRoomBlueprint.at(anchor, Direction.NORTH), 10, ContractData.Kind.STAIRS,
                -1, 1, Map.of());
        var ordinaryExtension = order.withExtraDay(2 * day);
        helper.assertTrue(ordinaryExtension.deadline() == 4 * day, "An early extension adds to the previous deadline");
        helper.assertTrue(!ContractData.StairInspection.WAITING.canBuyDay(ordinaryExtension.extraDay()),
                "A second ordinary day must be declined");
        helper.assertTrue(!ContractData.StairInspection.READY_AT_DEADLINE.canBuyDay(false),
                "A completed staircase only waits for inspection");
        helper.assertTrue(ContractData.StairInspection.NEEDS_REPAIR_DAY.canBuyDay(ordinaryExtension.extraDay()),
                "A newly damaged staircase permits a repair day");
        var repair = ordinaryExtension.withExtraDay(6 * day);
        helper.assertTrue(repair.deadline() == 7 * day && repair.extraDay() && repair.lateCount() == 1,
                "Repair day starts at purchase and retains original order history");
        helper.succeed();
    }
}
