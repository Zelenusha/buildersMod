package io.github.zelenusha.buildersmod.maintenance;

import com.mojang.authlib.GameProfile;
import io.github.zelenusha.buildersmod.BuildersMod;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(BuildersMod.MOD_ID)
@PrefixGameTestTemplate(false)
public class WorkItemGameTests {
    @GameTest(template = "empty")
    public static void wetRagRecipeRequiresPlainWaterAndGrantsEightUses(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer player = survivalPlayer(level);
        var grid = grid(player);
        for (int slot = 0; slot < grid.getContainerSize(); slot++) {
            grid.setItem(slot, new ItemStack(Items.PAPER));
        }
        CraftingRecipe recipe = recipe(level, "wet_rag");
        grid.setItem(4, PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.WATER));
        helper.assertTrue(currentRecipe(level, grid, recipe), "Only a plain water bottle in the center yields a wet rag");
        ItemStack rag = recipe.assemble(grid, level.registryAccess());
        helper.assertTrue(rag.is(WorkItems.WET_RAG.get()) && rag.getMaxDamage() == 8, "Crafting grants one eight-use rag");

        grid.setItem(4, PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.AWKWARD));
        helper.assertTrue(!recipe.matches(grid, level), "Another potion does not substitute for water");
        grid.setItem(4, new ItemStack(Items.GLASS_BOTTLE));
        helper.assertTrue(!recipe.matches(grid, level), "An empty bottle does not substitute for water");

        player.setItemInHand(InteractionHand.OFF_HAND, rag);
        helper.assertTrue(!WorkItems.useWetRag(player, InteractionHand.MAIN_HAND) && rag.getDamageValue() == 0,
                "An empty hand cannot consume a rag use");
        for (int use = 1; use < 8; use++) {
            helper.assertTrue(WorkItems.useWetRag(player, InteractionHand.OFF_HAND) && rag.getDamageValue() == use,
                    "Every attempt consumes exactly one use");
        }
        helper.assertTrue(WorkItems.useWetRag(player, InteractionHand.OFF_HAND) && rag.isEmpty()
                        && !WorkItems.useWetRag(player, InteractionHand.OFF_HAND),
                "The eighth attempt breaks the rag and a ninth attempt is rejected");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void extinguisherRecipeUsesOnlyItsCentralColumn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer player = survivalPlayer(level);
        CraftingRecipe recipe = recipe(level, "fire_extinguisher");
        var grid = grid(player);
        grid.setItem(1, new ItemStack(Items.IRON_INGOT));
        grid.setItem(4, new ItemStack(Items.IRON_INGOT));
        grid.setItem(7, new ItemStack(Items.COBWEB));
        helper.assertTrue(currentRecipe(level, grid, recipe), "The loaded recipe matches the exact central column");
        ItemStack extinguisher = recipe.assemble(grid, level.registryAccess());
        helper.assertTrue(extinguisher.is(WorkItems.FIRE_EXTINGUISHER.get()) && extinguisher.getMaxDamage() == 16,
                "Crafting grants one sixteen-use extinguisher");
        grid.setItem(8, new ItemStack(Items.PAPER));
        helper.assertTrue(!recipe.matches(grid, level), "An extra ingredient is rejected");
        grid.clearContent();
        grid.setItem(0, new ItemStack(Items.IRON_INGOT));
        grid.setItem(3, new ItemStack(Items.IRON_INGOT));
        grid.setItem(6, new ItemStack(Items.COBWEB));
        helper.assertTrue(!recipe.matches(grid, level), "Shifting the same pattern to a side column is rejected");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void extinguisherClearsOnlyFireInTheSelectedCube(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer player = survivalPlayer(level);
        BlockPos center = helper.absolutePos(new BlockPos(0, 11, 0));
        List<BlockPos> inside = List.of(center, center.offset(-1, -1, -1), center.offset(1, 1, 1));
        List<BlockPos> outside = List.of(center.east(2), center.offset(0, 2, 1));
        var fires = new java.util.ArrayList<>(inside);
        fires.addAll(outside);
        Map<BlockPos, BlockState> previous = new LinkedHashMap<>();
        ItemStack extinguisher = new ItemStack(WorkItems.FIRE_EXTINGUISHER.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, extinguisher);
        player.moveTo(center.getX() + 0.5, center.getY(), center.getZ() - 1.5, 0, 0);
        try {
            for (BlockPos position : fires) {
                for (BlockPos saved : List.of(position.below(), position)) {
                    previous.put(saved, level.getBlockState(saved));
                }
                level.setBlock(position.below(), Blocks.NETHERRACK.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(position, Blocks.FIRE.defaultBlockState(), Block.UPDATE_ALL);
                helper.assertTrue(level.getBlockState(position).is(BlockTags.FIRE), "Fixture fire stands on solid support");
            }
            helper.assertTrue(useAt(level, player, extinguisher, center).consumesAction(),
                    "Using the extinguisher on the center starts one application");
            helper.assertTrue(inside.stream().allMatch(level::isEmptyBlock)
                            && outside.stream().allMatch(pos -> level.getBlockState(pos).is(BlockTags.FIRE)),
                    "The 3×3×3 cube includes diagonal top and bottom corners and excludes fire two blocks away");
            helper.assertTrue(extinguisher.getDamageValue() == 1, "One application over several fires costs one use");
            helper.assertTrue(!useAt(level, player, extinguisher, center.below()).consumesAction()
                            && extinguisher.getDamageValue() == 1, "A targeted support block does not consume a use");
        } finally {
            previous.forEach((pos, state) -> level.setBlock(pos, state, Block.UPDATE_CLIENTS));
        }
        helper.succeed();
    }

    private static net.minecraft.world.InteractionResult useAt(ServerLevel level, FakePlayer player, ItemStack item, BlockPos position) {
        return player.gameMode.useItemOn(player, level, item, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(position), Direction.NORTH, position, false));
    }

    private static FakePlayer survivalPlayer(ServerLevel level) {
        FakePlayer player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "work-items"));
        player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        return player;
    }

    private static TransientCraftingContainer grid(FakePlayer player) {
        return new TransientCraftingContainer(new CraftingMenu(1, player.getInventory()), 3, 3);
    }

    private static CraftingRecipe recipe(ServerLevel level, String name) {
        return level.getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath(BuildersMod.MOD_ID, name))
                .filter(CraftingRecipe.class::isInstance).map(CraftingRecipe.class::cast).orElseThrow();
    }

    private static boolean currentRecipe(ServerLevel level, TransientCraftingContainer grid, CraftingRecipe expected) {
        return level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, grid, level)
                .map(recipe -> recipe.getId().equals(expected.getId())).orElse(false);
    }
}
