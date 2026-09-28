package dev.otectus.mcareputation.api;

import java.util.Objects;
import java.util.UUID;

public record StandingRegistration(UUID epoch, long startAfter, long acknowledgedThrough) {
    public StandingRegistration {
        Objects.requireNonNull(epoch, "epoch");
        if (startAfter < 0 || acknowledgedThrough < startAfter) {
            throw new IllegalArgumentException("Invalid standing consumer cursor");
        }
    }
}
