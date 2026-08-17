package dev.otectus.mcareputation.util;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Shared, case-insensitive enum parsing for datapack fields and NBT.
 *
 * <p>Two different behaviours are needed and they must not be confused:
 *
 * <ul>
 *   <li><b>Datapack parse</b> ({@link #codec}) is <em>strict</em>: an unknown value is a validation
 *       error naming the offending token and every legal one, because §21.3 requires invalid
 *       definitions to be reported and skipped rather than silently reinterpreted.</li>
 *   <li><b>Saved-data parse</b> ({@link #byName}) is <em>lenient</em>: §13.6 requires an unknown
 *       enum string in an existing world to fall back safely instead of discarding the record it
 *       belongs to. Callers supply the fallback and log a warning themselves.</li>
 * </ul>
 */
public final class EnumCodecs {

    private EnumCodecs() {
    }

    /** Lowercase {@code name()} — the wire/JSON spelling for every enum in this mod. */
    public static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    /** Strict, case-insensitive codec for datapack fields. */
    public static <E extends Enum<E>> Codec<E> codec(Class<E> type, Function<E, String> nameOf) {
        String legal = Arrays.stream(type.getEnumConstants())
                .map(nameOf)
                .collect(Collectors.joining(", "));
        return Codec.STRING.comapFlatMap(
                raw -> byName(type, nameOf, raw)
                        .map(DataResult::success)
                        .orElseGet(() -> DataResult.error(
                                () -> "Unknown " + type.getSimpleName() + ": '" + raw + "' (expected one of: " + legal + ")")),
                nameOf);
    }

    /** Strict, case-insensitive codec using the default lowercase spelling. */
    public static <E extends Enum<E>> Codec<E> codec(Class<E> type) {
        return codec(type, EnumCodecs::lower);
    }

    /** Lenient, case-insensitive lookup. Empty when nothing matches; never throws. */
    public static <E extends Enum<E>> Optional<E> byName(Class<E> type, Function<E, String> nameOf, String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String needle = raw.strip().toLowerCase(Locale.ROOT);
        for (E constant : type.getEnumConstants()) {
            if (nameOf.apply(constant).equalsIgnoreCase(needle) || constant.name().equalsIgnoreCase(needle)) {
                return Optional.of(constant);
            }
        }
        return Optional.empty();
    }

    /** Lenient lookup using the default lowercase spelling. */
    public static <E extends Enum<E>> Optional<E> byName(Class<E> type, String raw) {
        return byName(type, EnumCodecs::lower, raw);
    }

    /** Lenient lookup with a caller-supplied fallback, for reading saved data (§13.6). */
    public static <E extends Enum<E>> E byNameOr(Class<E> type, String raw, E fallback) {
        return byName(type, raw).orElse(fallback);
    }
}
