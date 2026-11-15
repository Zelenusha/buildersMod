package io.github.zelenusha.buildersmod.contract;

import io.github.zelenusha.buildersmod.BuildersMod;
import io.github.zelenusha.buildersmod.client.ContractClient;
import java.util.UUID;
import java.util.List;
import net.minecraft.core.BlockPos;
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
    public enum Action { SHOW_ORDERS, ACCEPT, SUBMIT, CANCEL, CLAIM, BUY_TIME, SHOW_DEMOLITIONS, DEMOLISH, SHOW_FINES, APPEAL, CARD }
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
                       boolean canBuyTime, int missing, String bonusLabel, int bonusFound, int bonusTarget,
                       List<Material> materials, UUID incidentId, boolean canParallel, String note) {
        void write(FriendlyByteBuf buffer) {
            buffer.writeVarInt(slot).writeUtf(title, 128).writeVarInt(level).writeVarInt(wage).writeVarInt(bonus)
                    .writeVarLong(remaining);
            buffer.writeBoolean(available);
            buffer.writeBoolean(canBuyTime);
            buffer.writeVarInt(missing).writeUtf(bonusLabel, 128).writeVarInt(bonusFound).writeVarInt(bonusTarget);
            buffer.writeCollection(materials, (buf, material) -> material.write(buf));
            buffer.writeNullable(incidentId, FriendlyByteBuf::writeUUID);
            buffer.writeBoolean(canParallel);
            buffer.writeUtf(note, 128);
        }

        static Card read(FriendlyByteBuf buffer) {
            return new Card(buffer.readVarInt(), buffer.readUtf(128), buffer.readVarInt(), buffer.readVarInt(),
                    buffer.readVarInt(), buffer.readVarLong(), buffer.readBoolean(), buffer.readBoolean(),
                    buffer.readVarInt(), buffer.readUtf(128), buffer.readVarInt(), buffer.readVarInt(), buffer.readList(Material::read),
                    buffer.readNullable(FriendlyByteBuf::readUUID), buffer.readBoolean(), buffer.readUtf(128));
        }
    }

    public record Panel(UUID foreman, boolean dialogue, State state, long unpaid, int completed,
                        List<Card> cards, int failed, int fineCount, String feedback, String lastCall, String pauseReason) {
        static void write(Panel value, FriendlyByteBuf buffer) {
            buffer.writeUUID(value.foreman);
            buffer.writeBoolean(value.dialogue);
            buffer.writeEnum(value.state);
            buffer.writeVarLong(value.unpaid).writeVarInt(value.completed).writeVarInt(value.failed).writeVarInt(value.fineCount);
            buffer.writeCollection(value.cards, (buf, card) -> card.write(buf));
            buffer.writeUtf(value.feedback, 256);
            buffer.writeUtf(value.lastCall, 512).writeUtf(value.pauseReason, 128);
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
                    buffer.readUtf(256), buffer.readUtf(512), buffer.readUtf(128));
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

    public record DemolitionCard(UUID id, BlockPos position, List<Material> materials) {
        void write(FriendlyByteBuf buffer) {
            buffer.writeUUID(id).writeBlockPos(position).writeCollection(materials, (buf, entry) -> entry.write(buf));
        }

        static DemolitionCard read(FriendlyByteBuf buffer) {
            return new DemolitionCard(buffer.readUUID(), buffer.readBlockPos(), buffer.readList(Material::read));
        }
    }

    public record DemolitionPanel(UUID foreman, List<DemolitionCard> cards, String feedback) {
        static void write(DemolitionPanel value, FriendlyByteBuf buffer) {
            buffer.writeUUID(value.foreman).writeCollection(value.cards, (buf, entry) -> entry.write(buf));
            buffer.writeUtf(value.feedback, 256);
        }

        static DemolitionPanel read(FriendlyByteBuf buffer) {
            return new DemolitionPanel(buffer.readUUID(), buffer.readList(DemolitionCard::read), buffer.readUtf(256));
        }
    }

    public record FineRow(UUID id, String reason, int amount, int outstanding, int attemptsLeft, boolean overturned) {
        void write(FriendlyByteBuf buf) {
            buf.writeUUID(id).writeUtf(reason, 256).writeVarInt(amount).writeVarInt(outstanding)
                    .writeVarInt(attemptsLeft).writeBoolean(overturned);
        }

        static FineRow read(FriendlyByteBuf buf) {
            return new FineRow(buf.readUUID(), buf.readUtf(256), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean());
        }
    }

    public record AppealView(UUID id, String hint, int selected, int required, long remainingMillis,
                             List<AppealPuzzle.Card> cards) {
        void write(FriendlyByteBuf buf) {
            buf.writeUUID(id).writeUtf(hint, 512).writeVarInt(selected).writeVarInt(required).writeVarLong(remainingMillis);
            buf.writeCollection(cards, (dest, card) -> dest.writeVarInt(card.id()).writeUtf(card.text(), 128));
        }

        static AppealView read(FriendlyByteBuf buf) {
            return new AppealView(buf.readUUID(), buf.readUtf(512), buf.readVarInt(), buf.readVarInt(), buf.readVarLong(),
                    buf.readList(source -> new AppealPuzzle.Card(source.readVarInt(), source.readUtf(128))));
        }
    }

    public record FinePanel(UUID foreman, List<FineRow> fines, AppealView attempt, String feedback) {
        static void write(FinePanel panel, FriendlyByteBuf buf) {
            buf.writeUUID(panel.foreman).writeCollection(panel.fines, (dest, row) -> row.write(dest));
            buf.writeNullable(panel.attempt, (dest, view) -> view.write(dest));
            buf.writeUtf(panel.feedback, 256);
        }

        static FinePanel read(FriendlyByteBuf buf) {
            return new FinePanel(buf.readUUID(), buf.readList(FineRow::read), buf.readNullable(AppealView::read), buf.readUtf(256));
        }
    }

    private record Request(UUID foreman, Action action, int choice, UUID workId) {
        static void write(Request value, FriendlyByteBuf buffer) {
            buffer.writeUUID(value.foreman).writeEnum(value.action).writeVarInt(value.choice);
            buffer.writeNullable(value.workId, FriendlyByteBuf::writeUUID);
        }

        static Request read(FriendlyByteBuf buffer) {
            return new Request(buffer.readUUID(), buffer.readEnum(Action.class), buffer.readVarInt(),
                    buffer.readNullable(FriendlyByteBuf::readUUID));
        }
    }

    private static final String PROTOCOL = "6";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(BuildersMod.MOD_ID, "contracts"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private ContractNetwork() { }

    public static void register() {
        CHANNEL.messageBuilder(Request.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(Request::write).decoder(Request::read)
                .consumerMainThread((request, context) -> {
                    ServerPlayer player = context.get().getSender();
                    if (player != null) {
                        ContractEvents.handle(player, request.foreman, request.action, request.choice, request.workId);
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
        CHANNEL.messageBuilder(DemolitionPanel.class, 4, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(DemolitionPanel::write).decoder(DemolitionPanel::read)
                .consumerMainThread((panel, context) -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> ContractClient.openDemolitions(panel))).add();
        CHANNEL.messageBuilder(FinePanel.class, 5, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(FinePanel::write).decoder(FinePanel::read)
                .consumerMainThread((panel, context) -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> ContractClient.openFines(panel))).add();
        CHANNEL.messageBuilder(TaskView.class, 6, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(TaskView::write).decoder(TaskView::read)
                .consumerMainThread((view, context) -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> ContractClient.updateTasks(view.names(), view.paused()))).add();
        CHANNEL.messageBuilder(Call.class, 7, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((view, buf) -> buf.writeUtf(view.message(), 512)).decoder(buf -> new Call(buf.readUtf(512)))
                .consumerMainThread((view, context) -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> ContractClient.showCall(view.message()))).add();
    }

    static void sendPanel(ServerPlayer player, Panel panel) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), panel);
    }

    static void sendDemolitions(ServerPlayer player, DemolitionPanel panel) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), panel);
    }

    static void sendFines(ServerPlayer player, FinePanel panel) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), panel);
    }

    static void sync(ServerPlayer player, ContractData.Accepted order, long remaining) {
        SiteView view = order == null ? new SiteView(null, null, 0) : new SiteView(order.blueprint().encode(), order.kind().title, remaining);
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), view);
    }

    static void syncTimer(ServerPlayer player, ContractData.Accepted order, long remaining) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new TimerView(order != null, remaining));
    }

    static void tasks(ServerPlayer player, List<String> names, boolean paused) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new TaskView(names, paused));
    }

    static void call(ServerPlayer player, String text) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Call(text));
    }

    private record Call(String message) { }

    private record TaskView(List<String> names, boolean paused) {
        static void write(TaskView view, FriendlyByteBuf buf) {
            buf.writeCollection(view.names, (target, name) -> target.writeUtf(name, 128));
            buf.writeBoolean(view.paused);
        }

        static TaskView read(FriendlyByteBuf buf) {
            return new TaskView(buf.readList(source -> source.readUtf(128)), buf.readBoolean());
        }
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
        CHANNEL.sendToServer(new Request(foreman, action, choice, null));
    }

    public static void deliverDemolition(UUID foreman, UUID workId) {
        request(foreman, Action.DEMOLISH, 0, workId);
    }

    public static void request(UUID foreman, Action action, int choice, UUID workId) {
        CHANNEL.sendToServer(new Request(foreman, action, choice, workId));
    }
}
