package dev.otectus.mcareputation.util;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * Optional datapack fields that <b>report</b> a malformed value instead of quietly substituting the
 * default.
 *
 * <h2>Why this exists</h2>
 *
 * <p>DataFixerUpper's {@code Codec.optionalFieldOf} treats "the field failed to parse" and "the field
 * is absent" as the same thing: both yield the default. For a datapack format that is the wrong
 * trade. A pack author who writes
 *
 * <pre>{@code "resolution": { "atoned": 1.5 }}</pre>
 *
 * would get the <em>default</em> resolution policy and no diagnostic at all — the incident would
 * silently behave differently from what the file says, which is precisely the class of bug §15 and
 * §21.3 require to be "reported and skipped" rather than absorbed.
 *
 * <p>{@link #strictOptional} keeps absence lenient and makes malformation loud: a missing field is
 * still {@code Optional.empty()} (or the supplied default), while a present-but-invalid one produces
 * a real parse error naming the field.
 *
 * <p>Hand-rolled because 1.20.1 had no {@code ExtraCodecs.strictOptionalField}. 1.21.1 does ship an
 * equivalent, but this class is deliberately kept: its error messages name the offending field and
 * resource, and the content-validation tests assert that wording. Retiring it is a separate change
 * from the loader port.
 */
public final class StrictCodecs {

    private StrictCodecs() {
    }

    /** Absent → empty; present and valid → value; present and invalid → error. */
    public static <A> MapCodec<Optional<A>> strictOptional(com.mojang.serialization.Codec<A> codec,
                                                           String name) {
        return new MapCodec<>() {

            @Override
            public <T> DataResult<Optional<A>> decode(DynamicOps<T> ops, MapLike<T> input) {
                T value = input.get(name);
                if (value == null) {
                    return DataResult.success(Optional.empty());
                }
                return codec.parse(ops, value)
                        .map(Optional::of)
                        .mapError(error -> "field '" + name + "': " + error);
            }

            @Override
            public <T> RecordBuilder<T> encode(Optional<A> input, DynamicOps<T> ops,
                                               RecordBuilder<T> prefix) {
                return input.map(value -> prefix.add(name, codec.encodeStart(ops, value))).orElse(prefix);
            }

            @Override
            public <T> Stream<T> keys(DynamicOps<T> ops) {
                return Stream.of(ops.createString(name));
            }

            @Override
            public String toString() {
                return "StrictOptional[" + name + "]";
            }
        };
    }

    /** Absent → {@code fallback}; present and valid → value; present and invalid → error. */
    public static <A> MapCodec<A> strictOptional(com.mojang.serialization.Codec<A> codec, String name,
                                                 A fallback) {
        return strictOptional(codec, name).xmap(
                value -> value.orElse(fallback),
                value -> value == null || value.equals(fallback) ? Optional.empty() : Optional.of(value));
    }
}
