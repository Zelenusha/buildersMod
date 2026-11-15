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
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraftforge.common.capabilities.ForgeCapabilities;

final class OfferFactory {
    private static final Set<ResourceKey<Biome>> SAND_BIOMES = Set.of(Biomes.DESERT, Biomes.BEACH, Biomes.SNOWY_BEACH,
            Biomes.RIVER, Biomes.FROZEN_RIVER, Biomes.WARM_OCEAN, Biomes.LUKEWARM_OCEAN, Biomes.DEEP_LUKEWARM_OCEAN);
    private static final Set<ResourceKey<Biome>> MONUMENT_BIOMES = Set.of(Biomes.DEEP_OCEAN, Biomes.DEEP_COLD_OCEAN,
            Biomes.DEEP_FROZEN_OCEAN, Biomes.DEEP_LUKEWARM_OCEAN);
    private static final Set<Block> GLAZING = Set.of(Blocks.GLASS, Blocks.GLASS_PANE, Blocks.WHITE_STAINED_GLASS,
            Blocks.WHITE_STAINED_GLASS_PANE, Blocks.BLUE_STAINED_GLASS, Blocks.BLUE_STAINED_GLASS_PANE);

    private OfferFactory() { }

    static void fill(ServerPlayer player, ContractData data, ContractData.Account account) {
        ServerLevel site = player.server.getLevel(ConstructionSite.SITE_LEVEL);
        if (site == null) {
            return;
        }
        List<SiteDoor> doors = doors(account);
        List<RoomBlueprint.Bounds> occupied = occupied(account);
        var localMaterials = com.google.common.base.Suppliers.memoize(() -> availableMaterials(player));
        for (int slot = 0; slot < account.offers.length; slot++) {
            if (account.offers[slot] != null || slot >= 3 && account.incidentOffers[slot - 3] != null) {
                continue;
            }
            if (slot >= 3) {
                var incident = IncidentWork.chooseManual(site, account, player.getRandom(), slot);
                if (incident != null && (slot == 3 || player.getRandom().nextBoolean())) {
                    account.incidentOffers[slot - 3] = incident;
                    data.setDirty();
                    continue;
                }
            }
            ContractData.Accepted offer = slot == 0 && account.sequence == 0 ? sample(slot) : null;
            if (offer == null) {
                offer = generate(player, site, account, slot, doors, occupied, localMaterials);
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
                                                  List<SiteDoor> doors, List<RoomBlueprint.Bounds> occupied,
                                                  Supplier<Predicate<Block>> localMaterials) {
        if (doors.isEmpty()) {
            return null;
        }
        UUID owner = player.getUUID();
        RandomSource random = RandomSource.create(site.getSeed() ^ owner.getLeastSignificantBits()
                ^ (long) account.sequence * 4_294_967_311L ^ slot * 71_531L);
        ContractBalance.Difficulty level = ContractBalance.selectDifficulty(random, account.completed);
        Predicate<Block> available = level.ordinal() > 1 ? block -> MaterialRarity.tier(block) > 0 : localMaterials.get();
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

    private static Predicate<Block> availableMaterials(ServerPlayer player) {
        var sources = new java.util.HashSet<>(SAND_BIOMES);
        sources.addAll(MONUMENT_BIOMES);
        sources.add(Biomes.CHERRY_GROVE);
        Set<ResourceKey<Biome>> nearby = IntroEvents.nearbyBiomes(player, sources);
        Map<Item, Boolean> stocks = new HashMap<>();
        return material -> availableLocally(material, nearby, item -> stocks.computeIfAbsent(item,
                product -> IntroEvents.availableSupply(player, product) > 0));
    }

    static boolean availableLocally(Block block, Set<ResourceKey<Biome>> nearby, Predicate<Item> stocked) {
        if (MaterialRarity.tier(block) == 0) {
            return false;
        }
        if (block == Blocks.CHERRY_PLANKS) {
            return nearby.contains(Biomes.CHERRY_GROVE) || stocked.test(Items.CHERRY_LOG) || stocked.test(Items.CHERRY_PLANKS);
        }
        if (block == Blocks.SANDSTONE || GLAZING.contains(block)) {
            return SAND_BIOMES.stream().anyMatch(nearby::contains) || stocked.test(Items.SAND) || stocked.test(block.asItem());
        }
        if (block == Blocks.PRISMARINE) {
            return MONUMENT_BIOMES.stream().anyMatch(nearby::contains)
                    || stocked.test(Items.PRISMARINE_SHARD) || stocked.test(Items.PRISMARINE);
        }
        return true;
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
        for (BlockPos position : proposal.blueprint().bounds().positions()) {
            if (obstructsPlacement(site, position, site.getBlockState(position))) {
                return false;
            }
        }
        return true;
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
        var lowerRoom = account.rooms.stream()
                .filter(order -> order.kind() == ContractData.Kind.STAIRS
                        && order.blueprint().interior().min().getY() == floor + 1)
                .findFirst()
                .flatMap(order -> SiteStairBlueprint.lowerRoomMaterials(order.blueprint(), floor))
                .orElseGet(() -> new SiteStairBlueprint.LowerRoomMaterials(Blocks.STONE.defaultBlockState(),
                        Blocks.STONE_BRICKS.defaultBlockState()));
        var materials = SiteStairBlueprint.selectMaterials(RandomSource.create(site.getSeed() ^ (long) floor * 71_531L));
        var level = ContractBalance.Difficulty.TWO;
        return SiteStairBlueprint.createFitting(floor, materials, lowerRoom.floor(), lowerRoom.ceiling(), site::getBlockState)
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
        if (account.special != null && account.special.kind() == ContractData.Kind.CORRIDOR && account.special != account.active) {
            occupied.add(account.special.blueprint().bounds());
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
            if (obstructsPlacement(site, position, state)) {
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

    private static boolean obstructsPlacement(ServerLevel site, BlockPos pos, BlockState state) {
        if (state.getDestroySpeed(site, pos) < 0 || !state.getFluidState().isEmpty()) {
            return true;
        }
        var entity = state.hasBlockEntity() ? site.getBlockEntity(pos) : null;
        return entity instanceof Container || entity != null && entity.getCapability(ForgeCapabilities.ITEM_HANDLER).isPresent();
    }

    private record Placement(RoomBlueprint blueprint, Map<BlockPos, BlockState> originals) { }
}
