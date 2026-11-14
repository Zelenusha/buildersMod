package io.github.zelenusha.buildersmod.contract;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class FineLedger {
    public static final int MAX_ATTEMPTS = 2;

    public record Fine(UUID id, int amount, int withheld, String reason, int audacity, int attemptsUsed,
                       boolean overturned) {
        public Fine {
            Objects.requireNonNull(id);
            Objects.requireNonNull(reason);
            if (amount <= 0 || withheld < 0 || withheld > amount) {
                throw new IllegalArgumentException("Invalid fine amount or withholding");
            }
            if (audacity < 1 || audacity > 5 || attemptsUsed < 0 || attemptsUsed > MAX_ATTEMPTS
                    || (overturned && attemptsUsed == 0)) {
                throw new IllegalArgumentException("Invalid fine appeal state");
            }
        }

        public int outstanding() {
            return overturned ? 0 : amount - withheld;
        }

        public boolean canAppeal() {
            return !overturned && attemptsUsed < MAX_ATTEMPTS;
        }
    }

    public record AppealAward(int refund, long compensation) { }

    private final Map<UUID, Fine> fines = new LinkedHashMap<>();
    private final Set<UUID> activeAttempts = new HashSet<>();

    public FineLedger() { }

    public FineLedger(List<Fine> entries) {
        for (Fine fine : entries) {
            if (fines.putIfAbsent(fine.id(), fine) != null) {
                throw new IllegalArgumentException("Duplicate fine ID");
            }
        }
    }

    public List<Fine> entries() {
        return List.copyOf(fines.values());
    }

    public Fine find(UUID id) {
        return fines.get(id);
    }

    public Fine issue(int amount, String reason, int audacity) {
        return issue(UUID.randomUUID(), amount, reason, audacity);
    }

    public Fine issue(UUID id, int amount, String reason, int audacity) {
        Fine fine = new Fine(id, amount, 0, reason, audacity, 0, false);
        Fine existing = fines.putIfAbsent(id, fine);
        return existing == null ? fine : existing;
    }

    public int apply(int salary) {
        if (salary < 0) {
            throw new IllegalArgumentException("Negative salary");
        }
        int remaining = salary;
        for (Map.Entry<UUID, Fine> entry : fines.entrySet()) {
            if (remaining == 0) {
                break;
            }
            Fine fine = entry.getValue();
            int deduction = Math.min(remaining, fine.outstanding());
            if (deduction > 0) {
                entry.setValue(new Fine(fine.id(), fine.amount(), fine.withheld() + deduction, fine.reason(),
                        fine.audacity(), fine.attemptsUsed(), fine.overturned()));
                remaining -= deduction;
            }
        }
        return remaining;
    }

    public boolean beginAttempt(UUID id) {
        Fine fine = find(id);
        if (fine == null || !fine.canAppeal() || !activeAttempts.add(id)) {
            return false;
        }
        fines.put(id, new Fine(fine.id(), fine.amount(), fine.withheld(), fine.reason(), fine.audacity(),
                fine.attemptsUsed() + 1, false));
        return true;
    }

    public void expireAttempt(UUID id) {
        activeAttempts.remove(id);
    }

    public Optional<AppealAward> appealResolved(UUID id, int remainingFullSeconds) {
        if (remainingFullSeconds < 0) {
            throw new IllegalArgumentException("Negative appeal time");
        }
        if (!activeAttempts.remove(id)) {
            return Optional.empty();
        }
        Fine fine = find(id);
        fines.put(id, new Fine(fine.id(), fine.amount(), fine.withheld(), fine.reason(), fine.audacity(),
                fine.attemptsUsed(), true));
        return Optional.of(new AppealAward(fine.withheld(), (long) fine.amount() + remainingFullSeconds));
    }
}
