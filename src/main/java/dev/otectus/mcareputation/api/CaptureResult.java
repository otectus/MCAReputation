package dev.otectus.mcareputation.api;

import java.util.Map;
import java.util.Objects;

public record CaptureResult(Status status, Map<String, String> payload) {
    public CaptureResult {
        Objects.requireNonNull(status, "status");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public static CaptureResult ready(Map<String, String> payload) { return new CaptureResult(Status.READY, payload); }
    public static CaptureResult ignored(Map<String, String> payload) { return new CaptureResult(Status.IGNORED, payload); }
    public static CaptureResult unmapped(Map<String, String> payload) { return new CaptureResult(Status.UNMAPPED, payload); }
    public static CaptureResult failed(Map<String, String> payload) { return new CaptureResult(Status.FAILED, payload); }

    public enum Status { READY, IGNORED, UNMAPPED, FAILED }
}
