package dev.otectus.mcareputation.state;

import dev.otectus.mcareputation.api.ReceiptOutcome;
import dev.otectus.mcareputation.api.ReceiptView;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.reputation.ReputationBounds;
import net.minecraft.nbt.CompoundTag;

import java.util.Optional;
import java.util.UUID;

/**
 * One producer's record that a named operation was delivered (spec §5 F03, DD6).
 *
 * <p>This is the thing that makes cross-mod delivery exactly-once. The dedupe index answers "is this
 * deed already in the ledger"; a receipt answers the harder question, "what did you tell me the first
 * time" — including for the deliveries that produced no incident at all, which the ledger by
 * definition cannot remember.
 *
 * <p>Identity is {@code producerNamespace + player + community + operationKey}. The player is the
 * store's owner rather than part of the stored key, so it is written for diagnostics and re-read on
 * load, never used to match.
 */
public record OperationReceipt(String producerNamespace, UUID player, CommunityKey community,
                               String operationKey, ReceiptOutcome outcome, Optional<UUID> incidentId,
                               long occurredGameTime, long recordedGameTime) {

    public OperationReceipt {
        producerNamespace = bound(producerNamespace, ReputationBounds.MAX_RECEIPT_NAMESPACE_LENGTH);
        operationKey = bound(operationKey, ReputationBounds.MAX_DEDUPE_KEY_LENGTH);
        outcome = outcome == null ? ReceiptOutcome.REFUSED_INVALID : outcome;
        incidentId = incidentId == null ? Optional.empty() : incidentId;
    }

    private static String bound(String raw, int max) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String trimmed = raw.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    /** The API-side copy. {@code state} stays internal; the compile-only API jar carries the view. */
    public ReceiptView toView() {
        return new ReceiptView(producerNamespace, player, community, operationKey, outcome, incidentId,
                occurredGameTime, recordedGameTime);
    }

    // --- persistence --------------------------------------------------------

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("ns", producerNamespace);
        if (player != null) {
            tag.putUUID("player", player);
        }
        tag.put("community", community.save());
        tag.putString("key", operationKey);
        tag.putString("outcome", outcome.name());
        incidentId.ifPresent(id -> tag.putUUID("incident", id));
        tag.putLong("occurred", occurredGameTime);
        tag.putLong("recorded", recordedGameTime);
        return tag;
    }

    /**
     * Reads one receipt. Empty — never thrown — when the entry is unusable: a receipt is a cache of an
     * answer, and one corrupt entry must never cost the player the rest of their record (§13.6).
     */
    public static Optional<OperationReceipt> load(CompoundTag tag, UUID owner) {
        if (tag == null) {
            return Optional.empty();
        }
        Optional<CommunityKey> community = CommunityKey.load(tag.getCompound("community"));
        String key = tag.getString("key");
        if (community.isEmpty() || key == null || key.isBlank()) {
            return Optional.empty();
        }
        ReceiptOutcome outcome;
        try {
            outcome = ReceiptOutcome.valueOf(tag.getString("outcome"));
        } catch (IllegalArgumentException e) {
            // An outcome this build does not know is not a reason to forget the operation happened.
            outcome = ReceiptOutcome.APPLIED;
        }
        return Optional.of(new OperationReceipt(tag.getString("ns"),
                tag.hasUUID("player") ? tag.getUUID("player") : owner,
                community.get(), key, outcome,
                tag.hasUUID("incident") ? Optional.of(tag.getUUID("incident")) : Optional.empty(),
                tag.getLong("occurred"), tag.getLong("recorded")));
    }
}
