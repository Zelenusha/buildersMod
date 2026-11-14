package io.github.zelenusha.buildersmod.intro;

import io.github.zelenusha.buildersmod.BuildersMod;
import java.util.Map;
import java.util.Set;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.inventory.MerchantContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(BuildersMod.MOD_ID)
@PrefixGameTestTemplate(false)
public class TraderStockGameTests {
    @GameTest(template = "empty")
    public static void dailyMaterialOffersRetainExhaustedStockAcrossReload(GameTestHelper helper) {
        long day = 12;
        long seed = 257L;
        Set<ResourceKey<Biome>> possible = Set.of(Biomes.PLAINS, Biomes.DESERT, Biomes.BADLANDS,
                Biomes.MANGROVE_SWAMP, Biomes.CHERRY_GROVE);
        Set<ResourceKey<Biome>> nearby = Set.of(Biomes.PLAINS, Biomes.DESERT);
        Map<Item, Integer> prices = Map.of(Items.RED_SAND, 3, Items.TERRACOTTA, 3, Items.MUD, 2,
                Items.MANGROVE_LOG, 3, Items.CHERRY_LOG, 3);
        WanderingTrader trader = newTrader(helper);
        IntroTrades.updateTrader(trader, day, seed, () -> possible, () -> nearby);
        var offers = trader.getOffers();
        helper.assertTrue(offers.size() == 4 && offers.stream().map(offer -> offer.getResult().getItem()).distinct().count() == 4,
                "Exactly four distinct materials are stocked when five are eligible");
        for (MerchantOffer offer : offers) {
            Item material = offer.getResult().getItem();
            Integer rate = prices.get(material);
            helper.assertTrue(rate != null && offer.getCostA().is(Items.EMERALD) && offer.getCostA().getCount() == rate
                    && offer.getCostB().isEmpty() && offer.getResult().getCount() == 16 && offer.getMaxUses() == 8,
                    "Only remote Overworld materials are stocked with the configured price and quantity");
        }

        MerchantOffer consumed = offers.get(1);
        Item item = consumed.getResult().getItem();
        int price = prices.get(item);
        ItemStack emeralds = new ItemStack(Items.EMERALD, price * 9);
        MerchantContainer basket = new MerchantContainer(trader);
        basket.setSelectionHint(1);
        basket.setItem(0, emeralds);
        for (int i = 0; i < 8; i++) {
            helper.assertTrue(basket.getActiveOffer() == consumed && basket.getItem(2).is(item)
                    && consumed.take(emeralds, ItemStack.EMPTY),
                    "The first eight merchant purchases can be paid");
            consumed.increaseUses();
            basket.updateSellItem();
        }
        helper.assertTrue(consumed.isOutOfStock() && basket.getItem(2).isEmpty() && emeralds.getCount() == price,
                "The merchant shows no ninth result and leaves the unused emeralds untouched");

        CompoundTag originalStock = offers.createTag();
        WanderingTrader restored = reload(helper, trader);
        IntroTrades.updateTrader(restored, day, seed, TraderStockGameTests::unexpectedBiomeLookup,
                TraderStockGameTests::unexpectedBiomeLookup);
        helper.assertTrue(restored.getOffers().createTag().equals(originalStock) && restored.getOffers().get(1).isOutOfStock(),
                "Entity reload on the same day preserves the existing order and its exhausted position");

        IntroTrades.updateTrader(restored, day + 1, seed, () -> Set.of(Biomes.DESERT), Set::of);
        var restocked = restored.getOffers();
        helper.assertTrue(restocked.size() == 2 && restocked.stream().allMatch(offer -> !offer.isOutOfStock() && offer.getUses() == 0)
                && restocked.stream().map(offer -> offer.getResult().getItem()).collect(java.util.stream.Collectors.toSet())
                        .equals(Set.of(Items.SAND, Items.SANDSTONE)),
                "The next day rebuilds available stock using the current eligible source biomes");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void emptyMaterialDayStaysEmptyAcrossReloadAndShopsRemainSeparate(GameTestHelper helper) {
        long day = 4;
        Set<ResourceKey<Biome>> allLocal = Set.of(Biomes.PLAINS, Biomes.DESERT);
        WanderingTrader trader = newTrader(helper);
        IntroTrades.updateTrader(trader, day, 4, () -> allLocal, () -> allLocal);
        helper.assertTrue(trader.getOffers().isEmpty(), "The trader has no stock when all its sources are local");

        WanderingTrader restored = reload(helper, trader);
        IntroTrades.updateTrader(restored, day, 4, TraderStockGameTests::unexpectedBiomeLookup,
                TraderStockGameTests::unexpectedBiomeLookup);
        helper.assertTrue(restored.getOffers().isEmpty(), "The trader does not fall back to vanilla stock after an empty day is saved");

        IntroTrades.updateTrader(restored, day + 1, 4, () -> allLocal, () -> Set.of(Biomes.PLAINS));
        var builder = EntityType.VILLAGER.create(helper.getLevel());
        helper.assertTrue(builder != null, "Expected a builder villager");
        IntroTrades.equipBuilder(builder);
        Set<Item> materialItems = Set.of(Items.SAND, Items.SANDSTONE);
        helper.assertTrue(restored.getOffers().size() == 2 && restored.getOffers().stream()
                        .allMatch(offer -> materialItems.contains(offer.getResult().getItem()))
                && builder.getOffers().size() == 12 && builder.getOffers().stream()
                        .noneMatch(offer -> materialItems.contains(offer.getResult().getItem())),
                "Newly eligible materials stay in the trader's shop and the builder keeps its equipment assortment");
        helper.succeed();
    }

    private static WanderingTrader newTrader(GameTestHelper helper) {
        WanderingTrader trader = EntityType.WANDERING_TRADER.create(helper.getLevel());
        helper.assertTrue(trader != null, "Expected a wandering trader");
        return trader;
    }

    private static WanderingTrader reload(GameTestHelper helper, WanderingTrader original) {
        WanderingTrader restored = newTrader(helper);
        restored.load(original.saveWithoutId(new CompoundTag()));
        return restored;
    }

    private static Set<ResourceKey<Biome>> unexpectedBiomeLookup() {
        throw new AssertionError("Stock is preserved on the same day without recalculating the biome catalogue");
    }
}
