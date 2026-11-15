package io.github.zelenusha.buildersmod.contract;

import com.mojang.authlib.GameProfile;
import io.github.zelenusha.buildersmod.BuildersMod;
import io.github.zelenusha.buildersmod.maintenance.GraffitiEvents;
import io.github.zelenusha.buildersmod.maintenance.GraffitiPattern;
import io.github.zelenusha.buildersmod.maintenance.IncidentState;
import io.github.zelenusha.buildersmod.maintenance.IncidentWorld.GraffitiTarget;
import io.github.zelenusha.buildersmod.maintenance.WorkItems;
import io.github.zelenusha.buildersmod.site.ConstructionSite;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.BitSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(BuildersMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GraffitiInteractionGameTests {
    private static final ResourceLocation GRAFFITI = ResourceLocation.fromNamespaceAndPath(BuildersMod.MOD_ID, "graffiti");
    private static final int OPEN = 1;
    private static final int PROGRESS = 2;
    private static final int STROKE = 3;
    private static final int CLOSE = 4;

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void wetRagPreservesPartialDrawingAndRemovesItAfterResumedScrubbing(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        ServerLevel site = server.getLevel(ConstructionSite.SITE_LEVEL);
        helper.assertTrue(site != null, "The construction dimension must be available");
        BlockPos wall = new BlockPos(274, ConstructionSite.START_FLOOR_Y + 1, 274);
        site.getChunkAt(wall);
        var backing = site.getBlockState(wall);
        var player = new ServerPlayer(server, site, new GameProfile(UUID.randomUUID(), "graffiti-packets"));
        var connection = new Connection(PacketFlow.SERVERBOUND);
        var channel = new EmbeddedChannel(connection);
        connection.setProtocol(ConnectionProtocol.PLAY);
        new ServerGamePacketListenerImpl(server, connection, player);
        var storage = server.overworld().getDataStorage();
        var originalContracts = ContractData.get(server);
        var contracts = new ContractData();
        storage.set("buildersmod_contracts", contracts);
        UUID incident = UUID.randomUUID();
        UUID replacement = UUID.randomUUID();
        var target = new GraffitiTarget(wall, Direction.EAST);
        try {
            player.setGameMode(GameType.SURVIVAL);
            player.moveTo(wall.getX() + 2.5, wall.getY(), wall.getZ() + 0.5, 90F, 0F);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(WorkItems.WET_RAG.get()));
            site.setBlock(wall, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            site.addNewPlayer(player);
            var cleanup = IncidentState.cleanup(incident, ContractBalance.Difficulty.ONE, false, wall,
                    List.of(), GraffitiPattern.PATCH_COUNT, 1L);
            contracts.account(player.getUUID()).incidents.add(cleanup);
            helper.assertTrue(GraffitiEvents.place(player, site, incident, target), "The cleanup starts with a graffiti marker");

            useRag(channel, target, 0);
            Reply first = readReply(channel, OPEN);
            helper.assertTrue(first != null && first.erased().isEmpty() && damage(player) == 1,
                    "The first right click opens the whole drawing and consumes one of eight rag uses");
            useRag(channel, target, 1);
            helper.assertTrue(readReply(channel, OPEN) == null && damage(player) == 1,
                    "Repeated interactions during the same attempt consume no extra rag use");
            stroke(channel, first.token(), true, 12, 12);
            Reply partial = readReply(channel, PROGRESS);
            helper.assertTrue(partial != null && !partial.finished() && !partial.erased().isEmpty()
                    && partial.erased().cardinality() < GraffitiPattern.PATCH_COUNT
                    && cleanup.erasedPatches().equals(partial.erased()), "A stroke updates only part of the saved drawing");
            closeAttempt(channel, first.token());

            var restored = ContractData.load(contracts.save(new CompoundTag()));
            storage.set("buildersmod_contracts", restored);
            var persistedCleanup = restored.account(player.getUUID()).incidents.get(0);
            useRag(channel, target, 2);
            Reply reopened = readReply(channel, OPEN);
            helper.assertTrue(reopened != null && !reopened.token().equals(first.token())
                    && reopened.erased().equals(partial.erased()) && damage(player) == 2,
                    "A new attempt consumes another use and resumes the saved partial drawing");
            stroke(channel, first.token(), true, 100, 50);
            helper.assertTrue(readReply(channel, PROGRESS) == null && persistedCleanup.erasedPatches().equals(partial.erased()),
                    "An old attempt token cannot erase new patches");

            for (int row = 1; row < GraffitiPattern.HEIGHT; row += 3) {
                int y = row * GraffitiPattern.CELL + GraffitiPattern.CELL / 2;
                stroke(channel, reopened.token(), true, 0, y);
                stroke(channel, reopened.token(), false, GraffitiPattern.CANVAS_WIDTH - 1, y);
            }
            Reply clean = readReply(channel, PROGRESS);
            helper.assertTrue(clean != null && clean.finished() && clean.erased().cardinality() == GraffitiPattern.PATCH_COUNT
                    && persistedCleanup.erasedPatches().equals(clean.erased()) && damage(player) == 2,
                    "The final stroke removes the drawing and spends no extra rag uses");
            helper.assertTrue(GraffitiEvents.place(player, site, replacement, target),
                    "A new drawing can now occupy this exact surface, proving the original marker was removed");
        } finally {
            GraffitiEvents.remove(site, incident);
            GraffitiEvents.remove(site, replacement);
            site.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
            site.setBlock(wall, backing, Block.UPDATE_CLIENTS);
            channel.finishAndReleaseAll();
            storage.set("buildersmod_contracts", originalContracts);
        }
        helper.succeed();
    }

    private record Reply(UUID token, BitSet erased, boolean finished) {}

    private static void useRag(EmbeddedChannel channel, GraffitiTarget target, int sequence) {
        Vec3 faceCenter = Vec3.atCenterOf(target.pos()).add(0.5, 0, 0);
        var hit = new BlockHitResult(faceCenter, target.face(), target.pos(), false);
        channel.writeInbound(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, sequence));
    }

    private static void stroke(EmbeddedChannel channel, UUID token, boolean begin, int x, int y) {
        FriendlyByteBuf data = new FriendlyByteBuf(Unpooled.buffer());
        data.writeByte(STROKE);
        data.writeUUID(token).writeBoolean(begin).writeByte(x).writeByte(y);
        channel.writeInbound(new ServerboundCustomPayloadPacket(GRAFFITI, data));
    }

    private static void closeAttempt(EmbeddedChannel channel, UUID token) {
        FriendlyByteBuf data = new FriendlyByteBuf(Unpooled.buffer());
        data.writeByte(CLOSE);
        data.writeUUID(token);
        channel.writeInbound(new ServerboundCustomPayloadPacket(GRAFFITI, data));
    }

    private static Reply readReply(EmbeddedChannel channel, int discriminator) {
        Reply matched = null;
        Object packet;
        while ((packet = channel.readOutbound()) != null) {
            if (packet instanceof ClientboundCustomPayloadPacket payload) {
                FriendlyByteBuf data = payload.getData();
                try {
                    if (!payload.getIdentifier().equals(GRAFFITI) || data.readUnsignedByte() != discriminator) {
                        continue;
                    }
                    UUID token = data.readUUID();
                    if (discriminator == PROGRESS) {
                        data.readUUID();
                    }
                    BitSet erased = BitSet.valueOf(data.readLongArray());
                    matched = new Reply(token, erased, discriminator == PROGRESS && data.readBoolean());
                } finally {
                    data.release();
                }
            }
        }
        return matched;
    }

    private static int damage(ServerPlayer player) {
        return player.getItemInHand(InteractionHand.MAIN_HAND).getDamageValue();
    }
}
