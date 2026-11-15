package io.github.zelenusha.buildersmod.contract;

import io.github.zelenusha.buildersmod.BuildersMod;
import io.github.zelenusha.buildersmod.blueprint.RoomBlueprint;
import io.github.zelenusha.buildersmod.intro.IntroEvents;
import io.github.zelenusha.buildersmod.maintenance.GraffitiEvents;
import io.github.zelenusha.buildersmod.maintenance.IncidentState;
import io.github.zelenusha.buildersmod.maintenance.IncidentWorld;
import io.github.zelenusha.buildersmod.site.ConstructionSite;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.SleepFinishedTimeEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = BuildersMod.MOD_ID)
public final class ContractEvents {
    private static final TicketType<ChunkPos> CHECK_TICKET = TicketType.create("buildersmod_room_check",
            java.util.Comparator.comparingLong(ChunkPos::toLong));

    private ContractEvents() { }

    @SubscribeEvent
    public static void interact(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getHand() != InteractionHand.MAIN_HAND
                || !IntroEvents.isOwnForeman(player, event.getTarget())) {
            return;
        }
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
        show(player, event.getTarget().getUUID(), true, "");
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void slept(SleepFinishedTimeEvent event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
            ContractData.get(level.getServer()).advanceSleep(event.getNewTime() - level.getDayTime());
        }
    }

    @SubscribeEvent
    public static void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        ContractData data = ContractData.get(event.getServer());
        long now = data.time(event.getServer());
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            ContractData.Account account = data.account(player.getUUID());
            boolean expired = checkDeadline(player, data, account);
            int activeIncidents = account.incidents.size();
            IncidentWork.tick(player, data, account, now);
            if (expired || activeIncidents != account.incidents.size() || now % 120 == 0) {
                sync(player, data, account);
            } else if (account.active != null && now % 20 == 0) {
                ContractNetwork.syncTimer(player, account.active, remaining(account, now));
            }
        }
    }

    static void handle(ServerPlayer player, UUID foreman, ContractNetwork.Action action, int choice, UUID workId) {
        Entity target = player.serverLevel().getEntity(foreman);
        if (target == null || !target.isAlive() || player.distanceToSqr(target) > 64
                || !IntroEvents.hasAcceptedWork(player) || !IntroEvents.isOwnForeman(player, target)) {
            return;
        }
        ContractData data = ContractData.get(player.server);
        ContractData.Account account = data.account(player.getUUID());
        checkDeadline(player, data, account);
        String feedback = switch (action) {
            case SHOW_ORDERS, SHOW_DEMOLITIONS, SHOW_FINES -> "";
            case ACCEPT -> accept(player, data, account, choice);
            case SUBMIT -> workId != null ? submitIncident(player, data, account, workId) : submit(player, data, account);
            case CANCEL -> cancel(player, data, account);
            case CLAIM -> claim(player, data, account);
            case BUY_TIME -> buyTime(player, data, account);
            case DEMOLISH -> deliverDemolition(player, data, account, workId);
            case APPEAL -> startAppeal(player, data, account, workId);
            case CARD -> chooseAppealCard(data, account, workId, choice);
        };
        if (action == ContractNetwork.Action.SHOW_DEMOLITIONS || action == ContractNetwork.Action.DEMOLISH) {
            showDemolitions(player, foreman, account, feedback);
        } else if (action == ContractNetwork.Action.SHOW_FINES || action == ContractNetwork.Action.APPEAL
                || action == ContractNetwork.Action.CARD) {
            showFines(player, foreman, account, feedback);
        } else {
            show(player, foreman, false, feedback);
        }
        sync(player, data, account);
    }

    static String accept(ServerPlayer player, ContractData data, ContractData.Account account, int slot) {
        if (!offersAreReady(player)) {
            return "Сейчас этот заказ недоступен.";
        }
        ServerLevel site = player.server.getLevel(ConstructionSite.SITE_LEVEL);
        if (site == null || slot >= account.offers.length) {
            return "Сейчас этот заказ недоступен.";
        }
        OfferFactory.fill(player, data, account);
        if (slot >= 3 && account.incidentOffers[slot - 3] != null) {
            var offer = account.incidentOffers[slot - 3];
            if (account.incidentSlots.containsKey(offer.id())) {
                return "Этот заказ уже выполняется.";
            }
            return IncidentWork.accept(player, data, account, offer)
                    ? "Заказ принят. Начни работу на стройке." : "Эту работу сейчас нельзя принять.";
        }
        if (account.active != null) {
            return "Сначала закончи текущую постройку.";
        }
        ContractData.Accepted selected;
        if (slot == -1) {
            selected = hasAvailableRoom(site, account) ? null : OfferFactory.extension(site, account);
        } else if (slot < -1) {
            selected = account.courtyards.stream().filter(yard -> yard.slot() == slot && OfferFactory.canResume(site, yard))
                    .findFirst().orElse(null);
        } else {
            selected = account.offers[slot];
        }
        if (selected != null && slot >= 0) {
            selected = OfferFactory.bind(site, account, selected);
        }
        if (selected == null) {
            return "Нет места за подходящим проёмом.";
        }
        if (selected.kind() != ContractData.Kind.STAIRS && account.incidents.stream().anyMatch(work -> work.kind().pausesConstruction())) {
            return "Сначала закончи работу, которая приостанавливает стройку.";
        }
        if (selected.kind() == ContractData.Kind.ROOM || selected.kind() == ContractData.Kind.SAMPLE) {
            selected = selected.rebound(selected.anchor(), selected.outward(), selected.blueprint(),
                    DemolitionWork.snapshot(site, selected.blueprint()));
            account.pendingCourtyard = OfferFactory.courtyard(site, account, selected);
        }
        account.active = selected.started(data.time(player.server));
        account.stairInspection = ContractData.StairInspection.WAITING;
        if (slot == -1) {
            account.special = selected;
        }
        data.setDirty();
        return "Заказ принят. Чертёж ждёт на стройке.";
    }

    static String submit(ServerPlayer player, ContractData data, ContractData.Account account) {
        if (account.active == null) {
            return "Нет активной постройки.";
        }
        if (account.pausedAt >= 0) {
            return "Сначала закончи задание, приостановившее стройку.";
        }
        if (account.stairInspection == ContractData.StairInspection.NEEDS_REPAIR_DAY) {
            return "Купи сутки на восстановление лестницы перед повторной сдачей.";
        }
        RoomBlueprint.BlueprintCheck check = check(player, account.active);
        if (check == null || !check.complete()) {
            if (check != null && account.stairInspection == ContractData.StairInspection.READY_AT_DEADLINE) {
                account.stairInspection = ContractData.StairInspection.NEEDS_REPAIR_DAY;
                data.setDirty();
                return "Лестницу повредили. Купи сутки на восстановление.";
            }
            return "Такую комнату я не приму. Посмотри чертёж.";
        }
        finish(player, data, account, check, true);
        return "Постройка принята. Зарплату можно забрать.";
    }

    static String cancel(ServerPlayer player, ContractData data, ContractData.Account account) {
        ContractData.Accepted order = account.active;
        if (order == null) {
            return "Нет активной постройки.";
        }
        RoomBlueprint.BlueprintCheck check = check(player, order);
        if (check == null) {
            return "Стройплощадка недоступна.";
        }
        int fine = check.complete() ? 3 : ContractBalance.incompleteFine(check.mismatchCount(), check.violatedCriteriaCount());
        fail(data, account, order, fine, "Отмена заказа", fineAudacity(player, 0, 1));
        return "Заказ отменён. Штраф: " + fine + " изумрудов.";
    }

    static boolean checkDeadline(ServerPlayer player, ContractData data, ContractData.Account account) {
        ContractData.Accepted order = account.active;
        if (order == null || account.pausedAt >= 0 || order.deadline() > data.time(player.server)
                || account.stairInspection != ContractData.StairInspection.WAITING) {
            return false;
        }
        RoomBlueprint.BlueprintCheck check = check(player, order);
        if (check == null) {
            return false;
        }
        if (check.complete() && order.kind() == ContractData.Kind.STAIRS) {
            account.stairInspection = ContractData.StairInspection.READY_AT_DEADLINE;
            data.setDirty();
            player.sendSystemMessage(Component.literal("Лестница готова. Сдай её прорабу, чтобы открыть этаж."));
        } else if (check.complete()) {
            finish(player, data, account, check, false);
        } else if (order.slot() < 0) {
            int surcharge = 3 * order.lateCount();
            int fine = ContractBalance.incompleteFine(check.mismatchCount(), check.violatedCriteriaCount()) + surcharge;
            account.fines.issue(fine, "Недострой в срок", fineAudacity(player, surcharge, 0));
            account.active = order.withDeadline(data.time(player.server) + order.difficulty().duration(), order.lateCount() + 1);
            retainSpecial(account, account.active);
            data.setDirty();
            account.lastCall = "Опять недострой. Штраф " + fine + " изумрудов. Даю ещё "
                    + order.difficulty().duration() / ContractBalance.TICKS_PER_DAY + " дн. на тот же заказ.";
            ContractNetwork.call(player, account.lastCall);
        } else {
            fail(data, account, order, ContractBalance.incompleteFine(check.mismatchCount(), check.violatedCriteriaCount()),
                    "Недострой в срок", fineAudacity(player, 0, 0));
            player.sendSystemMessage(Component.literal("Срок истёк. Прораб оштрафовал за недострой."));
        }
        return true;
    }

    private static int fineAudacity(ServerPlayer player, int surcharge, int reasonWeight) {
        return Math.min(5, 1 + Math.max(0, surcharge) / 3 + reasonWeight + player.getRandom().nextInt(2));
    }

    private static void fail(ContractData data, ContractData.Account account, ContractData.Accepted order, int fine, String reason,
                             int audacity) {
        account.fines.issue(fine, reason, audacity);
        if (order.slot() >= 0) {
            account.failed.add(new DemolitionWork(order));
            account.offers[order.slot()] = null;
        } else {
            retainSpecial(account, order);
        }
        account.pendingCourtyard = null;
        account.sequence++;
        account.active = null;
        account.pausedAt = -1;
        account.stairInspection = ContractData.StairInspection.WAITING;
        data.setDirty();
    }

    static String deliverDemolition(ServerPlayer player, ContractData data, ContractData.Account account, UUID workId) {
        DemolitionWork work = account.failed.stream().filter(entry -> entry.id().equals(workId)).findFirst().orElse(null);
        ServerLevel site = player.server.getLevel(ConstructionSite.SITE_LEVEL);
        if (work == null || site == null) {
            return "Этот снос сейчас недоступен.";
        }
        if (!withLoadedArea(site, work.order().blueprint().bounds(), () -> work.readyToDeliver(site))) {
            return "Разбери постройку и восстанови исходные блоки пола.";
        }
        if (!work.deliver(player.getInventory())) {
            return "Принеси все материалы из списка сноса.";
        }
        account.failed.remove(work);
        data.setDirty();
        return "Снос принят.";
    }

    private static void showDemolitions(ServerPlayer player, UUID foreman, ContractData.Account account, String feedback) {
        var demolitions = account.failed.stream().map(work -> new ContractNetwork.DemolitionCard(work.id(),
                work.order().blueprint().bounds().min(), work.materials().entrySet().stream()
                .map(entry -> new ContractNetwork.Material(entry.getKey().getDescriptionId(), "", 0, entry.getValue()))
                .sorted(java.util.Comparator.comparing(ContractNetwork.Material::label)).toList())).toList();
        ContractNetwork.sendDemolitions(player, new ContractNetwork.DemolitionPanel(foreman, demolitions, feedback));
    }

    private static String startAppeal(ServerPlayer player, ContractData data, ContractData.Account account, UUID fineId) {
        long now = System.nanoTime();
        expireAppeal(account, now);
        if (account.appeal != null) {
            return "Сначала закончи текущую попытку.";
        }
        if (fineId == null || !account.fines.beginAttempt(fineId)) {
            return "Попытки по этому штрафу недоступны.";
        }
        account.appeal = AppealPuzzle.start(account.fines.find(fineId), player.getRandom()::nextInt, now);
        data.setDirty();
        return "";
    }

    private static String chooseAppealCard(ContractData data, ContractData.Account account, UUID attemptId, int choice) {
        AppealPuzzle puzzle = account.appeal;
        if (puzzle == null || !puzzle.attemptId().equals(attemptId)) {
            return "Попытка уже завершена.";
        }
        long now = System.nanoTime();
        return switch (puzzle.choose(choice, now)) {
            case IN_PROGRESS -> "";
            case EXPIRED -> {
                expireAppeal(account, now);
                yield "Время попытки истекло.";
            }
            case WRONG_ORDER -> {
                account.fines.expireAttempt(puzzle.fineId());
                account.appeal = null;
                yield "Аргумент не принят. Проверь порядок карточек.";
            }
            case WON -> {
                var award = account.fines.appealResolved(puzzle.fineId(), puzzle.remainingFullSeconds(now)).orElseThrow();
                account.appeal = null;
                account.unpaid += award.refund() + award.compensation();
                data.setDirty();
                yield "Штраф отменён. Возврат " + award.refund() + ", компенсация " + award.compensation() + ".";
            }
        };
    }

    private static boolean expireAppeal(ContractData.Account account, long now) {
        if (account.appeal == null || account.appeal.status(now) != AppealPuzzle.Status.EXPIRED) {
            return false;
        }
        account.fines.expireAttempt(account.appeal.fineId());
        account.appeal = null;
        return true;
    }

    private static void showFines(ServerPlayer player, UUID foreman, ContractData.Account account, String feedback) {
        long now = System.nanoTime();
        String message = expireAppeal(account, now) && feedback.isEmpty() ? "Время попытки истекло." : feedback;
        AppealPuzzle puzzle = account.appeal;
        var view = puzzle == null ? null : new ContractNetwork.AppealView(puzzle.attemptId(), puzzle.hint(),
                puzzle.progress(), puzzle.requiredCount(), puzzle.remainingNanos(now) / 1_000_000, puzzle.cards());
        var rows = account.fines.entries().stream().map(fine -> new ContractNetwork.FineRow(fine.id(), fine.reason(),
                fine.amount(), fine.outstanding(), FineLedger.MAX_ATTEMPTS - fine.attemptsUsed(), fine.overturned())).toList();
        ContractNetwork.sendFines(player, new ContractNetwork.FinePanel(foreman, rows, view, message));
    }

    private static void finish(ServerPlayer player, ContractData data, ContractData.Account account,
                               RoomBlueprint.BlueprintCheck check, boolean early) {
        ContractData.Accepted order = account.active;
        account.rooms.add(order);
        if (order.kind() == ContractData.Kind.ROOM || order.kind() == ContractData.Kind.SAMPLE) {
            account.completed++;
        }
        if (order.kind() == ContractData.Kind.STAIRS) {
            account.highestFloor++;
        }
        if (order.slot() >= 0) {
            account.offers[order.slot()] = null;
        } else if (order.slot() == -1) {
            account.special = null;
        } else {
            account.courtyards.removeIf(yard -> yard.slot() == order.slot());
        }
        if (account.pendingCourtyard != null) {
            account.courtyards.add(account.pendingCourtyard);
            account.nextCourtyardSlot--;
            account.pendingCourtyard = null;
        }
        account.sequence++;
        int bonus = early && order.lateCount() == 0
                ? ContractBalance.earlyBonus(order.deadline() - data.time(player.server), order.extraDay()) : 0;
        int wage = order.wage() + check.bonusEmeralds() + bonus;
        if (player.getRandom().nextInt(10) == 0) {
            int audacity = 1 + player.getRandom().nextInt(5);
            String reason = switch (player.getRandom().nextInt(3)) {
                case 0 -> "Гороскоп велел всё переделать";
                case 1 -> "Стена кривая, если смотреть лёжа на боку";
                default -> "У потолка недостаточно бодрый цвет";
            };
            account.fines.issue(5 * audacity, reason, audacity);
        }
        account.unpaid += account.fines.apply(wage);
        account.active = null;
        account.pausedAt = -1;
        account.stairInspection = ContractData.StairInspection.WAITING;
        data.setDirty();
    }

    static String claim(ServerPlayer player, ContractData data, ContractData.Account account) {
        int received = 0;
        while (account.unpaid > 0) {
            ItemStack payment = new ItemStack(Items.EMERALD, (int) Math.min(64, account.unpaid));
            int offered = payment.getCount();
            player.getInventory().add(payment);
            int delivered = offered - payment.getCount();
            account.unpaid -= delivered;
            received += delivered;
            if (delivered < offered) {
                break;
            }
        }
        if (received > 0) {
            data.setDirty();
            return "Выдано изумрудов: " + received + ".";
        }
        return account.unpaid == 0 ? "Начислений к выдаче нет." : "Освободи место в инвентаре.";
    }

    static String buyTime(ServerPlayer player, ContractData data, ContractData.Account account) {
        ContractData.Accepted order = account.active;
        if (order == null || !canBuyTime(account)) {
            return "Дополнительные сутки недоступны.";
        }
        int price = order.difficulty().extraDayPrice();
        int carried = player.getInventory().items.stream().filter(stack -> stack.is(Items.EMERALD))
                .mapToInt(ItemStack::getCount).sum();
        if (carried < price) {
            return "Не хватает изумрудов.";
        }
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(Items.EMERALD)) {
                int taken = Math.min(stack.getCount(), price);
                stack.shrink(taken);
                price -= taken;
            }
            if (price == 0) {
                break;
            }
        }
        player.getInventory().setChanged();
        account.active = order.withExtraDay(data.time(player.server));
        if (order.slot() < 0) {
            retainSpecial(account, account.active);
        }
        account.stairInspection = ContractData.StairInspection.WAITING;
        data.setDirty();
        return "Добавлены одни игровые сутки.";
    }

    private static boolean canBuyTime(ContractData.Account account) {
        return account.pausedAt < 0 && account.stairInspection.canBuyDay(account.active.extraDay());
    }

    private static String submitIncident(ServerPlayer player, ContractData data, ContractData.Account account, UUID id) {
        var incident = account.incidents.stream().filter(work -> work.id().equals(id)).findFirst().orElse(null);
        ServerLevel site = player.server.getLevel(ConstructionSite.SITE_LEVEL);
        if (incident == null || site == null) {
            return "Такого активного задания сейчас нет.";
        }
        var wage = IncidentWork.submit(site, account, incident);
        if (incident.kind() == IncidentState.Kind.FIRE) {
            data.setDirty();
        }
        if (wage.isEmpty()) {
            return "Работа пока не готова. Проверь указанную комнату.";
        }
        if (incident.kind() == IncidentState.Kind.CLEANUP) {
            GraffitiEvents.remove(site, id);
        }
        account.unpaid += account.fines.apply(wage.getAsInt());
        IncidentWork.finish(player, data, account, incident);
        return "Работа принята. Зарплата у прораба.";
    }

    @SubscribeEvent
    public static void onDeath(net.minecraftforge.event.entity.living.LivingDeathEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)) {
            return;
        }
        ContractData data = ContractData.get(level.getServer());
        if (event.getEntity() instanceof ServerPlayer player) {
            var account = data.account(player.getUUID());
            for (var work : List.copyOf(account.incidents)) {
                if (work.recordPlayerDeath()) {
                    account.failedEvictions.computeIfAbsent(work.originRoom(), key -> new ArrayList<>())
                            .addAll(work.remainingEvictionTargets());
                    IncidentWork.finish(player, data, account, work);
                }
            }
        } else if (level.dimension() == ConstructionSite.SITE_LEVEL) {
            data.recordEvictionDeath(event.getEntity());
        }
    }

    @SubscribeEvent(priority = EventPriority.MONITOR)
    public static void onFloodFill(net.minecraftforge.event.level.BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.serverLevel().dimension() != ConstructionSite.SITE_LEVEL) {
            return;
        }
        var snapshots = event instanceof net.minecraftforge.event.level.BlockEvent.EntityMultiPlaceEvent multi
                ? multi.getReplacedBlockSnapshots() : List.of(event.getBlockSnapshot());
        var data = ContractData.get(player.server);
        var account = data.account(player.getUUID());
        for (var incident : account.incidents) {
            var room = IncidentWork.room(account, incident.originRoom());
            if (incident.kind() != IncidentState.Kind.FLOOD || room == null) {
                continue;
            }
            for (var snapshot : snapshots) {
                var previous = snapshot.getReplacedBlock();
                if (room.blueprint().containsInterior(snapshot.getPos())
                        && (previous.isAir() || previous.is(Blocks.WATER)) && incident.recordFloodFill(snapshot.getPos())) {
                    data.setDirty();
                }
            }
        }
    }

    private static void retainSpecial(ContractData.Account account, ContractData.Accepted order) {
        if (order.slot() == -1) {
            account.special = order;
        } else {
            account.courtyards.replaceAll(yard -> yard.slot() == order.slot() ? order : yard);
        }
    }

    private static RoomBlueprint.BlueprintCheck check(ServerPlayer player, ContractData.Accepted order) {
        ServerLevel site = player.server.getLevel(ConstructionSite.SITE_LEVEL);
        if (site == null) {
            return null;
        }
        RoomBlueprint room = order.blueprint();
        return withLoadedArea(site, room.bounds(), () -> room.check(site));
    }

    static <T> T withLoadedArea(ServerLevel site, RoomBlueprint.Bounds bounds, Supplier<T> work) {
        return withLoadedAreas(site, List.of(bounds), work);
    }

    static <T> T withLoadedAreas(ServerLevel site, List<RoomBlueprint.Bounds> areas, Supplier<T> work) {
        var chunks = site.getChunkSource();
        List<ChunkPos> held = areas.stream().flatMap(bounds -> ChunkPos.rangeClosed(new ChunkPos(bounds.min()),
                new ChunkPos(bounds.max()))).distinct().toList();
        held.forEach(pos -> chunks.addRegionTicket(CHECK_TICKET, pos, 0, pos));
        try {
            held.forEach(pos -> site.getChunk(pos.x, pos.z));
            return work.get();
        } finally {
            held.forEach(pos -> chunks.removeRegionTicket(CHECK_TICKET, pos, 0, pos));
        }
    }

    private static boolean hasAvailableRoom(ServerLevel site, ContractData.Account account) {
        for (ContractData.Accepted offer : account.offers) {
            if (offer != null && OfferFactory.bind(site, account, offer) != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean offersAreReady(ServerPlayer player) {
        return IntroEvents.hasAcceptedWork(player) && Math.floorDiv(player.server.overworld().getDayTime(),
                ContractBalance.TICKS_PER_DAY) >= IntroEvents.firstOffersDay(player);
    }

    private static void show(ServerPlayer player, UUID foreman, boolean dialogue, String feedback) {
        ContractNetwork.sendPanel(player, panel(player, foreman, dialogue, feedback));
    }

    /** The foreman's dialogue or order list as the client receives it. */
    static ContractNetwork.Panel panel(ServerPlayer player, UUID foreman, boolean dialogue, String feedback) {
        ContractData data = ContractData.get(player.server);
        ContractData.Account account = data.account(player.getUUID());
        ContractData.Accepted order = account.active;
        List<ContractNetwork.Card> cards = new ArrayList<>();
        ContractNetwork.State state = ContractNetwork.State.NOT_READY;
        if (!dialogue && order != null) {
            state = ContractNetwork.State.ACTIVE;
            cards.add(card(order, check(player, order), remaining(account, data.time(player.server)), true,
                    canBuyTime(account), ""));
            OfferFactory.fill(player, data, account);
            for (var offer : account.offers) {
                if (offer != null && offer.slot() != order.slot()) {
                    cards.add(card(offer, null, offer.difficulty().duration(), false, false,
                            "Сначала закончи текущую постройку"));
                }
            }
        } else if (!dialogue && offersAreReady(player)) {
            ServerLevel site = player.server.getLevel(ConstructionSite.SITE_LEVEL);
            if (site != null) {
                OfferFactory.fill(player, data, account);
                // FR-163: an eviction, fire or flood blocks new rooms, courtyards and corridors but not the staircase.
                boolean urgent = account.incidents.stream().anyMatch(work -> work.kind().pausesConstruction());
                String busy = "Сначала закончи срочное задание";
                List<ContractData.Accepted> shown = new ArrayList<>();
                List<Boolean> fits = new ArrayList<>();
                for (ContractData.Accepted offer : account.offers) {
                    if (offer != null && (offer.slot() < 3 || account.incidentOffers[offer.slot() - 3] == null)) {
                        shown.add(offer);
                        fits.add(OfferFactory.bind(site, account, offer) != null);
                    }
                }
                ContractData.Accepted extension = fits.contains(true) ? null : OfferFactory.extension(site, account);
                // FR-182: an offer that fits nowhere keeps its card and says why.
                String noSpace = extension != null ? "Нет места на этаже, нужен отдельный заказ"
                        : account.highestFloor >= ConstructionSite.FLOOR_COUNT ? "Открыт последний этаж" : "Проход перекрыт";
                for (int index = 0; index < shown.size(); index++) {
                    ContractData.Accepted offer = shown.get(index);
                    cards.add(card(offer, null, offer.difficulty().duration(), fits.get(index) && !urgent, false,
                            !fits.get(index) ? noSpace : urgent ? busy : ""));
                }
                if (extension != null) {
                    boolean stairs = extension.kind() == ContractData.Kind.STAIRS;
                    cards.add(card(extension, null, extension.difficulty().duration(), stairs || !urgent, false,
                            stairs || !urgent ? "" : busy));
                }
                for (var yard : account.courtyards) {
                    boolean free = OfferFactory.canResume(site, yard);
                    cards.add(card(yard, null, yard.difficulty().duration(), free && !urgent, false,
                            !free ? "Участок занят" : urgent ? busy : ""));
                }
            }
            state = cards.stream().anyMatch(ContractNetwork.Card::available)
                    ? ContractNetwork.State.AVAILABLE : ContractNetwork.State.NO_SPACE;
        }
        if (!dialogue && offersAreReady(player)) {
            for (var offer : account.incidentOffers) {
                if (offer != null && !account.incidentSlots.containsKey(offer.id())) {
                    boolean canAccept = !offer.kind().pausesConstruction() || !IncidentWork.blocksEvents(account);
                    cards.add(incidentCard(offer.slot(), offer.kind(), offer.level(), canAccept, null, offer.room()));
                }
            }
            for (var work : account.incidents) {
                int slot = account.incidentSlots.getOrDefault(work.id(), -100);
                cards.add(incidentCard(slot, work.kind(), work.difficulty(), true, work.id(), work.originRoom()));
            }
            cards.sort(java.util.Comparator.comparingInt(card -> card.slot() < 0 ? Integer.MAX_VALUE : card.slot()));
        }
        return new ContractNetwork.Panel(foreman, dialogue, state, account.unpaid,
                account.completed, cards, account.failed.size(), account.fines.entries().size(), feedback, account.lastCall,
                account.incidents.stream().filter(work -> work.kind().pausesConstruction() && account.pausedAt >= 0)
                        .map(work -> work.kind().title).findFirst().orElse(""));
    }

    private static ContractNetwork.Card incidentCard(int slot, IncidentState.Kind kind, ContractBalance.Difficulty level,
                                                      boolean available, UUID id, BlockPos room) {
        return new ContractNetwork.Card(slot, kind.title, level.ordinal() + 1, level.emeralds(), 0, -1,
                available, false, 0, room.toShortString(), 0, 0, List.of(), id, kind == IncidentState.Kind.CLEANUP,
                available ? "" : "Сначала закончи срочное задание", List.of());
    }

    private record MaterialKey(net.minecraft.world.item.Item primary, List<net.minecraft.world.item.Item> substitutes) { }

    private static ContractNetwork.Card card(ContractData.Accepted order, RoomBlueprint.BlueprintCheck check,
                                             long remaining, boolean available, boolean canBuyTime, String note) {
        Map<MaterialKey, int[]> groups = new LinkedHashMap<>();
        for (var entry : order.blueprint().requirements().entrySet()) {
            BlockPos position = entry.getKey();
            var requirement = entry.getValue();
            BlockState expected = requirement.expectedState();
            BlockPos second = position;
            if (requirement.clearance()) {
                continue;
            } else if (expected.getBlock() instanceof DoorBlock) {
                if (expected.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
                    continue;
                }
                second = position.above();
            } else if (expected.getBlock() instanceof BedBlock) {
                if (expected.getValue(BedBlock.PART) != BedPart.FOOT) {
                    continue;
                }
                second = position.relative(expected.getValue(BedBlock.FACING));
            }
            MaterialKey group = new MaterialKey(expected.getBlock().asItem(), requirement.replacements().stream()
                    .map(state -> state.getBlock().asItem()).distinct().toList());
            int[] counts = groups.computeIfAbsent(group, ignored -> new int[2]);
            counts[1]++;
            if (check != null && check.statuses().get(position) == RoomBlueprint.Status.CORRECT
                    && check.statuses().get(second) == RoomBlueprint.Status.CORRECT) {
                counts[0]++;
            }
        }
        List<ContractNetwork.Material> materials = groups.entrySet().stream().map(entry -> {
            MaterialKey group = entry.getKey();
            int[] counts = entry.getValue();
            String names = group.substitutes().stream().map(item -> item.getDescriptionId())
                    .collect(java.util.stream.Collectors.joining(","));
            return new ContractNetwork.Material(group.primary().getDescriptionId(), names, counts[0], counts[1]);
        }).toList();
        var bonus = order.blueprint().bonus().orElse(null);
        return new ContractNetwork.Card(order.slot(), order.brief().title(), order.difficulty().ordinal() + 1,
                order.wage(), bonus == null ? 0 : bonus.emeralds(), remaining, available, canBuyTime,
                check == null ? 0 : check.mismatchCount(), bonus == null ? "" : bonus.block().asItem().getDescriptionId(),
                check == null ? 0 : check.bonusBlockCount(), bonus == null ? 0 : bonus.exactCount(), materials, null, false,
                note, order.brief().conditions());
    }

    private static void sync(ServerPlayer player, ContractData data, ContractData.Account account) {
        ContractData.Accepted order = account.active;
        ContractNetwork.sync(player, order, remaining(account, data.time(player.server)));
        var names = new ArrayList<String>();
        account.incidents.forEach(work -> names.add(work.kind().title));
        for (int index = 0; index < account.failed.size(); index++) {
            names.add("Снос комнаты " + (index + 1));
        }
        ContractNetwork.tasks(player, List.copyOf(names), account.pausedAt >= 0);
    }

    private static long remaining(ContractData.Account account, long now) {
        return account.active == null ? 0 : Math.max(0, account.active.deadline() - (account.pausedAt >= 0 ? account.pausedAt : now));
    }
}
