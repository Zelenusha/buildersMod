package io.github.zelenusha.buildersmod.contract;

import io.github.zelenusha.buildersmod.BuildersMod;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

final class MaterialRarity {
    private static final Map<String, Integer> TIERS = load();

    private MaterialRarity() { }

    static int tier(Block block) {
        return TIERS.getOrDefault(BuiltInRegistries.BLOCK.getKey(block).toString(), 0);
    }

    private static Map<String, Integer> load() {
        Map<String, Integer> tiers = new HashMap<>();
        try (InputStream input = BuildersMod.class.getResourceAsStream("/data/buildersmod/block_rarity.csv")) {
            if (input == null) {
                throw new IllegalStateException("Missing block rarity catalogue");
            }
            String contents = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : contents.lines().toList()) {
                String[] fields = line.split(",", 2);
                tiers.put(fields[0], Integer.parseInt(fields[1]));
            }
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read block rarity catalogue", error);
        }
        return Map.copyOf(tiers);
    }
}
