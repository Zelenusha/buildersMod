package io.github.zelenusha.buildersmod.contract;

import io.github.zelenusha.buildersmod.BuildersMod;
import io.github.zelenusha.buildersmod.client.ContractClient;
import java.util.UUID;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class ContractNetwork {
    public enum Action { SHOW_ORDERS, ACCEPT, SUBMIT, CANCEL, CLAIM, BUY_TIME, DEMOLISH, APPEAL, CARD }
    public enum State { NOT_READY, AVAILABLE, ACTIVE, NO_SPACE }

    public record Material(String label, String substitutes, int placed, int needed) {
        void write(FriendlyByteBuf buffer) {
            buffer.writeUtf(label, 256).writeUtf(substitutes, 256).writeVarInt(placed).writeVarInt(needed);
        }

        static Material read(FriendlyByteBuf buffer) {
            return new Material(buffer.readUtf(256), buffer.readUtf(256), buffer.readVarInt(), buffer.readVarInt());
        }
    }

    public record Card(int slot, String title, int level, int wage, int bonus, long remaining, boolean available,
                       boolean extraDay, int missing, String bonusLabel, int bonusFound, int bonusTarget,
                       List<Material> materials) {
        void write(FriendlyByteBuf buffer) {
            buffer.writeVarInt(slot).writeUtf(title, 128).writeVarInt(level).writeVarInt(wage).writeVarInt(bonus)
                    .writeVarLong(remaining);
            buffer.writeBoolean(available);
            buffer.writeBoolean(extraDay);
            buffer.writeVarInt(missing).writeUtf(bonusLabel, 128).writeVarInt(bonusFound).writeVarInt(bonusTarget);
            buffer.writeCollection(materials, (buf, material) -> material.write(buf));
        }

        static Card read(FriendlyByteBuf buffer) {
            return new Card(buffer.readVarInt(), buffer.readUtf(128), buffer.readVarInt(), buffer.readVarInt(),
                    buffer.readVarInt(), buffer.readVarLong(), buffer.readBoolean(), buffer.readBoolean(),
                    buffer.readVarInt(), buffer.readUtf(128), buffer.readVarInt(), buffer.readVarInt(), buffer.readList(Material::read));
        }
    }

    public record Panel(UUID foreman, boolean dialogue, State state, long unpaid, int completed,
                        List<Card> cards, int failed, int fineCount, String feedback) {
        static void write(Panel value, FriendlyByteBuf buffer) {
            buffer.writeUUID(value.foreman);
            buffer.writeBoolean(value.dialogue);
            buffer.writeEnum(value.state);
            buffer.writeVarLong(value.unpaid).writeVarInt(value.completed).writeVarInt(value.failed).writeVarInt(value.fineCount);
            buffer.writeCollection(value.cards, (buf, card) -> card.write(buf));
            buffer.writeUtf(value.feedback, 256);
        }

        static Panel read(FriendlyByteBuf buffer) {
            UUID foreman = buffer.readUUID();
            boolean dialogue = buffer.readBoolean();
            State state = buffer.readEnum(State.class);
            long unpaid = buffer.readVarLong();
            int completed = buffer.readVarInt();
            int failed = buffer.readVarInt();
            int fineCount = buffer.readVarInt();
            return new Panel(foreman, dialogue, state, unpaid, completed, buffer.readList(Card::read), failed, fineCount,
                    buffer.readUtf(256));
        }
    }

    public record SiteView(CompoundTag blueprint, String title, long remaining) {
        static void write(SiteView value, FriendlyByteBuf buffer) {
            buffer.writeBoolean(value.blueprint != null);
            if (value.blueprint != null) {
                buffer.writeNbt(value.blueprint);
                buffer.writeUtf(value.title, 128).writeVarLong(value.remaining);
            }
        }

        static SiteView read(FriendlyByteBuf buffer) {
            return buffer.readBoolean()
                    ? new SiteView(buffer.readNbt(), buffer.readUtf(128), buffer.readVarLong())
                    : new SiteView(null, null, 0);
        }
    }

    private record Request(UUID foreman, Action action, int choice) {
        static void write(Request value, FriendlyByteBuf buffer) {
            buffer.writeUUID(value.foreman).writeEnum(value.action).writeVarInt(value.choice);
        }

        static Request read(FriendlyByteBuf buffer) {
            return new Request(buffer.readUUID(), buffer.readEnum(Action.class), buffer.readVarInt());
        }
    }

    private static final String PROTOCOL = "2";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(BuildersMod.MOD_ID, "contracts"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private ContractNetwork() { }

    public static void register() {
        CHANNEL.messageBuilder(Request.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(Request::write).decoder(Request::read)
                .consumerMainThread((request, context) -> {
                    ServerPlayer player = context.get().getSender();
                    if (player != null) {
                        ContractEvents.handle(player, request.foreman, request.action, request.choice);
                    }
                }).add();
        CHANNEL.messageBuilder(Panel.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Panel::write).decoder(Panel::read)
                .consumerMainThread((panel, context) -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> ContractClient.open(panel))).add();
        CHANNEL.messageBuilder(SiteView.class, 2, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SiteView::write).decoder(SiteView::read)
                .consumerMainThread((view, context) -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> ContractClient.updateSite(view))).add();
        CHANNEL.messageBuilder(TimerView.class, 3, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(TimerView::write).decoder(TimerView::read)
                .consumerMainThread((view, context) -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> ContractClient.updateTimer(view.active, view.remaining))).add();
    }

    static void sendPanel(ServerPlayer player, Panel panel) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), panel);
    }

    static void sync(ServerPlayer player, ContractData.Accepted order, long remaining) {
        SiteView view = order == null ? new SiteView(null, null, 0) : new SiteView(order.blueprint().encode(), order.kind().title, remaining);
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), view);
    }

    static void syncTimer(ServerPlayer player, ContractData.Accepted order, long remaining) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new TimerView(order != null, remaining));
    }

    private record TimerView(boolean active, long remaining) {
        static void write(TimerView value, FriendlyByteBuf buffer) {
            buffer.writeBoolean(value.active);
            buffer.writeVarLong(value.remaining);
        }

        static TimerView read(FriendlyByteBuf buffer) {
            return new TimerView(buffer.readBoolean(), buffer.readVarLong());
        }
    }

    public static void request(UUID foreman, Action action, int choice) {
        CHANNEL.sendToServer(new Request(foreman, action, choice));
    }
}
