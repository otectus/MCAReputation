package dev.otectus.mcareputation.api.profile;

import java.util.Optional;

/**
 * One profile answer: whether it is usable, the value when it is, and a bounded machine-readable
 * reason when it is not (§14.2, §14.3).
 *
 * <p>§14.3 is explicit that an authored access condition must use the detailed result rather than a
 * convenience zero: "no recognition" and "we cannot say" are different facts and a quest gate that
 * conflates them either blocks a legitimate player or admits an unproven one. {@link #reason} is a
 * short stable token (for example {@code no_speaker_context}, {@code partial_legacy_history}),
 * intended for logs and diagnostics, never for display.
 *
 * @param <T> the answered value
 * @since MCA: Reputation 0.6.0
 */
public record ProfileQueryResult<T>(ProfileAvailability availability, Optional<T> value,
                                    Optional<String> reason) {

    /** Longest reason token this type will carry; longer ones are truncated rather than rejected. */
    public static final int MAX_REASON_LENGTH = 64;

    public ProfileQueryResult {
        availability = availability == null ? ProfileAvailability.ERROR : availability;
        value = value == null ? Optional.empty() : value;
        reason = reason == null ? Optional.empty() : reason.map(ProfileQueryResult::bound);
    }

    private static String bound(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.length() <= MAX_REASON_LENGTH) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_REASON_LENGTH);
    }

    public static <T> ProfileQueryResult<T> available(T value) {
        return new ProfileQueryResult<>(ProfileAvailability.AVAILABLE, Optional.of(value),
                Optional.empty());
    }

    public static <T> ProfileQueryResult<T> unavailable(ProfileAvailability availability, String reason) {
        return new ProfileQueryResult<>(
                availability == null || availability == ProfileAvailability.AVAILABLE
                        ? ProfileAvailability.UNRESOLVED
                        : availability,
                Optional.empty(), Optional.ofNullable(reason));
    }

    public boolean isAvailable() {
        return availability.isAvailable() && value.isPresent();
    }

    /** The value, or {@code fallback} for every unavailable answer. Never guesses. */
    public T orElse(T fallback) {
        return value.orElse(fallback);
    }
}
