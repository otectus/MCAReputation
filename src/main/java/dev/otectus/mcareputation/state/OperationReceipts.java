package dev.otectus.mcareputation.state;

import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.reputation.ReputationBounds;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * One player's delivery receipts, insertion-ordered (spec §5 F03, DD6).
 *
 * <h2>Two ways in</h2>
 *
 * <p>The stored identity is {@code namespace|community|operationKey}. Every dedupe key written before
 * receipts existed — {@code DeedKeys}, the inline assault and kill keys, a companion's
 * {@code conversation:…} — carries no namespace of its own, so {@link #findAnyNamespace} matches on
 * community and key alone. That is what stops the upgrade from re-awarding a quest whose key predates
 * this feature.
 *
 * <h2>Two budgets</h2>
 *
 * <p>A count ceiling ({@link ReputationBounds#MAX_RECEIPTS_PER_PLAYER}) and a time horizon, both
 * evicting strictly oldest-first by <em>occurrence</em>. While the count is under budget, a receipt
 * whose incident is still in the ledger is never dropped for age: the ledger would answer the
 * question anyway, and disagreeing with it is worse than keeping the entry. {@link #floor()} publishes
 * where the memory ends so a producer can tell "never happened" from "too old to remember".
 */
public final class OperationReceipts {

    private final Map<String, OperationReceipt> receipts = new LinkedHashMap<>();

    // Where the memory ends: absent until something has actually been evicted, because "we have
    // never forgotten anything" and "we have forgotten everything before tick 0" are different answers.
    private boolean everEvicted;
    private long lastEvictedOccurrence;

    private static String storageKey(String namespace, CommunityKey community, String operationKey) {
        return namespace + "|" + community.asString() + "|" + operationKey;
    }

    public int size() {
        return receipts.size();
    }

    public boolean isEmpty() {
        return receipts.isEmpty();
    }

    public Collection<OperationReceipt> all() {
        return Collections.unmodifiableCollection(receipts.values());
    }

    /** The exact, namespaced lookup. */
    public Optional<OperationReceipt> find(String namespace, CommunityKey community, String operationKey) {
        if (namespace == null || community == null || operationKey == null || operationKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(receipts.get(storageKey(namespace, community, operationKey)));
    }

    /** The legacy form: an operation key alone within one community, whoever produced it. */
    public Optional<OperationReceipt> findAnyNamespace(CommunityKey community, String operationKey) {
        if (community == null || operationKey == null || operationKey.isBlank()) {
            return Optional.empty();
        }
        for (OperationReceipt receipt : receipts.values()) {
            if (receipt.operationKey().equals(operationKey) && receipt.community().equals(community)) {
                return Optional.of(receipt);
            }
        }
        return Optional.empty();
    }

    /**
     * Stores a receipt, replacing any earlier one under the same identity and evicting the oldest
     * occurrence when the count budget is full.
     */
    public void put(OperationReceipt receipt) {
        if (receipt == null || receipt.operationKey().isEmpty()) {
            return;
        }
        String key = storageKey(receipt.producerNamespace(), receipt.community(), receipt.operationKey());
        receipts.remove(key);
        while (receipts.size() >= ReputationBounds.MAX_RECEIPTS_PER_PLAYER) {
            if (!evictOldest()) {
                break;
            }
        }
        receipts.put(key, receipt);
    }

    /**
     * Drops receipts that fell out of the retention horizon, and anything still over the count budget.
     *
     * @param incidentRetained answers whether a receipt's incident is still in the ledger
     * @return how many were evicted
     */
    public int prune(long now, long retentionTicks, Predicate<OperationReceipt> incidentRetained) {
        int evicted = 0;
        if (retentionTicks > 0) {
            long cutoff = now - retentionTicks;
            for (OperationReceipt candidate : oldestFirst()) {
                if (candidate.occurredGameTime() >= cutoff) {
                    break; // ordered by occurrence: nothing later can be older than the cutoff
                }
                if (incidentRetained != null && incidentRetained.test(candidate)) {
                    continue; // the ledger still answers this one; the receipt must agree with it
                }
                evict(candidate);
                evicted++;
            }
        }
        while (receipts.size() > ReputationBounds.MAX_RECEIPTS_PER_PLAYER) {
            if (!evictOldest()) {
                break;
            }
            evicted++;
        }
        return evicted;
    }

    /**
     * The oldest occurrence this store can still answer for, or empty when nothing has ever been
     * evicted — in which case there is no floor and every operation is answerable.
     */
    public OptionalLong floor() {
        if (!everEvicted) {
            return OptionalLong.empty();
        }
        long oldest = Long.MAX_VALUE;
        for (OperationReceipt receipt : receipts.values()) {
            oldest = Math.min(oldest, receipt.occurredGameTime());
        }
        // With nothing left, the floor sits just past the last thing forgotten.
        return OptionalLong.of(receipts.isEmpty() ? lastEvictedOccurrence + 1 : oldest);
    }

    private List<OperationReceipt> oldestFirst() {
        List<OperationReceipt> ordered = new ArrayList<>(receipts.values());
        ordered.sort(Comparator.comparingLong(OperationReceipt::occurredGameTime)
                .thenComparing(OperationReceipt::operationKey));
        return ordered;
    }

    private boolean evictOldest() {
        List<OperationReceipt> ordered = oldestFirst();
        if (ordered.isEmpty()) {
            return false;
        }
        evict(ordered.get(0));
        return true;
    }

    private void evict(OperationReceipt receipt) {
        receipts.remove(storageKey(receipt.producerNamespace(), receipt.community(),
                receipt.operationKey()));
        everEvicted = true;
        lastEvictedOccurrence = Math.max(lastEvictedOccurrence, receipt.occurredGameTime());
    }

    // --- persistence --------------------------------------------------------

    public ListTag save() {
        ListTag list = new ListTag();
        receipts.values().forEach(receipt -> list.add(receipt.save()));
        return list;
    }

    /** Per-entry guarded: one unreadable receipt never costs the player the others. */
    public static OperationReceipts load(ListTag list, UUID owner) {
        OperationReceipts store = new OperationReceipts();
        if (list == null) {
            return store;
        }
        for (int i = 0; i < list.size() && store.size() < ReputationBounds.MAX_RECEIPTS_PER_PLAYER; i++) {
            Tag entry = list.get(i);
            if (entry instanceof CompoundTag compound) {
                OperationReceipt.load(compound, owner).ifPresent(store::put);
            }
        }
        return store;
    }
}
