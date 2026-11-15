package io.github.zelenusha.buildersmod.contract;

import io.github.zelenusha.buildersmod.blueprint.SampleRoomBlueprint;
import io.github.zelenusha.buildersmod.blueprint.RectangularRoomBlueprint;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint;
import io.github.zelenusha.buildersmod.site.ConstructionSite;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biomes;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("buildersmod")
@PrefixGameTestTemplate(false)
public final class ContractStateGameTests {
    @GameTest(template = "empty")
    public static void restrictedMaterialsNeedALocalSourceOrAStockedIngredient(GameTestHelper helper) {
        helper.assertTrue(!OfferFactory.availableLocally(Blocks.CHERRY_PLANKS, Set.of(), item -> false),
                "A distant wood with no trade must be rejected");
        helper.assertTrue(OfferFactory.availableLocally(Blocks.CHERRY_PLANKS, Set.of(Biomes.CHERRY_GROVE), item -> false),
                "Local cherry logs allow crafting cherry planks");
        helper.assertTrue(OfferFactory.availableLocally(Blocks.CHERRY_PLANKS, Set.of(), item -> item == Items.CHERRY_LOG),
                "A stocked log also allows crafting planks");
        helper.assertTrue(!OfferFactory.availableLocally(Blocks.BLUE_STAINED_GLASS_PANE, Set.of(Biomes.FOREST), item -> false),
                "Glass needs sand or a matching trade");
        helper.assertTrue(OfferFactory.availableLocally(Blocks.BLUE_STAINED_GLASS_PANE, Set.of(), item -> item == Items.SAND),
                "Stocked sand makes glazing available through its recipe");
        helper.assertTrue(!OfferFactory.availableLocally(Blocks.BEDROCK, Set.of(), item -> true),
                "A trade source does not override the survival catalogue");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void preservesTheReservedCourtyardUntilTheRoomIsAccepted(GameTestHelper helper) {
        var site = helper.getLevel().getServer().getLevel(ConstructionSite.SITE_LEVEL);
        var data = new ContractData();
        UUID owner = UUID.randomUUID();
        var account = data.account(owner);
        var stone = RectangularRoomBlueprint.Surface.solid(Blocks.STONE);
        var spec = new RectangularRoomBlueprint.Spec(7, 5, List.of(4), stone, stone, stone,
                Set.of(RectangularRoomBlueprint.Wall.OPPOSITE),
                List.of(new RectangularRoomBlueprint.Window(0, RectangularRoomBlueprint.Wall.LEFT, 1, 1, 2, 2, Blocks.GLASS)),
                List.of(new RectangularRoomBlueprint.Fixture(0, new BlockPos(2, 1, 2), Blocks.TORCH.defaultBlockState())),
                List.of(), Optional.empty());
        BlockPos anchor = new BlockPos(201, 65, 201);
        var blueprint = RectangularRoomBlueprint.at(anchor, Direction.NORTH, spec);
        var window = blueprint.windows().get(0);
        var street = new ContractData.Accepted(anchor, Direction.NORTH, ContractBalance.Difficulty.ONE, 0, false,
                blueprint.withWindows(List.of(window.withDestination(RoomBlueprint.WindowDestination.STREET))),
                6, ContractData.Kind.ROOM, 1, 0, Map.of());
        helper.assertTrue(OfferFactory.courtyard(site, account, street) == null, "A street window does not reserve a courtyard");
        var room = new ContractData.Accepted(anchor, Direction.NORTH, ContractBalance.Difficulty.ONE, 0, false,
                blueprint.withWindows(List.of(window.withDestination(RoomBlueprint.WindowDestination.COURTYARD))),
                6, ContractData.Kind.ROOM, 1, 0, Map.of());
        var yard = OfferFactory.courtyard(site, account, room);
        helper.assertTrue(yard != null && yard.slot() == -2 && yard.kind() == ContractData.Kind.COURTYARD,
                "A fitting window should reserve a separate courtyard");
        account.active = room;
        account.pendingCourtyard = yard;
        var reloaded = ContractData.load(data.save(new CompoundTag())).account(owner);
        helper.assertTrue(reloaded.courtyards.isEmpty() && reloaded.pendingCourtyard != null,
                "The reservation stays hidden from the offer list while the parent room is active");
        helper.assertTrue(reloaded.pendingCourtyard.blueprint().bounds().equals(yard.blueprint().bounds()),
                "The courtyard stays attached to the same window after reload");
        helper.succeed();
    }

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
