package io.github.zelenusha.buildersmod.intro;

import com.mojang.authlib.GameProfile;
import io.github.zelenusha.buildersmod.BuildersMod;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

@GameTestHolder(BuildersMod.MOD_ID)
@PrefixGameTestTemplate(false)
public class IntroFlowGameTests {
    @GameTest(template = "empty")
    public static void refusalKeepsBuilderAndAcceptanceProtectsExistingBlocks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos ground = flatGround(helper, 0, 20);
        BlockPos chest = ground.north(4).above();
        BlockPos protectedDirt = ground.west(4);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(protectedDirt, Blocks.DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);
        IntroData data = IntroData.get(level.getServer());
        data.protect(protectedDirt);

        UUID owner = UUID.randomUUID();
        FakePlayer player = new FakePlayer(level, new GameProfile(owner, "intro-flow"));
        player.moveTo(ground.getX() + 0.5, ground.getY() + 1, ground.getZ() + 0.5, 0, 0);
        try {
            IntroEvents.onLogin(new PlayerEvent.PlayerLoggedInEvent(player));
            IntroData.Work work = data.work(owner);
            helper.assertTrue(work.builder != null && level.getEntity(work.builder) instanceof Villager,
                    "The player's first login creates a nearby builder");
            UUID originalBuilder = work.builder;
            Villager builder = (Villager) level.getEntity(originalBuilder);
            helper.assertTrue(player.distanceToSqr(builder) <= 64, "The builder is within interaction range");
            helper.assertTrue(!data.hasAccepted(owner) && work.dugout == null && work.office == null,
                    "Housing and office must wait for acceptance");

            helper.assertTrue(interact(player, builder), "The initial interaction opens the offer");
            helper.assertTrue(execute(player, "decline") == 1, "The player can decline the job");
            helper.assertTrue(!data.hasAccepted(owner) && work.dugout == null && work.office == null,
                    "Refusal does not create either building");
            helper.assertTrue(originalBuilder.equals(work.builder) && interact(player, builder),
                    "The original builder can reopen the offer after refusal");
            helper.assertTrue(execute(player, "accept") == 1, "The player can accept on the second interaction");

            helper.assertTrue(data.hasAccepted(owner) && originalBuilder.equals(work.builder),
                    "Acceptance moves the same builder rather than creating another one");
            BlockPos home = data.dugout(owner);
            BlockPos officeCenter = work.office.offset(6, 0, 5);
            helper.assertTrue(horizontalDistanceSquared(ground, home) < horizontalDistanceSquared(ground, officeCenter),
                    "The office is farther from the player than the dugout");
            helper.assertTrue(!inFootprint(chest, work.dugout, 5, 5) && !inFootprint(chest, work.office, 13, 11)
                    && level.getBlockEntity(chest) instanceof ChestBlockEntity, "Both plots avoid the existing chest");
            helper.assertTrue(!inFootprint(protectedDirt, work.dugout, 5, 5) && !inFootprint(protectedDirt, work.office, 13, 11)
                    && level.getBlockState(protectedDirt).is(Blocks.DIRT), "Both plots avoid protected natural-looking blocks");
            helper.assertTrue(builder.blockPosition().equals(work.office.offset(3, 1, 3)), "The builder moved into the office");
            helper.assertTrue(level.getEntity(work.trader) instanceof WanderingTrader,
                    "The office has its own wandering trader");
            helper.assertTrue(level.getEntitiesOfClass(Villager.class, new AABB(work.office, work.office.offset(13, 6, 11)))
                    .stream().filter(entity -> IntroEvents.isOwnForeman(player, entity)).count() == 1,
                    "The office has the player's foreman");
            Block portal = ForgeRegistries.BLOCKS.getValue(ResourceLocation.fromNamespaceAndPath(BuildersMod.MOD_ID, "site_portal"));
            helper.assertTrue(portal != null && level.getBlockState(data.officePortalOpening(owner)).is(portal),
                    "The office portal is active on the first visit");
            helper.assertTrue(data.firstOffersDay(owner) == Math.floorDiv(level.getDayTime(), 24_000L) + 1,
                    "First offers unlock the next game day");
            helper.assertTrue(execute(player, "accept") == 0, "The accepted offer cannot be taken again");

            IntroData loaded = IntroData.load(data.save(new CompoundTag()));
            IntroData.Work loadedWork = loaded.work(owner);
            helper.assertTrue(loaded.hasAccepted(owner) && originalBuilder.equals(loadedWork.builder)
                    && work.trader.equals(loadedWork.trader) && work.dugout.equals(loadedWork.dugout)
                    && work.office.equals(loadedWork.office) && work.firstOffersDay == loaded.firstOffersDay(owner)
                    && loaded.isProtected(protectedDirt), "Work and protected blocks survive an NBT roundtrip");
        } finally {
            data.unprotect(protectedDirt);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void builderDeathPersistsReturnDayAndRespawnsOnce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos ground = flatGround(helper, 128, 8);
        forceLoad(level, ground, 8, true);
        whenEntitiesTick(helper, level, ground, () -> {
            try {
                builderDeathAndReturn(helper, level, ground);
            } finally {
                forceLoad(level, ground, 8, false);
            }
        });
    }

    /** The platform lies outside the test structure. Entities in its new chunks stay hidden until the chunks tick. */
    private static void whenEntitiesTick(GameTestHelper helper, ServerLevel level, BlockPos ground, Runnable scenario) {
        if (level.isPositionEntityTicking(ground)) {
            scenario.run();
        } else {
            helper.runAfterDelay(1, () -> whenEntitiesTick(helper, level, ground, scenario));
        }
    }

    private static void forceLoad(ServerLevel level, BlockPos center, int radius, boolean loaded) {
        for (int x = center.getX() - radius >> 4; x <= center.getX() + radius >> 4; x++) {
            for (int z = center.getZ() - radius >> 4; z <= center.getZ() + radius >> 4; z++) {
                level.setChunkForced(x, z, loaded);
            }
        }
    }

    private static void builderDeathAndReturn(GameTestHelper helper, ServerLevel level, BlockPos ground) {
        UUID owner = UUID.randomUUID();
        FakePlayer player = new FakePlayer(level, new GameProfile(owner, "intro-return"));
        player.moveTo(ground.getX() + 0.5, ground.getY() + 1, ground.getZ() + 0.5, 0, 0);
        IntroData data = IntroData.get(level.getServer());
        long originalTime = level.getDayTime();
        try {
            IntroEvents.onLogin(new PlayerEvent.PlayerLoggedInEvent(player));
            IntroData.Work work = data.work(owner);
            helper.assertTrue(work.builder != null && level.getEntity(work.builder) instanceof Villager, "Builder spawned");
            UUID oldId = work.builder;
            Entity oldBuilder = level.getEntity(oldId);
            CompoundTag unloaded = oldBuilder.saveWithoutId(new CompoundTag());
            oldBuilder.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            IntroEvents.onLogin(new PlayerEvent.PlayerLoggedInEvent(player));
            helper.assertTrue(oldId.equals(work.builder) && work.builderReturnDay == -1,
                    "Unloading a living builder keeps its identity and no replacement is scheduled");
            Villager reloaded = net.minecraft.world.entity.EntityType.VILLAGER.create(level);
            if (reloaded == null) {
                helper.fail("Could not reload the builder");
                return;
            }
            reloaded.load(unloaded);
            helper.assertTrue(level.addFreshEntity(reloaded) && oldId.equals(reloaded.getUUID()),
                    "The same living builder can be loaded back into the world");
            reloaded.kill();
            long returnDay = Math.floorDiv(originalTime, 24_000L) + 1;
            helper.assertTrue(work.builder == null && work.builderReturnDay == returnDay,
                    "Death clears the builder and schedules a return on the next day");
            IntroData.Work loaded = IntroData.load(data.save(new CompoundTag())).work(owner);
            helper.assertTrue(loaded.builder == null && loaded.builderReturnDay == returnDay,
                    "A restart retains the scheduled return");
            IntroEvents.onLogin(new PlayerEvent.PlayerLoggedInEvent(player));
            helper.assertTrue(work.builder == null, "The builder cannot return on the same day");
            level.setDayTime(returnDay * 24_000L);
            IntroEvents.onLogin(new PlayerEvent.PlayerLoggedInEvent(player));
            UUID newId = work.builder;
            helper.assertTrue(newId != null && !newId.equals(oldId) && level.getEntity(newId) instanceof Villager
                    && work.builderReturnDay == -1, "The builder returns on the next day");
            IntroEvents.onLogin(new PlayerEvent.PlayerLoggedInEvent(player));
            helper.assertTrue(newId.equals(work.builder), "Later logins keep the replacement builder");
        } finally {
            level.setDayTime(originalTime);
        }
        helper.succeed();
    }

    private static BlockPos flatGround(GameTestHelper helper, int xOffset, int radius) {
        ServerLevel level = helper.getLevel();
        BlockPos testOrigin = helper.absolutePos(BlockPos.ZERO);
        BlockPos ground = new BlockPos(testOrigin.getX() + xOffset, level.getMaxBuildHeight() - 12, testOrigin.getZ());
        for (BlockPos pos : BlockPos.betweenClosed(ground.offset(-radius, 0, -radius), ground.offset(radius, 0, radius))) {
            level.setBlock(pos, Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        return ground;
    }

    private static boolean interact(FakePlayer player, Villager builder) {
        return MinecraftForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, builder));
    }

    private static int execute(FakePlayer player, String action) {
        return player.server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), "buildersmod_job " + action);
    }

    private static boolean inFootprint(BlockPos pos, BlockPos origin, int width, int depth) {
        return pos.getX() >= origin.getX() && pos.getX() < origin.getX() + width
                && pos.getZ() >= origin.getZ() && pos.getZ() < origin.getZ() + depth;
    }

    private static long horizontalDistanceSquared(BlockPos first, BlockPos second) {
        long dx = first.getX() - second.getX();
        long dz = first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }
}
