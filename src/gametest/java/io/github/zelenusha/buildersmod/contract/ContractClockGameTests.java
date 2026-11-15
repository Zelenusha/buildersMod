package io.github.zelenusha.buildersmod.contract;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("buildersmod")
@PrefixGameTestTemplate(false)
public final class ContractClockGameTests {
    @GameTest(template = "empty")
    public static void advancesOnlyForTicksAndTimeSkippedBySleep(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var overworld = server.overworld();
        var storage = overworld.getDataStorage();
        var original = ContractData.get(server);
        Map<ServerLevel, Long> dayTimes = new LinkedHashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            dayTimes.put(level, level.getDayTime());
        }
        var data = new ContractData();
        storage.set("buildersmod_contracts", data);
        try {
            overworld.setDayTime(1_000);
            long initialClock = data.time(server);
            var commands = server.getCommands();
            var source = server.createCommandSourceStack().withPermission(4).withSuppressedOutput();
            commands.performPrefixedCommand(source, "time set 45000");
            helper.assertTrue(overworld.getDayTime() == 45_000 && data.time(server) == initialClock,
                    "A forward time command changes the daylight clock only");
            commands.performPrefixedCommand(source, "time set 23000");
            helper.assertTrue(overworld.getDayTime() == 23_000 && data.time(server) == initialClock,
                    "A backward time command leaves the contract clock unchanged as well");

            long morning = ForgeEventFactory.onSleepFinished(overworld, 24_000, overworld.getDayTime());
            overworld.setDayTime(morning);
            helper.assertTrue(morning == 24_000 && data.time(server) == initialClock + 1_000,
                    "Sleeping into the next morning advances contracts by the time skipped");

            long earlier = ForgeEventFactory.onSleepFinished(overworld, 23_500, overworld.getDayTime());
            overworld.setDayTime(earlier);
            helper.assertTrue(data.time(server) == initialClock + 1_000, "A negative skip cannot rewind the contract clock");
            var restored = ContractData.load(data.save(new CompoundTag()));
            helper.assertTrue(restored.time(server) == initialClock + 1_000, "The sleep offset survives saving and loading");
        } finally {
            storage.set("buildersmod_contracts", original);
            dayTimes.forEach(ServerLevel::setDayTime);
        }
        helper.succeed();
    }
}
