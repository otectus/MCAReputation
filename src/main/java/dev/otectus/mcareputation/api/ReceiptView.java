package dev.otectus.mcareputation.api;

import dev.otectus.mcareputation.community.CommunityKey;

import java.util.Optional;
import java.util.UUID;

/**
 * The immutable, API-side view of one stored delivery receipt (§6 "Receipt lookup").
 *
 * <p>Identity is {@code producerNamespace + player + community + operationKey}. {@code occurredGameTime}
 * is the delivery's occurrence time — the wrapped request's game time — and {@code recordedGameTime}
 * is the world clock when this side committed, which for a backdated delivery are different numbers.
 *
 * @since MCA: Reputation 0.4.1
 */
public record ReceiptView(String producerNamespace, UUID player, CommunityKey community,
                          String operationKey, ReceiptOutcome outcome, Optional<UUID> incidentId,
                          long occurredGameTime, long recordedGameTime) {

    public ReceiptView {
        producerNamespace = producerNamespace == null ? "" : producerNamespace;
        operationKey = operationKey == null ? "" : operationKey;
        incidentId = incidentId == null ? Optional.empty() : incidentId;
    }
}
