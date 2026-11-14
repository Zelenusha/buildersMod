package io.github.zelenusha.buildersmod.contract;

import io.github.zelenusha.buildersmod.BuildersMod;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint;
import io.github.zelenusha.buildersmod.site.ConstructionSite;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = BuildersMod.MOD_ID)
public final class DemolitionWork {
    private static final Map<ServerLevel, BreakCapture> BREAKS = new WeakHashMap<>();
    private static final Set<ItemEntity> TOSSED = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private final UUID id;
    private final ContractData.Accepted order;
    private final Map<Item, Integer> materials = new LinkedHashMap<>();

    public DemolitionWork(ContractData.Accepted order) {
        this(UUID.randomUUID(), order);
    }

    private DemolitionWork(UUID id, ContractData.Accepted order) {
        this.id = id;
        this.order = order;
    }

    public UUID id() {
        return id;
    }

    public ContractData.Accepted order() {
        return order;
    }

    public Map<Item, Integer> materials() {
        return Collections.unmodifiableMap(materials);
    }

    public static Map<BlockPos, BlockState> snapshot(BlockGetter world, RoomBlueprint blueprint) {
        Map<BlockPos, BlockState> baseline = new HashMap<>();
        for (BlockPos pos : blueprint.bounds().positions()) {
            BlockState state = world.getBlockState(pos);
            if (!state.isAir() || restoresDeck(blueprint, pos)) {
                baseline.put(pos, state);
            }
        }
        return Map.copyOf(baseline);
    }

    public boolean readyToDeliver(BlockGetter world) {
        if (world instanceof ServerLevel level) {
            finishCapture(level);
        }
        for (BlockPos pos : order.blueprint().bounds().positions()) {
            BlockState actual = world.getBlockState(pos);
            BlockState original = originalAt(pos);
            if (actual.equals(original) || actual.isAir() && original.isAir()) {
                continue;
            }
            if (!actual.isAir() || restoresDeck(order.blueprint(), pos)) {
                return false;
            }
        }
        return true;
    }

    public boolean deliver(Inventory inventory) {
        Map<Item, Integer> remaining = new HashMap<>(materials);
        List<ItemStack> carried = new ArrayList<>(inventory.items);
        carried.addAll(inventory.offhand);
        for (ItemStack stack : carried) {
            remaining.computeIfPresent(stack.getItem(), (item, needed) -> Math.max(0, needed - stack.getCount()));
        }
        if (remaining.values().stream().anyMatch(needed -> needed > 0)) {
            return false;
        }
        remaining.putAll(materials);
        for (ItemStack stack : carried) {
            Item item = stack.getItem();
            int taken = Math.min(remaining.getOrDefault(item, 0), stack.getCount());
            if (taken > 0) {
                stack.shrink(taken);
                remaining.put(item, remaining.get(item) - taken);
            }
        }
        inventory.setChanged();
        return true;
    }

    void recordDrop(ItemStack stack) {
        if (!stack.isEmpty()) {
            materials.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        ListTag items = new ListTag();
        materials.forEach((item, count) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("Item", BuiltInRegistries.ITEM.getKey(item).toString());
            entry.putInt("Count", count);
            items.add(entry);
        });
        tag.put("Materials", items);
        return tag;
    }

    public static DemolitionWork load(ContractData.Accepted order, CompoundTag tag) {
        DemolitionWork work = tag.hasUUID("Id") ? new DemolitionWork(tag.getUUID("Id"), order) : new DemolitionWork(order);
        ListTag items = tag.getList("Materials", Tag.TAG_COMPOUND);
        for (int index = 0; index < items.size(); index++) {
            CompoundTag entry = items.getCompound(index);
            ResourceLocation key = ResourceLocation.tryParse(entry.getString("Item"));
            int count = entry.getInt("Count");
            if (key != null && count > 0) {
                Item item = BuiltInRegistries.ITEM.get(key);
                if (item != Items.AIR) {
                    work.materials.put(item, count);
                }
            }
        }
        return work;
    }

    private BlockState originalAt(BlockPos pos) {
        BlockState original = order.clearanceOriginals().get(pos);
        if (original != null) {
            return original;
        }
        // Old saves did not always record stone that already satisfied the floor requirement.
        return restoresDeck(order.blueprint(), pos) ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
    }

    private static boolean restoresDeck(RoomBlueprint blueprint, BlockPos pos) {
        int deckOffset = pos.getY() - ConstructionSite.START_FLOOR_Y;
        RoomBlueprint.Requirement requirement = blueprint.requirements().get(pos);
        return deckOffset >= 0 && deckOffset < ConstructionSite.FLOOR_STEP * ConstructionSite.FLOOR_COUNT
                && deckOffset % ConstructionSite.FLOOR_STEP == 0
                && requirement != null && requirement.criterion() == RoomBlueprint.Criterion.FLOOR;
    }

    @SubscribeEvent(priority = EventPriority.MONITOR)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != ConstructionSite.SITE_LEVEL
                || !(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        finishCapture(level);
        ContractData data = ContractData.get(level.getServer());
        BlockPos broken = event.getPos();
        for (DemolitionWork work : data.account(player.getUUID()).failed) {
            RoomBlueprint.Bounds bounds = work.order.blueprint().bounds();
            if (!bounds.contains(broken)) {
                continue;
            }
            Map<BlockPos, BlockState> nearby = new HashMap<>();
            for (BlockPos pos : BlockPos.betweenClosed(broken.offset(-1, -1, -1), broken.offset(1, 1, 1))) {
                BlockState state = level.getBlockState(pos);
                if (bounds.contains(pos) && !state.isAir() && !state.equals(work.originalAt(pos))) {
                    nearby.put(pos.immutable(), state);
                }
            }
            if (!nearby.isEmpty()) {
                BREAKS.put(level, new BreakCapture(level.getGameTime(), work, data, nearby, new ArrayList<>()));
            }
            break;
        }
    }

    @SubscribeEvent(priority = EventPriority.MONITOR)
    public static void onToss(ItemTossEvent event) {
        if (!event.getPlayer().level().isClientSide && event.getPlayer().level().dimension() == ConstructionSite.SITE_LEVEL) {
            TOSSED.add(event.getEntity());
        }
    }

    @SubscribeEvent(priority = EventPriority.MONITOR)
    public static void onItemSpawn(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ItemEntity drop)
                || event.loadedFromDisk() || TOSSED.remove(drop) || !drop.hasPickUpDelay()) {
            return;
        }
        BreakCapture capture = BREAKS.get(level);
        BlockPos pos = drop.blockPosition();
        if (capture != null && level.getGameTime() == capture.tick() && capture.before().containsKey(pos)) {
            capture.drops().add(new SpawnedDrop(pos, drop.getItem().copy()));
        }
    }

    @SubscribeEvent
    public static void endTick(TickEvent.LevelTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.level instanceof ServerLevel level) {
            finishCapture(level);
        }
    }

    private static void finishCapture(ServerLevel level) {
        BreakCapture capture = BREAKS.remove(level);
        if (capture == null) {
            return;
        }
        // Loot spawns synchronously, but attached blocks may produce loot before removing themselves.
        boolean changed = false;
        for (SpawnedDrop drop : capture.drops()) {
            if (!drop.stack().isEmpty() && !level.getBlockState(drop.pos()).equals(capture.before().get(drop.pos()))) {
                capture.work().recordDrop(drop.stack());
                changed = true;
            }
        }
        if (changed) {
            capture.data().setDirty();
        }
    }

    private record BreakCapture(long tick, DemolitionWork work, ContractData data, Map<BlockPos, BlockState> before,
                                List<SpawnedDrop> drops) { }

    private record SpawnedDrop(BlockPos pos, ItemStack stack) { }
}
