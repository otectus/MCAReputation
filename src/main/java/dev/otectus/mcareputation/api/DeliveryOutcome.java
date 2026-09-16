package dev.otectus.mcareputation.api;

import java.util.Optional;

/**
 * What one {@link IncidentDelivery} did (§6 "Incident delivery").
 *
 * <p>Three answers in one: the producer-facing {@link ReceiptOutcome}, the ordinary
 * {@link ReputationResult} every other write path already returns, and the stored receipt when there
 * is one. A retryable refusal stores nothing, so {@link #receipt()} is empty for it.
 *
 * @since MCA: Reputation 0.4.1
 */
public record DeliveryOutcome(ReceiptOutcome outcome, ReputationResult result,
                              Optional<ReceiptView> receipt) {

    public DeliveryOutcome {
        receipt = receipt == null ? Optional.empty() : receipt;
    }

    public static DeliveryOutcome of(ReceiptOutcome outcome, ReputationResult result) {
        return new DeliveryOutcome(outcome, result, Optional.empty());
    }

    public static DeliveryOutcome of(ReceiptOutcome outcome, ReputationResult result, ReceiptView receipt) {
        return new DeliveryOutcome(outcome, result, Optional.ofNullable(receipt));
    }

    /** True when the deed was recorded and moved standing. */
    public boolean applied() {
        return outcome == ReceiptOutcome.APPLIED;
    }

    /** True when a retry with the same operation key could produce a different answer. */
    public boolean retryable() {
        return !outcome.isTerminal() && outcome != ReceiptOutcome.DUPLICATE;
    }
}
