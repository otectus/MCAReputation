package dev.otectus.mcareputation.network;

import dev.otectus.mcareputation.reputation.ReputationBounds;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The defensive half of the snapshot packet's codec (spec §18.3, §27.3).
 *
 * <h2>Why this is not inside {@link ReputationNetwork}</h2>
 *
 * <p>{@code ReputationNetwork}'s static initialiser builds a Forge {@code SimpleChannel}, which needs
 * a loaded Forge to exist. Its packet records are nested classes and are deliberately free of any
 * reference to their own outer class's statics, so encoding and decoding stay exercisable in a plain
 * JUnit test with no game running — the same reason {@link SnapshotPaging} is a class of its own. A
 * helper added to the outer class would drag the channel into every codec test, which is exactly how
 * this file came to exist.
 */
final class SnapshotCodec {

    /** Wire length of a tier id, standing or recognition; both are 48 characters at the source. */
    static final int TIER_ID_LENGTH = 48;

    private SnapshotCodec() {
    }

    /**
     * Reads a length-prefixed list, <b>refusing the length before allocating anything</b> (§18.3).
     *
     * <p>{@code FriendlyByteBuf.readList} sizes its list from the claimed count first, so a frame
     * claiming two billion entries costs that allocation before the first element fails to read.
     * Truncating at the encoder is not the same guarantee: it bounds replies this server built and
     * says nothing about a frame somebody else wrote. A negative count is refused for the same
     * reason — it is not a small list, it is a claim that cannot be honoured.
     */
    static <T> List<T> readBounded(FriendlyByteBuf buf, int max, Function<FriendlyByteBuf, T> reader) {
        int size = buf.readVarInt();
        if (size < 0 || size > max) {
            throw new DecoderException("mcareputation: refusing a list of " + size
                    + " entries; at most " + max + " are legal here");
        }
        List<T> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            out.add(reader.apply(buf));
        }
        return out;
    }

    /**
     * A count of deeds behind a value, clamped to what one ledger can hold.
     *
     * <p>The cap is the per-community incident ceiling, because that is the most evidence any facet
     * can actually have. Anything above it is a corrupt or forged frame, and drawing it would put an
     * impossible number in front of a player — or read one aloud to them.
     */
    static int boundedCount(int raw) {
        return Math.max(0, Math.min(ReputationBounds.MAX_INCIDENTS_PER_COMMUNITY, raw));
    }

    /**
     * Writes two optional values inside §18.3's byte budget, degrading them if they do not fit.
     *
     * <p>The per-field limits bound the profile payload's shape. They cannot bound an authored
     * {@link net.minecraft.network.chat.Component}: a datapack may name a facet with a kilobyte of
     * formatted text, and eight of those plus an observer pane would be a packet large enough to
     * disconnect the player it describes. So the pair is encoded into a scratch buffer and measured
     * first; over budget, both are replaced by their degraded forms, which are small by construction
     * rather than by hope.
     *
     * <p>Measuring the real encoding rather than estimating it is the point. An estimate of a
     * {@code Component}'s serialized size is another implementation of the serializer, and the two
     * would disagree on precisely the input that mattered.
     */
    static <A, B> void writeWithinBudget(FriendlyByteBuf buf, Optional<A> first, Optional<B> second,
                                         FriendlyByteBuf.Writer<A> writeFirst,
                                         FriendlyByteBuf.Writer<B> writeSecond,
                                         Function<A, A> degradeFirst, Function<B, B> degradeSecond,
                                         int budgetBytes) {
        FriendlyByteBuf scratch = new FriendlyByteBuf(Unpooled.buffer());
        try {
            scratch.writeOptional(first, writeFirst);
            scratch.writeOptional(second, writeSecond);
            if (scratch.writerIndex() <= budgetBytes) {
                buf.writeBytes(scratch, 0, scratch.writerIndex());
                return;
            }
        } finally {
            scratch.release();
        }
        buf.writeOptional(first.map(degradeFirst), writeFirst);
        buf.writeOptional(second.map(degradeSecond), writeSecond);
    }
}
