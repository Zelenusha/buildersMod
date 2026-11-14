package io.github.zelenusha.buildersmod.intro;

import io.github.zelenusha.buildersmod.maintenance.WorkItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

final class IntroTrades {
    private static final String STOCK_DAY = "buildersmod_stock_day";
    private static final String EMPTY_STOCK = "buildersmod_empty_stock";
    private static final int LOCAL_RADIUS = 256;
    private static final List<Item> STONE_TOOLS = List.of(Items.STONE_PICKAXE, Items.STONE_AXE, Items.STONE_SHOVEL,
            Items.STONE_HOE, Items.STONE_SWORD);
    private static final List<Item> IRON_TOOLS = List.of(Items.IRON_PICKAXE, Items.IRON_AXE, Items.IRON_SHOVEL,
            Items.IRON_HOE, Items.IRON_SWORD);
    private static final List<ResourceKey<Biome>> BADLANDS = List.of(Biomes.BADLANDS, Biomes.ERODED_BADLANDS, Biomes.WOODED_BADLANDS);
    private static final List<ResourceKey<Biome>> SAVANNA = List.of(Biomes.SAVANNA, Biomes.SAVANNA_PLATEAU, Biomes.WINDSWEPT_SAVANNA);
    private static final List<ResourceKey<Biome>> JUNGLES = List.of(Biomes.JUNGLE, Biomes.SPARSE_JUNGLE, Biomes.BAMBOO_JUNGLE);
    private static final List<Stock> MATERIALS = List.of(
            new Stock(Biomes.DESERT, Items.SAND, 2),
            new Stock(Biomes.DESERT, Items.SANDSTONE, 2),
            new Stock(BADLANDS, Items.RED_SAND, 3),
            new Stock(BADLANDS, Items.TERRACOTTA, 3),
            new Stock(SAVANNA, Items.ACACIA_LOG, 3),
            new Stock(Biomes.MANGROVE_SWAMP, Items.MUD, 2),
            new Stock(Biomes.MANGROVE_SWAMP, Items.MANGROVE_LOG, 3),
            new Stock(Biomes.CHERRY_GROVE, Items.CHERRY_LOG, 3),
            new Stock(List.of(Biomes.JUNGLE, Biomes.BAMBOO_JUNGLE), Items.BAMBOO, 2),
            new Stock(JUNGLES, Items.JUNGLE_LOG, 3)
    );

    private IntroTrades() {
    }

    static void equipBuilder(AbstractVillager builder) {
        MerchantOffers offers = builder.getOffers();
        offers.clear();
        STONE_TOOLS.forEach(tool -> offers.add(offer(tool, 1, 2, Integer.MAX_VALUE)));
        IRON_TOOLS.forEach(tool -> offers.add(offer(tool, 1, 4, Integer.MAX_VALUE)));
        offers.add(offer(WorkItems.WET_RAG.get(), 1, 2, Integer.MAX_VALUE));
        offers.add(offer(WorkItems.FIRE_EXTINGUISHER.get(), 1, 4, Integer.MAX_VALUE));
    }

    static void updateTrader(ServerLevel level, BlockPos dugout, WanderingTrader trader, long day) {
        if (trader.getPersistentData().contains(STOCK_DAY)
                && day == trader.getPersistentData().getLong(STOCK_DAY)) {
            if (trader.getPersistentData().getBoolean(EMPTY_STOCK)) {
                trader.getOffers().clear();
            }
            return;
        }
        if (trader.getTradingPlayer() instanceof ServerPlayer customer) {
            customer.closeContainer();
        }
        Set<Holder<Biome>> possible = level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes();
        Set<ResourceKey<Biome>> nearby = nearbySourceBiomes(level, dugout);
        List<Stock> candidates = new ArrayList<>();
        for (Stock candidate : MATERIALS) {
            if (candidate.biomes().stream().noneMatch(nearby::contains)
                    && possible.stream().anyMatch(biome -> candidate.biomes().stream().anyMatch(biome::is))) {
                candidates.add(candidate);
            }
        }
        Collections.shuffle(candidates, new Random(level.getSeed() ^ dugout.asLong() ^ day));

        MerchantOffers offers = trader.getOffers();
        offers.clear();
        candidates.stream().limit(4).forEach(stock -> offers.add(offer(stock.item(), 16, stock.price(), 8)));
        trader.getPersistentData().putLong(STOCK_DAY, day);
        trader.getPersistentData().putBoolean(EMPTY_STOCK, offers.isEmpty());
    }

    static Set<ResourceKey<Biome>> nearbySourceBiomes(ServerLevel level, BlockPos dugout) {
        Set<ResourceKey<Biome>> nearby = new HashSet<>();
        Set<ResourceKey<Biome>> relevant = new HashSet<>();
        MATERIALS.forEach(stock -> relevant.addAll(stock.biomes()));
        int radiusSquared = LOCAL_RADIUS * LOCAL_RADIUS;
        int centerX = dugout.getX() + 2;
        int centerZ = dugout.getZ() + 2;
        int quartY = QuartPos.fromBlock(dugout.getY());
        for (int quartX = QuartPos.fromBlock(centerX - LOCAL_RADIUS); quartX <= QuartPos.fromBlock(centerX + LOCAL_RADIUS); quartX++) {
            int x = horizontalDelta(centerX, quartX);
            for (int quartZ = QuartPos.fromBlock(centerZ - LOCAL_RADIUS); quartZ <= QuartPos.fromBlock(centerZ + LOCAL_RADIUS); quartZ++) {
                int z = horizontalDelta(centerZ, quartZ);
                if (x * x + z * z <= radiusSquared) {
                    level.getUncachedNoiseBiome(quartX, quartY, quartZ).unwrapKey()
                            .filter(relevant::contains).ifPresent(nearby::add);
                    if (nearby.size() == relevant.size()) {
                        return nearby;
                    }
                }
            }
        }
        return nearby;
    }

    private static int horizontalDelta(int center, int quart) {
        int first = QuartPos.toBlock(quart);
        int last = first + QuartPos.toBlock(1) - 1;
        return Math.max(first - center, Math.max(0, center - last));
    }

    static void open(AbstractVillager merchant, ServerPlayer player) {
        if (merchant.getOffers().isEmpty()) {
            player.sendSystemMessage(Component.literal("Сегодня подходящих материалов нет."));
            return;
        }
        merchant.setTradingPlayer(player);
        merchant.openTradingScreen(player, merchant.getDisplayName(), 1);
    }

    private static MerchantOffer offer(Item item, int count, int emeralds, int uses) {
        return new MerchantOffer(new ItemStack(Items.EMERALD, emeralds), new ItemStack(item, count), uses, 0, 0);
    }

    private record Stock(List<ResourceKey<Biome>> biomes, Item item, int price) {
        Stock(ResourceKey<Biome> biome, Item item, int price) {
            this(List.of(biome), item, price);
        }
    }
}
