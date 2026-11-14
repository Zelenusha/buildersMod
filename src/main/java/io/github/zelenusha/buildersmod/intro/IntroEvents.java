package io.github.zelenusha.buildersmod.intro;

import io.github.zelenusha.buildersmod.contract.IncidentWork;
import io.github.zelenusha.buildersmod.site.ConstructionSite;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;

import java.util.Set;
import java.util.UUID;

public final class IntroEvents {
    private static final String OWNER = "buildersmod_intro_owner";
    private static final String BUILDER = "buildersmod_builder";
    private static final String FOREMAN = "buildersmod_foreman";
    private static final String TRADER = "buildersmod_trader";
    private static final String COMMAND = "buildersmod_job";

    private IntroEvents() {
    }

    public static void register(IEventBus bus) {
        bus.addListener(IntroEvents::registerCommands);
        bus.addListener(IntroEvents::onLogin);
        bus.addListener(IntroEvents::onPlayerTick);
        bus.addListener(IntroEvents::onInteraction);
        bus.addListener(EventPriority.MONITOR, IntroEvents::onBuilderDeath);
        bus.addListener(IntroEvents::onEntityLeave);
        bus.addListener(EventPriority.MONITOR, IntroEvents::onPlaceBlock);
        bus.addListener(EventPriority.MONITOR, IntroEvents::onBreakBlock);
    }

    public static boolean hasAcceptedWork(ServerPlayer player) {
        return IntroData.get(player.server).hasAccepted(player.getUUID());
    }

    public static long firstOffersDay(ServerPlayer player) {
        return IntroData.get(player.server).firstOffersDay(player.getUUID());
    }

    public static Set<ResourceKey<Biome>> nearbyBiomes(ServerPlayer player, Set<ResourceKey<Biome>> biomes) {
        BlockPos dugout = IntroData.get(player.server).work(player.getUUID()).dugout;
        return dugout == null ? Set.of() : IntroTrades.nearbyBiomes(player.server.overworld(), dugout, biomes);
    }

    public static boolean isForeman(Entity entity) {
        return entity.getTags().contains(FOREMAN);
    }

    public static boolean isOwnForeman(ServerPlayer player, Entity entity) {
        return isForeman(entity) && isOwnedBy(player, entity);
    }

    public static int availableSupply(ServerPlayer player, Item item) {
        ServerLevel level = player.server.overworld();
        IntroData.Work work = IntroData.get(player.server).work(player.getUUID());
        if (work.office == null || work.trader == null || !(level.getEntity(work.trader) instanceof WanderingTrader trader)) {
            return 0;
        }
        IntroTrades.updateTrader(level, work.dugout, trader, day(level));
        return trader.getOffers().stream()
                .filter(offer -> offer.getResult().is(item))
                .mapToInt(offer -> (offer.getMaxUses() - offer.getUses()) * offer.getResult().getCount())
                .sum();
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(COMMAND)
                .then(Commands.literal("accept").executes(context -> accept(context.getSource().getPlayerOrException())))
                .then(Commands.literal("decline").executes(context -> decline(context.getSource().getPlayerOrException()))));
    }

    static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            checkBuilder(player);
        }
    }

    private static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) {
            return;
        }
        if (player.tickCount % 20 == 0) {
            checkBuilder(player);
        }
        IntroData.Work work = IntroData.get(player.server).work(player.getUUID());
        ServerLevel overworld = player.server.overworld();
        boolean trading = player.containerMenu instanceof MerchantMenu;
        if (trading) {
            closeTradeIfTooFar(player, overworld, work.builder);
            closeTradeIfTooFar(player, overworld, work.trader);
        }
        if ((trading || player.tickCount % 20 == 0) && work.trader != null
                && overworld.getEntity(work.trader) instanceof WanderingTrader trader) {
            IntroTrades.updateTrader(overworld, work.dugout, trader, day(overworld));
        }
    }

    private static void closeTradeIfTooFar(ServerPlayer player, ServerLevel overworld, UUID merchantId) {
        if (merchantId != null && overworld.getEntity(merchantId) instanceof AbstractVillager merchant
                && merchant.getTradingPlayer() == player
                && (player.level() != overworld || player.distanceToSqr(merchant) > 64)) {
            player.closeContainer();
        }
    }

    private static void checkBuilder(ServerPlayer player) {
        if (!player.isAlive() || player.level().dimension() != Level.OVERWORLD) {
            return;
        }
        IntroData data = IntroData.get(player.server);
        IntroData.Work work = data.work(player.getUUID());
        if (work.office != null || work.builder != null || work.builderReturnDay > day(player.serverLevel())) {
            return;
        }
        BlockPos position = nearbySpawn(player);
        if (position == null) {
            return;
        }
        Villager builder = EntityType.VILLAGER.create(player.serverLevel());
        if (builder != null && spawn(player.serverLevel(), builder, player, position, BUILDER, "Строитель")) {
            work.builder = builder.getUUID();
            work.builderReturnDay = -1;
            data.setDirty();
        }
    }

    private static BlockPos nearbySpawn(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        BlockPos center = player.blockPosition();
        for (BlockPos position : BlockPos.withinManhattan(center, 5, 3, 5)) {
            if (position.distSqr(center) < 4 || !level.getChunkSource().hasChunk(position.getX() >> 4, position.getZ() >> 4)
                    || !level.getWorldBorder().isWithinBounds(position)) {
                continue;
            }
            BlockPos support = position.below();
            if (!level.getBlockState(support).isFaceSturdy(level, support, Direction.UP)
                    || !level.getFluidState(position).isEmpty() || !level.getFluidState(position.above()).isEmpty()) {
                continue;
            }
            AABB body = new AABB(position.getX() + 0.2, position.getY(), position.getZ() + 0.2,
                    position.getX() + 0.8, position.getY() + 1.95, position.getZ() + 0.8);
            if (level.noCollision(body) && level.getEntities(player, body).isEmpty()) {
                return position.immutable();
            }
        }
        return null;
    }

    private static void onInteraction(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getHand() != InteractionHand.MAIN_HAND
                || !(event.getTarget() instanceof AbstractVillager villager) || !isOwnedBy(player, villager)
                || !(villager.getTags().contains(BUILDER) || villager.getTags().contains(TRADER))) {
            return;
        }
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
        IntroData.Work work = IntroData.get(player.server).work(player.getUUID());
        if (work.office == null) {
            if (villager.getUUID().equals(work.builder)) {
                offerWork(player);
            }
            return;
        }
        if (villager instanceof WanderingTrader trader && villager.getUUID().equals(work.trader)) {
            IntroTrades.updateTrader(player.serverLevel(), work.dugout, trader, day(player.server.overworld()));
            IntroTrades.open(trader, player);
        } else if (villager.getUUID().equals(work.builder)) {
            IntroTrades.open(villager, player);
        }
    }

    private static void offerWork(ServerPlayer player) {
        player.sendSystemMessage(Component.literal("Строитель: Нужна работа? Контора даст землянку и заказы на стройке."));
        player.sendSystemMessage(Component.literal("[Принять работу]")
                .withStyle(style -> style.withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/" + COMMAND + " accept")))
                .append(Component.literal(" ")).append(Component.literal("[Отказаться]")
                        .withStyle(style -> style.withColor(ChatFormatting.GRAY)
                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/" + COMMAND + " decline")))));
    }

    private static int decline(ServerPlayer player) {
        if (contactableBuilder(player) == null) {
            return 0;
        }
        player.sendSystemMessage(Component.literal("Строитель: Если передумаешь, подходи."));
        return 1;
    }

    private static int accept(ServerPlayer player) {
        Villager builder = contactableBuilder(player);
        if (builder == null) {
            player.sendSystemMessage(Component.literal("Подойди к строителю, чтобы принять работу."));
            return 0;
        }
        ServerLevel level = player.serverLevel();
        IntroData data = IntroData.get(player.server);
        IntroStructures.Sites sites = IntroStructures.findSites(level, player.blockPosition(), data);
        if (sites == null) {
            player.sendSystemMessage(Component.literal("Строитель: Здесь тесно для землянки и офиса. Поищем более ровное место."));
            return 0;
        }
        Villager foreman = EntityType.VILLAGER.create(level);
        WanderingTrader trader = EntityType.WANDERING_TRADER.create(level);
        if (foreman == null || trader == null) {
            return 0;
        }

        BlockPos office = sites.office();
        foreman.setInvulnerable(true);
        trader.setDespawnDelay(0);
        trader.setInvulnerable(true);
        if (!spawn(level, foreman, player, office.offset(5, 1, 5), FOREMAN, "Прораб")) {
            return 0;
        }
        if (!spawn(level, trader, player, office.offset(10, 1, 4), TRADER, "Странствующий торговец")) {
            foreman.discard();
            return 0;
        }

        IntroStructures.build(level, sites);
        placeNpc(builder, office.offset(3, 1, 3));
        builder.setInvulnerable(true);
        IntroTrades.equipBuilder(builder);

        IntroData.Work work = data.work(player.getUUID());
        work.dugout = sites.dugout();
        work.office = office;
        work.firstOffersDay = day(level) + 1;
        IncidentWork.startClock(player);
        work.trader = trader.getUUID();
        data.setDirty();

        ConstructionSite.installOfficePortal(player, level, IntroStructures.officePortalFrame(office),
                Direction.Axis.X, IntroStructures.officeArrival(office));
        player.sendSystemMessage(Component.literal("Строитель: Землянка и офис готовы. Заказы появятся у прораба завтра."));
        return 1;
    }

    private static Villager contactableBuilder(ServerPlayer player) {
        if (!player.isAlive() || player.level().dimension() != Level.OVERWORLD) {
            return null;
        }
        IntroData.Work work = IntroData.get(player.server).work(player.getUUID());
        if (work.office != null || work.builder == null
                || !(player.serverLevel().getEntity(work.builder) instanceof Villager builder)
                || !builder.isAlive() || !isOwnedBy(player, builder) || builder.distanceToSqr(player) > 64) {
            return null;
        }
        return builder;
    }

    private static void onEntityLeave(EntityLeaveLevelEvent event) {
        Entity entity = event.getEntity();
        Entity.RemovalReason reason = entity.getRemovalReason();
        if (event.getLevel() instanceof ServerLevel level && reason != null) {
            if (reason.shouldDestroy() || reason == Entity.RemovalReason.CHANGED_DIMENSION) {
                scheduleReturn(level, entity);
            }
            if (entity instanceof AbstractVillager merchant && merchant.getPersistentData().hasUUID(OWNER)
                    && merchant.getTradingPlayer() instanceof ServerPlayer customer) {
                customer.closeContainer();
            }
        }
    }

    private static void onBuilderDeath(LivingDeathEvent event) {
        if (event.getEntity().level() instanceof ServerLevel level) {
            scheduleReturn(level, event.getEntity());
        }
    }

    private static void scheduleReturn(ServerLevel level, Entity builder) {
        CompoundTag persisted = builder.getPersistentData();
        if (!builder.getTags().contains(BUILDER) || !persisted.hasUUID(OWNER)) {
            return;
        }
        IntroData data = IntroData.get(level.getServer());
        IntroData.Work work = data.work(persisted.getUUID(OWNER));
        if (work.office == null && builder.getUUID().equals(work.builder)) {
            work.builder = null;
            work.builderReturnDay = day(level.getServer().overworld()) + 1;
            data.setDirty();
        }
    }

    private static boolean isOwnedBy(ServerPlayer player, Entity entity) {
        CompoundTag persisted = entity.getPersistentData();
        return player.level() == entity.level() && persisted.hasUUID(OWNER)
                && player.getUUID().equals(persisted.getUUID(OWNER));
    }

    private static boolean spawn(ServerLevel level, AbstractVillager npc, ServerPlayer owner, BlockPos position,
                                 String tag, String name) {
        npc.addTag(tag);
        npc.getPersistentData().putUUID(OWNER, owner.getUUID());
        npc.setCustomName(Component.literal(name));
        npc.setCustomNameVisible(true);
        npc.setPersistenceRequired();
        npc.setNoAi(true);
        placeNpc(npc, position);
        return level.addFreshEntity(npc);
    }

    private static void placeNpc(AbstractVillager npc, BlockPos position) {
        npc.moveTo(position.getX() + 0.5, position.getY(), position.getZ() + 0.5, 180, 0);
    }

    private static long day(ServerLevel level) {
        return Math.floorDiv(level.getDayTime(), 24_000L);
    }

    private static void onPlaceBlock(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD
                && event.getEntity() instanceof ServerPlayer) {
            IntroData data = IntroData.get(level.getServer());
            if (event instanceof BlockEvent.EntityMultiPlaceEvent multiPlace) {
                multiPlace.getReplacedBlockSnapshots().forEach(snapshot -> data.protect(snapshot.getPos()));
            } else {
                data.protect(event.getPos());
            }
        }
    }

    private static void onBreakBlock(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
            IntroData.get(level.getServer()).unprotect(event.getPos());
        }
    }
}
