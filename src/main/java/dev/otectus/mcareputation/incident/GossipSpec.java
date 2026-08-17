package dev.otectus.mcareputation.incident;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.otectus.mcareputation.util.StrictCodecs;

import java.util.List;
import java.util.Optional;

/**
 * How an incident should sound when a villager brings it up (spec §15 {@code gossip} block).
 *
 * <p>Reputation owns the <em>fact</em> — that this happened, and that this villager knows it. It does
 * not own the <em>wording</em>: §4 assigns dialogue voice to MCA: Conversations, which reads this
 * block through the bridge and renders it in the speaker's personality and locale. Everything here is
 * therefore a key or a hint, never a finished sentence, and none of it is required.
 *
 * <p>{@code tone} is a free-form authored label ({@code condemnation}, {@code praise}, {@code relief},
 * {@code gratitude}, …) that Conversations maps onto its own line pools; unknown tones fall back to a
 * neutral pool rather than failing. {@code phrase} is a translation key. {@code with} names which
 * template variables to bind as component arguments, capped at four to match the normalized gossip
 * candidate's argument limit in §30.4.
 */
public record GossipSpec(Optional<String> tone, Optional<String> phrase, List<String> with) {

    public static final GossipSpec NONE = new GossipSpec(Optional.empty(), Optional.empty(), List.of());

    /** §30.4: external gossip arguments may exceed the old fixed subject pair but are capped at four. */
    public static final int MAX_ARGUMENTS = 4;

    public static final int MAX_TONE_LENGTH = 32;
    public static final int MAX_PHRASE_LENGTH = 128;

    public static final Codec<GossipSpec> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            StrictCodecs.strictOptional(Codec.STRING, "tone").forGetter(GossipSpec::tone),
            StrictCodecs.strictOptional(Codec.STRING, "phrase").forGetter(GossipSpec::phrase),
            StrictCodecs.strictOptional(Codec.STRING.listOf(), "with", List.of()).forGetter(GossipSpec::with)
    ).apply(instance, GossipSpec::new));

    public GossipSpec {
        tone = bound(tone, MAX_TONE_LENGTH);
        phrase = bound(phrase, MAX_PHRASE_LENGTH);
        with = with == null ? List.of()
                : List.copyOf(with.stream().limit(MAX_ARGUMENTS).map(String::strip).filter(s -> !s.isEmpty()).toList());
    }

    private static Optional<String> bound(Optional<String> raw, int max) {
        if (raw == null) {
            return Optional.empty();
        }
        return raw.map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(s -> s.length() <= max ? s : s.substring(0, max));
    }

    /** True when this incident is worth offering to Conversations as a tellable story at all. */
    public boolean isTellable() {
        return phrase.isPresent();
    }
}
