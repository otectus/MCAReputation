package dev.otectus.mcareputation.api;

import java.util.Objects;

public record StandingDelivery(StandingEnvelope envelope, CaptureResult capture) {
    public StandingDelivery {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(capture, "capture");
    }
}
