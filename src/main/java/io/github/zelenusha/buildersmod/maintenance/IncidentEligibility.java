package io.github.zelenusha.buildersmod.maintenance;

import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint;
import io.github.zelenusha.buildersmod.maintenance.IncidentState.Kind;
import java.util.EnumSet;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;

public final class IncidentEligibility {
    private IncidentEligibility() { }

    public static Set<Kind> kinds(ServerLevel level, RoomBlueprint room, boolean hasSurvivingEviction) {
        if (!IncidentWorld.loaded(level, room.bounds())) {
            return Set.of();
        }
        EnumSet<Kind> kinds = EnumSet.noneOf(Kind.class);
        if (IncidentWorld.graffitiTarget(level, room).isPresent()) {
            kinds.add(Kind.CLEANUP);
        }
        if (hasSurvivingEviction || !IncidentWorld.residentTargets(level, room.bounds().min()).isEmpty()
                || !IncidentWorld.evictionCandidates(level, room).isEmpty()) {
            kinds.add(Kind.EVICTION);
        }
        if (IncidentWorld.fireOrigin(level, room).isPresent()) {
            kinds.add(Kind.FIRE);
        }
        if (IncidentWorld.roomHasNoWater(level, room) && !IncidentWorld.interiorCandidates(level, room).isEmpty()) {
            kinds.add(Kind.FLOOD);
        }
        return kinds;
    }
}
