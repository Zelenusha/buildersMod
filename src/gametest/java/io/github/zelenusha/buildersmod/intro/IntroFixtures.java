package io.github.zelenusha.buildersmod.intro;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

public final class IntroFixtures {
    private IntroFixtures() {}

    public static void setEmployment(ServerPlayer player, BlockPos office, long firstOffersDay) {
        IntroData.Work work = IntroData.get(player.server).work(player.getUUID());
        work.office = office;
        work.dugout = office.west(20);
        work.firstOffersDay = firstOffersDay;
    }
}
