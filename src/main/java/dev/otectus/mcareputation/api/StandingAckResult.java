package dev.otectus.mcareputation.api;

public enum StandingAckResult {
    ACCEPTED,
    UNREGISTERED,
    EPOCH_MISMATCH,
    BEYOND_DURABLE_TAIL,
    UNOBSERVED_PREFIX,
    READ_ONLY,
    INVALID
}
