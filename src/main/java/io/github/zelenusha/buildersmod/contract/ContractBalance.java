package io.github.zelenusha.buildersmod.contract;

import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint;
import io.github.zelenusha.buildersmod.blueprint.RoomGenerator.GeneratedRoom;
import io.github.zelenusha.buildersmod.blueprint.RectangularRoomBlueprint.Pattern;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;

public final class ContractBalance {
    public static final long TICKS_PER_DAY = 24_000;

    public enum Difficulty {
        ONE(3, 5, 2), TWO(5, 10, 3), THREE(8, 18, 4), FOUR(12, 30, 6), FIVE(18, 50, 8);

        private final int days;
        private final int emeralds;
        private final int extraDayPrice;

        Difficulty(int days, int emeralds, int extraDayPrice) {
            this.days = days;
            this.emeralds = emeralds;
            this.extraDayPrice = extraDayPrice;
        }

        public long duration() {
            return days * TICKS_PER_DAY;
        }

        public int emeralds() {
            return emeralds;
        }

        public int extraDayPrice() {
            return extraDayPrice;
        }
    }

    private ContractBalance() { }

    static Difficulty selectDifficulty(RandomSource random, int completed) {
        int[] start = {8_500, 1_200, 250, 45, 5};
        int[] end = {1_000, 1_500, 2_500, 3_000, 2_000};
        int step = Math.min(20, completed / 5);
        int roll = random.nextInt(200_000);
        for (int index = 0; index < start.length; index++) {
            roll -= start[index] * (20 - step) + end[index] * step;
            if (roll < 0) {
                return Difficulty.values()[index];
            }
        }
        throw new IllegalStateException("Difficulty weights do not sum to 200000");
    }

    static int wage(Difficulty difficulty, GeneratedRoom room, RoomBlueprint blueprint) {
        var spec = room.spec();
        int wage = difficulty.emeralds() + room.sizeCategory().surcharge();
        wage += switch (spec.heights().size()) { case 3 -> 18; case 2 -> 8; default -> 0; };
        wage += patternWage(spec.floor().pattern()) + patternWage(spec.walls().pattern()) + patternWage(spec.ceiling().pattern());
        wage += windowWage(blueprint);
        for (var fixture : spec.furniture()) {
            wage += fixture.state().is(Blocks.WHITE_BED) ? 2 : 1;
        }
        return wage + rarityWage(difficulty.emeralds(), blueprint);
    }

    static int windowWage(RoomBlueprint blueprint) {
        int wage = 0;
        for (var window : blueprint.windows()) {
            int size = switch (window.width()) { case 5 -> 4; case 3 -> 2; case 2 -> 1; default -> 0; };
            int destination = switch (window.destination()) {
                case COMMON_HALL -> 3;
                case INTERIOR -> 2;
                case STREET, COURTYARD, UNSPECIFIED -> 1;
            };
            wage += size + destination;
        }
        return wage;
    }

    private static int patternWage(Pattern pattern) {
        return switch (pattern) { case NONE -> 0; case CHECKERBOARD -> 3; default -> 2; };
    }

    static int rarityWage(int base, RoomBlueprint blueprint) {
        int[] counts = new int[6];
        int total = 0;
        for (var requirement : blueprint.requirements().values()) {
            if (!requirement.clearance()) {
                total++;
                counts[MaterialRarity.tier(requirement.expectedState().getBlock())]++;
            }
        }
        int[] rate = {0, 0, 2, 4, 7, 12};
        int bonus = 0;
        for (int tier = 1; tier < rate.length && total > 0; tier++) {
            bonus += 10 * counts[tier] / total * rate[tier];
        }
        return Math.min(base / 2, bonus);
    }

    public static int earlyBonus(long remainingTicks, boolean boughtExtraDay) {
        return remainingTicks > 0 && !boughtExtraDay ? (int) ((remainingTicks - 1) / TICKS_PER_DAY + 1) : 0;
    }

    public static int incompleteFine(int wrongPositions, int failedCriteria) {
        return (wrongPositions + 9) / 10 + 2 * failedCriteria;
    }
}
