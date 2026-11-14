package io.github.zelenusha.buildersmod.contract;

import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint;
import io.github.zelenusha.buildersmod.blueprint.RoomGenerator;
import io.github.zelenusha.buildersmod.blueprint.SampleRoomBlueprint;
import io.github.zelenusha.buildersmod.intro.IntroEvents;
import io.github.zelenusha.buildersmod.site.ConstructionSite;
import io.github.zelenusha.buildersmod.site.ConstructionSite.SiteDoor;
import io.github.zelenusha.buildersmod.site.SiteCorridorBlueprint;
import io.github.zelenusha.buildersmod.site.SiteCourtyardBlueprint;
import io.github.zelenusha.buildersmod.site.SiteStairBlueprint;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

final class OfferFactory {
    private OfferFactory() { }

    static void fill(ServerPlayer player, ContractData data, ContractData.Account account) {
        ServerLevel site = player.server.getLevel(ConstructionSite.SITE_LEVEL);
        if (site == null) {
            return;
        }
        List<SiteDoor> doors = doors(account);
        List<RoomBlueprint.Bounds> occupied = occupied(account);
        for (int slot = 0; slot < account.offers.length; slot++) {
            if (account.offers[slot] != null || slot >= 3 && account.incidentOffers[slot - 3] != null) {
                continue;
            }
            if (slot >= 3) {
                var incident = IncidentWork.chooseOffer(account, player.getRandom(), slot);
                if (incident != null && (slot == 3 || player.getRandom().nextBoolean())) {
                    account.incidentOffers[slot - 3] = incident;
                    data.setDirty();
                    continue;
                }
            }
            ContractData.Accepted offer = slot == 0 && account.sequence == 0 ? sample(slot) : null;
            if (offer == null) {
                offer = generate(player, site, account, slot, doors, occupied);
            }
            if (offer != null) {
                account.offers[slot] = offer;
                data.setDirty();
            }
        }
    }

    private static ContractData.Accepted sample(int slot) {
        SiteDoor origin = ConstructionSite.STARTER_DOORS.get(0);
        return proposal(origin, ContractBalance.Difficulty.ONE, SampleRoomBlueprint.at(origin.lower(), origin.outward()),
                6, ContractData.Kind.SAMPLE, slot);
    }

    private static ContractData.Accepted generate(ServerPlayer player, ServerLevel site, ContractData.Account account, int slot,
                                                  List<SiteDoor> doors, List<RoomBlueprint.Bounds> occupied) {
        if (doors.isEmpty()) {
            return null;
        }
        UUID owner = player.getUUID();
        RandomSource random = RandomSource.create(site.getSeed() ^ owner.getLeastSignificantBits()
                ^ (long) account.sequence * 4_294_967_311L ^ slot * 71_531L);
        ContractBalance.Difficulty level = ContractBalance.selectDifficulty(random, account.completed);
        Predicate<Block> available = availableMaterials(player, level);
        for (SiteDoor door : doors) {
            if (!hasDoor(site, door)) {
                continue;
            }
            var match = RoomGenerator.generate(random, level.ordinal() + 1, door.lower(), door.outward(), available,
                    generated -> scan(site, generated.blueprint(), occupied, account.highestFloor) != null);
            if (match.isPresent()) {
                var room = match.orElseThrow();
                return proposal(door, level, room.blueprint(), ContractBalance.wage(level, room), ContractData.Kind.ROOM, slot);
            }
        }
        return null;
    }

    private static Predicate<Block> availableMaterials(ServerPlayer player, ContractBalance.Difficulty level) {
        if (level.ordinal() > 1) {
            return block -> MaterialRarity.tier(block) > 0;
        }
        Map<Block, Boolean> evaluated = new HashMap<>();
        return block -> evaluated.computeIfAbsent(block, material -> {
            if (material == Blocks.CHERRY_PLANKS) {
                return IntroEvents.nearbyBiome(player, net.minecraft.world.level.biome.Biomes.CHERRY_GROVE)
                        || IntroEvents.availableSupply(player, net.minecraft.world.item.Items.CHERRY_LOG) > 0
                        || IntroEvents.availableSupply(player, material.asItem()) > 0;
            }
            return MaterialRarity.tier(material) > 0;
        });
    }

    static ContractData.Accepted bind(ServerLevel site, ContractData.Account account, ContractData.Accepted offer) {
        List<RoomBlueprint.Bounds> occupied = occupied(account);
        for (SiteDoor door : doors(account)) {
            if (!hasDoor(site, door)) {
                continue;
            }
            ContractData.Accepted placed = bindFrom(site, account, offer, door, occupied);
            if (placed != null) {
                return placed;
            }
        }
        return null;
    }

    private static ContractData.Accepted bindFrom(ServerLevel site, ContractData.Account account, ContractData.Accepted offer,
                                                   SiteDoor door, List<RoomBlueprint.Bounds> occupied) {
        RoomBlueprint candidate = offer.blueprint().reorient(offer.anchor(), offer.outward(), door.lower(), door.outward());
        Placement placement = scan(site, candidate, occupied, account.highestFloor);
        return placement == null ? null : offer.rebound(door.lower(), door.outward(), placement.blueprint(), placement.originals());
    }

    static ContractData.Accepted extension(ServerLevel site, ContractData.Account account) {
        if (account.special != null) {
            return canResume(site, account.special) ? account.special : null;
        }
        ContractData.Accepted corridor = account.highestFloor > 1 ? corridor(site, account) : null;
        return corridor != null ? corridor : stairs(site, account);
    }

    private static ContractData.Accepted corridor(ServerLevel site, ContractData.Account account) {
        List<RoomBlueprint.Bounds> occupied = occupied(account);
        int topDoorY = ConstructionSite.START_FLOOR_Y + (account.highestFloor - 1) * ConstructionSite.FLOOR_STEP + 1;
        var entrances = doors(account).stream().filter(door -> door.lower().getY() == topDoorY && hasDoor(site, door)).toList();
        var corridor = SiteCorridorBlueprint.createFitting(entrances, blueprint -> {
            if (scan(site, blueprint, occupied, account.highestFloor) == null) {
                return false;
            }
            var withCorridor = new ArrayList<>(occupied);
            withCorridor.add(blueprint.bounds());
            return blueprint.doors().stream().skip(1).anyMatch(exit -> {
                SiteDoor door = new SiteDoor(exit.lowerPosition(), exit.outward());
                for (ContractData.Accepted offer : account.offers) {
                    if (offer != null && bindFrom(site, account, offer, door, withCorridor) != null) {
                        return true;
                    }
                }
                return false;
            });
        });
        if (corridor.isEmpty()) {
            return null;
        }
        RoomBlueprint blueprint = corridor.orElseThrow();
        RoomBlueprint.Door entry = blueprint.doors().get(0);
        Direction outward = entry.outward().getOpposite();
        Placement placement = scan(site, blueprint, occupied, account.highestFloor);
        var level = ContractBalance.Difficulty.ONE;
        return proposal(new SiteDoor(entry.lowerPosition().relative(entry.outward()), outward), level,
                placement.blueprint(), specialWage(level, placement.blueprint()), ContractData.Kind.CORRIDOR, -1);
    }

    static ContractData.Accepted courtyard(ServerLevel site, ContractData.Account account, ContractData.Accepted room) {
        List<RoomBlueprint.Bounds> occupied = occupied(account);
        occupied.add(room.blueprint().bounds());
        var yard = SiteCourtyardBlueprint.createFitting(room.blueprint(), blueprint ->
                scan(site, blueprint, occupied, account.highestFloor) != null);
        if (yard.isEmpty()) {
            return null;
        }
        Placement placement = scan(site, yard.orElseThrow(), occupied, account.highestFloor);
        var gate = placement.blueprint().doors().get(0);
        return proposal(new SiteDoor(gate.lowerPosition(), gate.outward()), room.difficulty(), placement.blueprint(),
                specialWage(room.difficulty(), placement.blueprint()), ContractData.Kind.COURTYARD, account.nextCourtyardSlot);
    }

    static boolean canResume(ServerLevel site, ContractData.Accepted proposal) {
        return proposal.blueprint().requirements().keySet().stream().noneMatch(position -> {
            BlockState state = site.getBlockState(position);
            return state.hasBlockEntity() || state.getDestroySpeed(site, position) < 0 || !state.getFluidState().isEmpty();
        });
    }

    private static int specialWage(ContractBalance.Difficulty level, RoomBlueprint blueprint) {
        return level.emeralds() + ContractBalance.rarityWage(level.emeralds(), blueprint);
    }

    static ContractData.Accepted stairs(ServerLevel site, ContractData.Account account) {
        if (account.highestFloor >= ConstructionSite.FLOOR_COUNT) {
            return null;
        }
        if (account.special != null) {
            return account.special;
        }
        int floor = ConstructionSite.START_FLOOR_Y + (account.highestFloor - 1) * ConstructionSite.FLOOR_STEP;
        var stone = Blocks.STONE.defaultBlockState();
        var brick = Blocks.STONE_BRICKS.defaultBlockState();
        var materials = new SiteStairBlueprint.Materials(Blocks.COBBLESTONE_STAIRS.defaultBlockState(),
                Blocks.COBBLESTONE.defaultBlockState(), stone, brick, brick);
        var level = ContractBalance.Difficulty.TWO;
        return SiteStairBlueprint.createFitting(floor, materials, stone, brick, site::getBlockState)
                .map(blueprint -> proposal(new SiteDoor(new BlockPos(0, floor + 1, 0), Direction.WEST),
                        level, blueprint, specialWage(level, blueprint), ContractData.Kind.STAIRS, -1)).orElse(null);
    }

    private static ContractData.Accepted proposal(SiteDoor door, ContractBalance.Difficulty level, RoomBlueprint blueprint,
                                                   int wage, ContractData.Kind kind, int slot) {
        return new ContractData.Accepted(door.lower(), door.outward(), level, 0, false, blueprint, wage, kind, slot, 0,
                Map.of());
    }

    private static List<SiteDoor> doors(ContractData.Account account) {
        List<SiteDoor> doors = new ArrayList<>(ConstructionSite.STARTER_DOORS);
        for (int floor = 1; floor < account.highestFloor; floor++) {
            doors.addAll(SiteStairBlueprint.upperDoors(ConstructionSite.START_FLOOR_Y + floor * ConstructionSite.FLOOR_STEP));
        }
        for (ContractData.Accepted room : account.rooms) {
            if (room.kind() == ContractData.Kind.ROOM || room.kind() == ContractData.Kind.SAMPLE || room.kind() == ContractData.Kind.CORRIDOR) {
                room.blueprint().doors().stream().skip(1).map(door -> new SiteDoor(door.lowerPosition(), door.outward()))
                        .forEach(doors::add);
            }
        }
        return doors;
    }

    private static List<RoomBlueprint.Bounds> occupied(ContractData.Account account) {
        List<RoomBlueprint.Bounds> occupied = new ArrayList<>();
        account.rooms.stream().filter(order -> order.kind() != ContractData.Kind.STAIRS)
                .map(order -> order.blueprint().bounds()).forEach(occupied::add);
        account.failed.stream().map(work -> work.order().blueprint().bounds()).forEach(occupied::add);
        account.courtyards.stream().map(order -> order.blueprint().bounds()).forEach(occupied::add);
        if (account.pendingCourtyard != null) {
            occupied.add(account.pendingCourtyard.blueprint().bounds());
        }
        if (account.active != null && account.active.kind() != ContractData.Kind.STAIRS) {
            occupied.add(account.active.blueprint().bounds());
        }
        return occupied;
    }

    private static boolean hasDoor(ServerLevel site, SiteDoor door) {
        BlockState lower = site.getBlockState(door.lower());
        BlockState upper = site.getBlockState(door.lower().above());
        if (!(lower.getBlock() instanceof DoorBlock) || !upper.is(lower.getBlock())) {
            return false;
        }
        Direction facing = lower.getValue(DoorBlock.FACING);
        return lower.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                && upper.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER
                && upper.getValue(DoorBlock.FACING) == facing && facing.getAxis() == door.outward().getAxis();
    }

    private static Placement scan(ServerLevel site, RoomBlueprint candidate, List<RoomBlueprint.Bounds> occupied, int floorCount) {
        int lastOpen = ConstructionSite.START_FLOOR_Y + (floorCount - 1) * ConstructionSite.FLOOR_STEP;
        int next = lastOpen + ConstructionSite.FLOOR_STEP;
        if (site.isOutsideBuildHeight(candidate.bounds().min()) || site.isOutsideBuildHeight(candidate.bounds().max())
                || (floorCount < ConstructionSite.FLOOR_COUNT && candidate.bounds().max().getY() >= next)) {
            return null;
        }
        Map<BlockPos, RoomBlueprint.Requirement> requirements = new LinkedHashMap<>(candidate.requirements());
        Map<BlockPos, BlockState> originals = new LinkedHashMap<>();
        for (BlockPos position : candidate.bounds().positions()) {
            if (Math.abs(position.getX()) <= 4 && Math.abs(position.getZ()) <= 4
                    || ConstructionSite.isReservedSiteBlock(position)
                    || occupied.stream().anyMatch(bounds -> bounds.contains(position))) {
                return null;
            }
            BlockState state = site.getBlockState(position);
            RoomBlueprint.Requirement expected = requirements.get(position);
            boolean deck = position.getY() <= lastOpen && position.getY() >= ConstructionSite.START_FLOOR_Y
                    && (position.getY() - ConstructionSite.START_FLOOR_Y) % ConstructionSite.FLOOR_STEP == 0;
            if (state.isAir()) {
                if (deck && position.getY() == candidate.bounds().min().getY()) {
                    originals.put(position, state);
                }
                continue;
            }
            if (state.hasBlockEntity() || state.getDestroySpeed(site, position) < 0 || !state.getFluidState().isEmpty()) {
                return null;
            }
            boolean column = state.is(Blocks.STONE_BRICKS) && ConstructionSite.isOriginalColumnPosition(position);
            if (expected == null) {
                if (candidate.containsInterior(position) || column || deck) {
                    requirements.put(position, new RoomBlueprint.Requirement(Blocks.AIR.defaultBlockState(), RoomBlueprint.Criterion.CLEARANCE));
                    originals.put(position, state);
                }
            } else if (!state.equals(expected.expectedState()) && !expected.replacements().contains(state)) {
                originals.put(position, state);
            }
        }
        return new Placement(RoomBlueprint.of(candidate.bounds(), candidate.interiorVolumes(), requirements, candidate.doors(),
                candidate.bonus()), Map.copyOf(originals));
    }

    private record Placement(RoomBlueprint blueprint, Map<BlockPos, BlockState> originals) { }
}
