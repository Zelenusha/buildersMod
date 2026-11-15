package io.github.zelenusha.buildersmod.contract;

import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint;
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
}
