package io.github.zelenusha.buildersmod.intro;

import com.mojang.authlib.GameProfile;
import io.github.zelenusha.buildersmod.BuildersMod;
import io.github.zelenusha.buildersmod.maintenance.WorkItems;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biomes;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(BuildersMod.MOD_ID)
@PrefixGameTestTemplate(false)
public class TraderInteractionGameTests {
    @GameTest(template = "empty")
    public static void ownerRightClicksEachMerchantAndReceivesOnlyItsOffers(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos office = helper.absolutePos(new BlockPos(0, 14, 0));
        ServerPlayer player = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "merchant-owner"));
        RecordingConnection connection = new RecordingConnection(player);
        var builder = IntroNpcs.BUILDER.get().create(level);
        var trader = EntityType.WANDERING_TRADER.create(level);
        if (builder == null || trader == null) {
            helper.fail("Could not create test merchants");
            return;
        }

        IntroData.Work work = IntroData.get(level.getServer()).work(player.getUUID());
        work.office = office;
        work.dugout = office.west(12);
        work.builder = builder.getUUID();
        work.trader = trader.getUUID();
        prepareMerchant(builder, player, office.east(), "buildersmod_builder", "Builder Test Shop");
        prepareMerchant(trader, player, office.west(), "buildersmod_trader", "Materials Test Shop");
        moveTo(player, office);
        IntroTrades.equipBuilder(builder);
        long day = Math.floorDiv(level.getDayTime(), 24_000L);
        IntroTrades.updateTrader(trader, day, 13L, () -> Set.of(Biomes.DESERT), Set::of);

        try {
            helper.assertTrue(level.addFreshEntity(builder) && level.addFreshEntity(trader), "Registered both shops");
            connection.packets.clear();
            ClientboundMerchantOffersPacket tools = rightClick(helper, connection, player, builder);
            Set<Item> builderItems = items(tools);
            helper.assertTrue(tools.getOffers().size() == 12 && builderItems.size() == 12
                            && builderItems.containsAll(Set.of(Items.IRON_AXE, Items.STONE_PICKAXE,
                            WorkItems.WET_RAG.get(), WorkItems.FIRE_EXTINGUISHER.get()))
                            && !builderItems.contains(Items.SANDSTONE),
                    "Builder's packet contains starter tools and equipment");
            int previousContainer = tools.getContainerId();
            player.closeContainer();
            helper.assertTrue(builder.getTradingPlayer() == null, "Closing the builder menu frees its trade session");

            connection.packets.clear();
            ClientboundMerchantOffersPacket materials = rightClick(helper, connection, player, trader);
            helper.assertTrue(materials.getContainerId() != previousContainer && builder.getTradingPlayer() == null,
                    "The trader opens its own menu while the builder's trade session remains closed");
            helper.assertTrue(items(materials).equals(Set.of(Items.SAND, Items.SANDSTONE))
                            && materials.getOffers().stream().allMatch(offer -> offer.getResult().getCount() == 16),
                    "Trader's packet contains only its stocked materials in lots of sixteen");
        } finally {
            player.closeContainer();
            builder.discard();
            trader.discard();
            work.office = null;
            work.dugout = null;
            work.builder = null;
            work.trader = null;
        }
        helper.succeed();
    }

    private static ClientboundMerchantOffersPacket rightClick(GameTestHelper helper, RecordingConnection connection,
                                                                ServerPlayer player, AbstractVillager merchant) {
        connection.handleInteract(ServerboundInteractPacket.createInteractionPacket(merchant, false, InteractionHand.MAIN_HAND));
        helper.assertTrue(player.containerMenu instanceof MerchantMenu && merchant.getTradingPlayer() == player,
                "Right-click immediately opens the selected merchant's vanilla menu");
        int containerId = player.containerMenu.containerId;
        var openings = connection.packets.stream().filter(ClientboundOpenScreenPacket.class::isInstance)
                .map(ClientboundOpenScreenPacket.class::cast).toList();
        helper.assertTrue(openings.size() == 1 && openings.get(0).getContainerId() == containerId
                        && openings.get(0).getType() == MenuType.MERCHANT
                        && openings.get(0).getTitle().equals(merchant.getDisplayName()),
                "The client receives a single open-screen packet for this merchant");
        var offers = connection.packets.stream().filter(ClientboundMerchantOffersPacket.class::isInstance)
                .map(ClientboundMerchantOffersPacket.class::cast).toList();
        helper.assertTrue(offers.size() == 1 && offers.get(0).getContainerId() == containerId,
                "The client receives a single offers packet for the opened menu");
        return offers.get(0);
    }

    private static Set<Item> items(ClientboundMerchantOffersPacket packet) {
        return packet.getOffers().stream().map(offer -> offer.getResult().getItem()).collect(Collectors.toSet());
    }

    private static void prepareMerchant(AbstractVillager merchant, ServerPlayer owner, BlockPos pos, String tag, String name) {
        merchant.addTag(tag);
        merchant.getPersistentData().putUUID("buildersmod_intro_owner", owner.getUUID());
        merchant.setCustomName(Component.literal(name));
        merchant.setNoAi(true);
        moveTo(merchant, pos);
    }

    private static void moveTo(Entity entity, BlockPos pos) {
        entity.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
    }

    private static final class RecordingConnection extends ServerGamePacketListenerImpl {
        private final List<Packet<?>> packets = new ArrayList<>();

        RecordingConnection(ServerPlayer player) {
            super(player.server, new Connection(PacketFlow.SERVERBOUND), player);
        }

        @Override
        public void send(Packet<?> packet, PacketSendListener listener) {
            packets.add(packet);
        }
    }
}
