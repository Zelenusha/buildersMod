package io.github.zelenusha.buildersmod.site;

import io.github.zelenusha.buildersmod.contract.ContractData;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

final class SiteLighting {
    private static final int SPACING = 8;

    private SiteLighting() { }

    static void generate(WorldGenLevel level, ChunkPos chunk) {
        forEachFixture(chunk, pos -> level.setBlock(pos, Blocks.SEA_LANTERN.defaultBlockState(), Block.UPDATE_CLIENTS));
    }

    static void updateNearby(ServerLevel level, ChunkPos center) {
        SiteLinks links = SiteLinks.get(level.getServer());
        ContractData contracts = ContractData.get(level.getServer());
        for (int x = center.x - 1; x <= center.x + 1; x++) {
            for (int z = center.z - 1; z <= center.z + 1; z++) {
                if (level.getChunkSource().getChunkNow(x, z) != null) {
                    retrofit(level, new ChunkPos(x, z), links, contracts::containsSiteWork);
                }
            }
        }
    }

    static void retrofit(ServerLevel level, ChunkPos chunk, SiteLinks links, Predicate<BlockPos> reserved) {
        if (!links.markLightingChecked(chunk.toLong())) {
            return;
        }
        forEachFixture(chunk, pos -> {
            if (level.getBlockState(pos).is(Blocks.STONE) && level.isEmptyBlock(pos.above())
                    && level.isEmptyBlock(pos.above(2)) && !reserved.test(pos)) {
                level.setBlock(pos, Blocks.SEA_LANTERN.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        });
    }

    private static void forEachFixture(ChunkPos chunk, java.util.function.Consumer<BlockPos> fixture) {
        for (int dx = SPACING / 2; dx < 16; dx += SPACING) {
            for (int dz = SPACING / 2; dz < 16; dz += SPACING) {
                int x = chunk.getMinBlockX() + dx;
                int z = chunk.getMinBlockZ() + dz;
                if (Math.abs(x) <= 4 && Math.abs(z) <= 4) {
                    continue;
                }
                for (int floor = 0; floor < ConstructionSite.FLOOR_COUNT; floor++) {
                    int y = ConstructionSite.START_FLOOR_Y + floor * ConstructionSite.FLOOR_STEP;
                    fixture.accept(new BlockPos(x, y, z));
                }
            }
        }
    }
}
