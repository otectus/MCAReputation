package dev.otectus.mcareputation.api;

import dev.otectus.mcareputation.reputation.ReputationBounds;

/**
 * A {@link ReputationRequest} plus the identity a producer needs to make delivery exactly-once
 * (§6 "Incident delivery", DD5).
 *
 * <p>It <b>wraps</b> the request rather than extending it. {@code ReputationRequest} is a public
 * record compiled against by companions from a pinned jar, so moving its canonical constructor would
 * break the very mods this feature exists for.
 *
 * <p>The occurrence time is the wrapped request's {@link ReputationRequest#gameTime()}: a backdated
 * delivery is aged before it reaches the score, and the receipt records when it happened, not when it
 * arrived. {@code producerRevision} is opaque here — Reputation stores nothing from it and only hands
 * it back — and {@code 0} means "unused".
 *
 * @since MCA: Reputation 0.4.1
 */
public record IncidentDelivery(ReputationRequest request, String producerNamespace,
                               String operationKey, long producerRevision) {

    /** Maximum length of a producer namespace on a delivery. */
    public static final int MAX_NAMESPACE_LENGTH = ReputationBounds.MAX_RECEIPT_NAMESPACE_LENGTH;

    /** Maximum length of an operation key; the same bound as a dedupe key, because it is one. */
    public static final int MAX_OPERATION_KEY_LENGTH = ReputationBounds.MAX_DEDUPE_KEY_LENGTH;

    public IncidentDelivery {
        if (request == null) {
            throw new IllegalArgumentException("IncidentDelivery requires a request");
        }
        producerNamespace = bound(producerNamespace, MAX_NAMESPACE_LENGTH);
        if (producerNamespace.isEmpty()) {
            producerNamespace = request.source().getNamespace();
        }
        operationKey = bound(operationKey, MAX_OPERATION_KEY_LENGTH);
        producerRevision = Math.max(0L, producerRevision);
    }

    private static String bound(String raw, int max) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String trimmed = raw.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    /**
     * The delivery a plain {@link ReputationRequest} describes: namespace from its source, operation
     * key from its dedupe key. Without a dedupe key there is no operation identity and therefore no
     * receipt, which is exactly how {@code record} has always behaved.
     */
    public static IncidentDelivery of(ReputationRequest request) {
        return new IncidentDelivery(request, null, request.dedupeKey().orElse(null), 0L);
    }

    public static IncidentDelivery of(ReputationRequest request, String producerNamespace,
                                      String operationKey) {
        return new IncidentDelivery(request, producerNamespace, operationKey, 0L);
    }

    /** True when this delivery carries an operation identity, and therefore earns a receipt. */
    public boolean keyed() {
        return !operationKey.isEmpty();
    }
}
