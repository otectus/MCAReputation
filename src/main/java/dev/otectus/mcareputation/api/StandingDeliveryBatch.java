package dev.otectus.mcareputation.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record StandingDeliveryBatch(Status status, UUID epoch, long durableTail,
                                    long trimmedThrough, List<StandingDelivery> deliveries) {
    public StandingDeliveryBatch {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(epoch, "epoch");
        deliveries = deliveries == null ? List.of() : List.copyOf(deliveries);
    }

    public enum Status { READY, UNREGISTERED, EPOCH_MISMATCH, GAP, INVALID }
}
