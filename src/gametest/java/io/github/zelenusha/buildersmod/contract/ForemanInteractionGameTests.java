package io.github.zelenusha.buildersmod.contract;

import com.mojang.authlib.GameProfile;
import io.github.zelenusha.buildersmod.BuildersMod;
import io.github.zelenusha.buildersmod.blueprint.SampleRoomBlueprint;
import io.github.zelenusha.buildersmod.intro.IntroData;
import io.github.zelenusha.buildersmod.intro.IntroFixtures;
import io.github.zelenusha.buildersmod.site.ConstructionSite;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(BuildersMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ForemanInteractionGameTests {
    private static final ResourceLocation CONTRACTS = ResourceLocation.fromNamespaceAndPath(BuildersMod.MOD_ID, "contracts");

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void ownedForemanOpensOrdersAndHandlesPlayPackets(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        ServerLevel overworld = server.overworld();
        BlockPos office = helper.absolutePos(new BlockPos(1, 1, 1));
        var player = new ServerPlayer(server, overworld, new GameProfile(UUID.randomUUID(), "foreman-packets"));
        var connection = new Connection(PacketFlow.SERVERBOUND);
        var channel = new EmbeddedChannel(connection);
        connection.setProtocol(ConnectionProtocol.PLAY);
        new ServerGamePacketListenerImpl(server, connection, player);

        var storage = overworld.getDataStorage();
        var previousIntro = IntroData.get(server);
        var previousContracts = ContractData.get(server);
        var contracts = new ContractData();
        storage.set("buildersmod_intro", new IntroData());
        storage.set("buildersmod_contracts", contracts);
        Villager own = null;
        Villager other = null;
        try {
            long day = Math.floorDiv(overworld.getDayTime(), ContractBalance.TICKS_PER_DAY);
            IntroFixtures.setEmployment(player, office, day);
            player.moveTo(office.getX() + 0.5, office.getY(), office.getZ() + 0.5, 0, 0);
            overworld.addNewPlayer(player);
            own = foreman(overworld, office.east(), player.getUUID());
            other = foreman(overworld, office.south(), UUID.randomUUID());
            helper.assertTrue(overworld.getEntity(own.getUUID()) == own && overworld.getEntity(other.getUUID()) == other,
                    "Both villagers are available to vanilla interaction lookup in the Overworld");

            channel.writeInbound(ServerboundInteractPacket.createInteractionPacket(other, false, InteractionHand.MAIN_HAND));
            helper.assertTrue(readPanel(channel) == null, "Interaction with a different owner's foreman opens no contract panel");
            channel.writeInbound(ServerboundInteractPacket.createInteractionPacket(own, false, InteractionHand.MAIN_HAND));
            var dialogue = readPanel(channel);
            helper.assertTrue(dialogue != null && dialogue.foreman().equals(own.getUUID()) && dialogue.dialogue(),
                    "Normal main-hand interaction sends this foreman's dialogue packet");

            var account = contracts.account(player.getUUID());
            var start = ConstructionSite.STARTER_DOORS.get(0);
            var room = SampleRoomBlueprint.at(start.lower(), start.outward());
            for (int slot = 0; slot < account.offers.length; slot++) {
                account.offers[slot] = new ContractData.Accepted(start.lower(), start.outward(), ContractBalance.Difficulty.ONE, 0,
                        false, room, 6 + slot, ContractData.Kind.SAMPLE, slot, 0, Map.of());
            }
            sendRequest(channel, own.getUUID(), ContractNetwork.Action.SHOW_ORDERS, 0);
            var orders = readPanel(channel);
            helper.assertTrue(orders != null && !orders.dialogue() && orders.state() == ContractNetwork.State.AVAILABLE
                    && orders.cards().size() == 5, "The Forge play request opens all five seeded order cards");
            var sample = orders.cards().get(0);
            helper.assertTrue(sample.slot() == 0 && sample.wage() == 6 && sample.bonus() == 5 && sample.bonusTarget() == 3,
                    "The real reply carries the basic wage and separate bonus for the selected card");

            sendRequest(channel, own.getUUID(), ContractNetwork.Action.ACCEPT, sample.slot());
            var accepted = readPanel(channel);
            helper.assertTrue(accepted != null && accepted.state() == ContractNetwork.State.ACTIVE && account.active != null
                    && account.active.slot() == sample.slot(), "Choosing the card via a play packet activates that specific order");

            verifySubmissionAndCollection(helper, channel, player, own, other, account);
        } finally {
            if (own != null) {
                own.discard();
            }
            if (other != null) {
                other.discard();
            }
            overworld.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
            channel.finishAndReleaseAll();
            storage.set("buildersmod_intro", previousIntro);
            storage.set("buildersmod_contracts", previousContracts);
        }
        helper.succeed();
    }

    private static void verifySubmissionAndCollection(GameTestHelper helper, EmbeddedChannel channel, ServerPlayer player,
                                                      Villager own, Villager other, ContractData.Account account) {
        UUID foreman = own.getUUID();
        sendRequest(channel, foreman, ContractNetwork.Action.SUBMIT, 0);
        var rejected = readPanel(channel);
        helper.assertTrue(rejected != null && rejected.state() == ContractNetwork.State.ACTIVE && !rejected.feedback().isEmpty(),
                "An incomplete room leaves the order active and sends the foreman's refusal");
        var blueprint = account.active.blueprint();
        ServerLevel site = player.server.getLevel(ConstructionSite.SITE_LEVEL);
        Map<BlockPos, BlockState> original = new HashMap<>();
        final int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        try {
            blueprint.requirements().forEach((pos, requirement) -> {
                original.put(pos, site.getBlockState(pos));
                site.setBlock(pos, requirement.expectedState(), flags);
            });
            BlockPos first = blueprint.interior().min().east().south();
            for (BlockPos pos : new BlockPos[] {first, first.east(), first.south()}) {
                original.putIfAbsent(pos, site.getBlockState(pos));
                site.setBlock(pos, Blocks.CAKE.defaultBlockState(), flags);
            }
            player.getRandom().setSeed(1L);
            sendRequest(channel, foreman, ContractNetwork.Action.SUBMIT, 0);
            var submitted = readPanel(channel);
            helper.assertTrue(submitted != null && account.active == null && account.completed == 1 && account.pendingWage == 14,
                    "The owned foreman accepts the complete room and credits the exact gross wage");
            sendRequest(channel, other.getUUID(), ContractNetwork.Action.CLAIM, 0);
            helper.assertTrue(readPanel(channel) == null && account.pendingWage == 14,
                    "A different owner's foreman cannot settle wages");
            sendRequest(channel, foreman, ContractNetwork.Action.CLAIM, 0);
            var paid = readPanel(channel);
            helper.assertTrue(paid != null && paid.unpaid() == 0 && player.getInventory().countItem(Items.EMERALD) == 14,
                    "The owned foreman delivers wages and sends the updated balance");
        } finally {
            original.forEach((pos, state) -> site.setBlock(pos, state, flags));
        }
    }

    private static Villager foreman(ServerLevel level, BlockPos pos, UUID owner) {
        Villager villager = EntityType.VILLAGER.create(level);
        villager.addTag("buildersmod_foreman");
        villager.getPersistentData().putUUID("buildersmod_intro_owner", owner);
        villager.setNoAi(true);
        villager.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
        level.addFreshEntity(villager);
        return villager;
    }

    private static void sendRequest(EmbeddedChannel channel, UUID foreman, ContractNetwork.Action action, int choice) {
        var data = new FriendlyByteBuf(Unpooled.buffer());
        data.writeByte(0);
        data.writeUUID(foreman).writeEnum(action).writeVarInt(choice).writeBoolean(false);
        channel.writeInbound(new ServerboundCustomPayloadPacket(CONTRACTS, data));
    }

    private static ContractNetwork.Panel readPanel(EmbeddedChannel channel) {
        ContractNetwork.Panel panel = null;
        Object packet;
        while ((packet = channel.readOutbound()) != null) {
            if (packet instanceof ClientboundCustomPayloadPacket payload && payload.getIdentifier().equals(CONTRACTS)) {
                FriendlyByteBuf data = payload.getData();
                try {
                    if (data.readUnsignedByte() == 1) {
                        panel = ContractNetwork.Panel.read(data);
                    }
                } finally {
                    data.release();
                }
            }
        }
        return panel;
    }
}
