package io.github.zelenusha.buildersmod.intro;

import io.github.zelenusha.buildersmod.BuildersMod;
import io.github.zelenusha.buildersmod.maintenance.WorkItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

@GameTestHolder(BuildersMod.MOD_ID)
@PrefixGameTestTemplate(false)
public class IntroGameTests {
    @GameTest(template = "empty")
    public static void buildsDugoutAndOfficeWithUsableDoorAndFurnishings(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos dugout = helper.absolutePos(new BlockPos(0, 80, 0));
        BlockPos office = dugout.east(7).above();
        IntroStructures.build(level, new IntroStructures.Sites(dugout, office));

        checkDoor(helper, dugout.offset(2, 1, 0));
        helper.assertTrue(level.getBlockState(dugout.offset(4, 0, 4)).is(Blocks.DIRT)
                && level.getBlockState(dugout.offset(4, 3, 4)).is(Blocks.GRASS_BLOCK), "Expected dirt floor and grass roof");
        helper.assertTrue(level.isEmptyBlock(dugout.offset(2, 1, 2)) && level.isEmptyBlock(dugout.offset(2, 2, 2)),
                "Dugout has two blocks of headroom");
        var stair = level.getBlockState(dugout.offset(2, 1, -1));
        helper.assertTrue(stair.is(Blocks.COBBLESTONE_STAIRS) && stair.getValue(StairBlock.FACING) == Direction.NORTH,
                "Entry has a stair leading down into the dugout");
        var foot = level.getBlockState(dugout.offset(1, 1, 2));
        var head = level.getBlockState(dugout.offset(1, 1, 3));
        helper.assertTrue(foot.is(Blocks.WHITE_BED) && foot.getValue(BedBlock.PART) == BedPart.FOOT
                && head.is(Blocks.WHITE_BED) && head.getValue(BedBlock.PART) == BedPart.HEAD,
                "Dugout has both parts of the white bed");
        helper.assertTrue(level.getBlockState(dugout.offset(3, 1, 3)).is(Blocks.TORCH), "Dugout has a torch");

        checkDoor(helper, office.offset(6, 1, 0));
        helper.assertTrue(level.getBlockState(office.offset(12, 0, 10)).is(Blocks.COBBLESTONE)
                && level.getBlockState(office.offset(12, 5, 10)).is(Blocks.OAK_PLANKS), "Office has the specified far floor and roof corners");
        helper.assertTrue(level.getBlockState(office.offset(12, 2, 3)).is(Blocks.GLASS), "Office has glass windows");
        BlockPos landing = IntroStructures.officeArrival(office);
        helper.assertTrue(level.getBlockState(landing.below()).isFaceSturdy(level, landing.below(), Direction.UP)
                && level.isEmptyBlock(landing) && level.isEmptyBlock(landing.above()), "Return landing has floor and headroom");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sellsStarterToolsAndSpecialEquipmentForEmeralds(GameTestHelper helper) {
        var builder = EntityType.VILLAGER.create(helper.getLevel());
        helper.assertTrue(builder != null, "Expected a builder villager");
        IntroTrades.equipBuilder(builder);
        MerchantOffers offers = builder.getOffers();

        helper.assertTrue(offers.size() == 12, "Builder has five stone tools, five iron tools and two work items");
        for (Item stone : List.of(Items.STONE_PICKAXE, Items.STONE_AXE, Items.STONE_SHOVEL, Items.STONE_HOE, Items.STONE_SWORD)) {
            checkOffer(helper, offers, stone, 2);
        }
        for (Item iron : List.of(Items.IRON_PICKAXE, Items.IRON_AXE, Items.IRON_SHOVEL, Items.IRON_HOE, Items.IRON_SWORD)) {
            checkOffer(helper, offers, iron, 4);
        }
        checkOffer(helper, offers, WorkItems.WET_RAG.get(), 2);
        checkOffer(helper, offers, WorkItems.FIRE_EXTINGUISHER.get(), 4);
        helper.succeed();
    }

    private static void checkDoor(GameTestHelper helper, BlockPos pos) {
        var lower = helper.getLevel().getBlockState(pos);
        var upper = helper.getLevel().getBlockState(pos.above());
        helper.assertTrue(lower.is(Blocks.OAK_DOOR) && lower.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                && upper.is(Blocks.OAK_DOOR) && upper.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER,
                "Expected a two-block oak door at " + pos.toShortString());
    }

    private static void checkOffer(GameTestHelper helper, MerchantOffers offers, Item item, int price) {
        var matches = offers.stream().filter(offer -> offer.getResult().is(item)).toList();
        helper.assertTrue(matches.size() == 1, "Expected one offer for " + item);
        var offer = matches.get(0);
        helper.assertTrue(offer.getResult().getCount() == 1 && offer.getCostB().isEmpty(), "Expected one item with no second cost");
        ItemStack shortPayment = new ItemStack(Items.EMERALD, price - 1);
        helper.assertTrue(!offer.take(shortPayment, ItemStack.EMPTY) && shortPayment.getCount() == price - 1,
                "Insufficient payment leaves emeralds untouched");
        ItemStack payment = new ItemStack(Items.EMERALD, price + 1);
        helper.assertTrue(offer.take(payment, ItemStack.EMPTY) && payment.getCount() == 1,
                "Expected to pay exactly " + price + " emeralds for " + item);
    }
}
