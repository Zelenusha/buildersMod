package io.github.zelenusha.buildersmod.maintenance;

import io.github.zelenusha.buildersmod.BuildersMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class WorkItems {
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, BuildersMod.MOD_ID);
    private static final DeferredRegister<RecipeSerializer<?>> RECIPES =
            DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, BuildersMod.MOD_ID);

    public static final RegistryObject<Item> WET_RAG = ITEMS.register("wet_rag", () -> new Item(new Item.Properties().durability(8)));
    public static final RegistryObject<Item> FIRE_EXTINGUISHER = ITEMS.register("fire_extinguisher", FireExtinguisherItem::new);
    private static final RegistryObject<RecipeSerializer<?>> FIRE_EXTINGUISHER_RECIPE = RECIPES.register(
            "fire_extinguisher", () -> new SimpleCraftingRecipeSerializer<>(FireExtinguisherRecipe::new));

    private WorkItems() {}

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        RECIPES.register(modBus);
    }

    public static boolean useWetRag(ServerPlayer player, InteractionHand hand) {
        ItemStack rag = player.getItemInHand(hand);
        if (!rag.is(WET_RAG.get())) {
            return false;
        }
        rag.hurtAndBreak(1, player, user -> user.broadcastBreakEvent(hand));
        return true;
    }

    private static final class FireExtinguisherItem extends Item {
        FireExtinguisherItem() {
            super(new Properties().durability(16));
        }

        @Override
        public InteractionResult useOn(UseOnContext context) {
            Level level = context.getLevel();
            BlockPos center = context.getClickedPos();
            Player player = context.getPlayer();
            if (player == null || !level.getBlockState(center).is(BlockTags.FIRE)) {
                return InteractionResult.PASS;
            }
            if (level.isClientSide) {
                return InteractionResult.SUCCESS;
            }

            boolean extinguished = false;
            for (BlockPos pos : BlockPos.betweenClosed(center.offset(-1, -1, -1), center.offset(1, 1, 1))) {
                if (level.getChunkSource().hasChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()))
                        && level.mayInteract(player, pos)
                        && level.getBlockState(pos).is(BlockTags.FIRE) && level.removeBlock(pos, false)) {
                    extinguished = true;
                }
            }
            if (!extinguished) {
                return InteractionResult.PASS;
            }
            level.levelEvent(null, LevelEvent.SOUND_EXTINGUISH_FIRE, center, 0);
            context.getItemInHand().hurtAndBreak(1, player, user -> user.broadcastBreakEvent(context.getHand()));
            return InteractionResult.CONSUME;
        }
    }

    private static final class FireExtinguisherRecipe extends CustomRecipe {
        FireExtinguisherRecipe(ResourceLocation id, CraftingBookCategory category) {
            super(id, category);
        }

        @Override
        public boolean matches(CraftingContainer grid, Level level) {
            if (!canCraftInDimensions(grid.getWidth(), grid.getHeight())) {
                return false;
            }
            for (int slot = 0; slot < grid.getContainerSize(); slot++) {
                ItemStack ingredient = grid.getItem(slot);
                boolean matches = switch (slot) {
                    case 1, 4 -> ingredient.is(Items.IRON_INGOT);
                    case 7 -> ingredient.is(Items.COBWEB);
                    default -> ingredient.isEmpty();
                };
                if (!matches) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public ItemStack assemble(CraftingContainer grid, RegistryAccess registries) {
            return getResultItem(registries);
        }

        @Override
        public ItemStack getResultItem(RegistryAccess registries) {
            return new ItemStack(FIRE_EXTINGUISHER.get());
        }

        @Override
        public boolean canCraftInDimensions(int width, int height) {
            return width == 3 && height == 3;
        }

        @Override
        public RecipeSerializer<?> getSerializer() {
            return FIRE_EXTINGUISHER_RECIPE.get();
        }
    }
}
