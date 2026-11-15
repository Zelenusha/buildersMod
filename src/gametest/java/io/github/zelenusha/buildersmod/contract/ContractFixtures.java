package io.github.zelenusha.buildersmod.contract;

import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint;
import io.github.zelenusha.buildersmod.maintenance.IncidentState;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/** Read access to a player's contract account for scripted runs in other packages. */
public final class ContractFixtures {
    private ContractFixtures() { }

    public static ContractData.Account account(ServerPlayer player) {
        return ContractData.get(player.server).account(player.getUUID());
    }

    public static RoomBlueprint activeBlueprint(ServerPlayer player) {
        ContractData.Accepted order = account(player).active;
        return order == null ? null : order.blueprint();
    }

    public static long unpaid(ServerPlayer player) {
        return account(player).unpaid;
    }

    public static int completed(ServerPlayer player) {
        return account(player).completed;
    }

    public static int fines(ServerPlayer player) {
        return account(player).fines.entries().size();
    }

    public static int demolitions(ServerPlayer player) {
        return account(player).failed.size();
    }

    public static RoomBlueprint firstRoom(ServerPlayer player) {
        return account(player).rooms.get(0).blueprint();
    }

    /** Puts a voluntary level 1 job for the first finished room into the fourth place of the foreman's list. */
    public static void offerIncident(ServerPlayer player, IncidentState.Kind kind) {
        ContractData.Account account = account(player);
        account.offers[3] = null;
        account.incidentOffers[0] = new IncidentWork.Offer(UUID.randomUUID(), firstRoom(player).bounds().min(), kind,
                ContractBalance.Difficulty.ONE, 3);
    }

    /** Queues a level 1 event for the first finished room as the weekly roll would. */
    public static void queueIncident(ServerPlayer player, IncidentState.Kind kind) {
        account(player).pendingIncident = new IncidentWork.Offer(UUID.randomUUID(), firstRoom(player).bounds().min(), kind,
                ContractBalance.Difficulty.ONE, -1);
    }

    public static IncidentState incident(ServerPlayer player, IncidentState.Kind kind) {
        return account(player).incidents.stream().filter(work -> work.kind() == kind).findFirst().orElse(null);
    }

    public static boolean paused(ServerPlayer player) {
        return account(player).pausedAt >= 0;
    }

    public static String lastCall(ServerPlayer player) {
        return account(player).lastCall;
    }
}
