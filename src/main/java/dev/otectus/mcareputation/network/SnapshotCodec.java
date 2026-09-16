package dev.otectus.mcareputation.network;

import dev.otectus.mcareputation.reputation.ReputationBounds;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The defensive half of the snapshot payload's codec (spec §18.3, §27.3).
 *
 * <h2>Why this is not inside {@link ReputationNetwork}</h2>
 *
 * <p>The Forge line put it here because {@code ReputationNetwork}'s static initialiser built a
 * {@code SimpleChannel}, so any helper on the outer class dragged a loaded Forge into every codec
 * test. That specific hazard does not exist on this branch: registration happens inside
 * {@link ReputationNetwork#register}, and the outer class holds no channel. The file is kept anyway,
 * for two reasons that do hold here. The byte-budget measurement allocates a scratch buffer and has
 * to be reasoned about on its own, away from the request throttle and the payload registrar; and the
 * Forge line and this one stay file-for-file comparable, which is what makes the next port a diff
 * rather than an excavation.
 *
 * <p>Unlike Forge's version these helpers take {@link RegistryFriendlyByteBuf}: a profile pane
 * carries authored {@link net.minecraft.network.chat.Component}s, and component encoding needs
 * registry context in 1.21.1. {@code ReputationNetwork.readBoundedList} is the branch's existing
 * bounded decode and stays the entry point for the lists that predate this phase, so there is one
 * place a count is refused rather than two that could disagree about a limit.
 */
final class SnapshotCodec {

    /** Wire length of a tier id, standing or recognition; both are 48 characters at the source. */
    static final int TIER_ID_LENGTH = 48;

    private SnapshotCodec() {
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
     *
     * <p>The scratch buffer is a {@link RegistryFriendlyByteBuf} over the destination's own
     * {@link RegistryAccess}, so what is measured is the bytes that will actually travel. A plain
     * scratch buffer would throw on the first component instead of measuring it — which is the one
     * thing this port had to change about the Forge original.
     */
    static <A, B> void writeWithinBudget(RegistryFriendlyByteBuf buf, Optional<A> first,
                                         Optional<B> second,
                                         BiConsumer<RegistryFriendlyByteBuf, A> writeFirst,
                                         BiConsumer<RegistryFriendlyByteBuf, B> writeSecond,
                                         Function<A, A> degradeFirst, Function<B, B> degradeSecond,
                                         int budgetBytes) {
        RegistryFriendlyByteBuf scratch =
                new RegistryFriendlyByteBuf(Unpooled.buffer(), buf.registryAccess());
        try {
            writeOptional(scratch, first, writeFirst);
            writeOptional(scratch, second, writeSecond);
            if (scratch.writerIndex() <= budgetBytes) {
                buf.writeBytes(scratch, 0, scratch.writerIndex());
                return;
            }
        } finally {
            scratch.release();
        }
        writeOptional(buf, first.map(degradeFirst), writeFirst);
        writeOptional(buf, second.map(degradeSecond), writeSecond);
    }

    /**
     * The optional framing the panes are written with: one boolean, then the value.
     *
     * <p>Spelled out here rather than borrowed from {@link ReputationNetwork} because it has to be
     * callable on the scratch buffer as well as the outbound one, and the two framings must agree
     * byte for byte. Keeping it one boolean in both places is what makes that agreement checkable by
     * reading; {@code SnapshotPacketTest} round-trips the pair to make it checkable by running.
     */
    private static <T> void writeOptional(RegistryFriendlyByteBuf buf, Optional<T> value,
                                          BiConsumer<RegistryFriendlyByteBuf, T> writer) {
        buf.writeBoolean(value.isPresent());
        value.ifPresent(present -> writer.accept(buf, present));
    }

    /**
     * Refuses a length before anything is allocated, for a caller with no label to hand.
     *
     * <p>{@code FriendlyByteBuf.readList} sizes its list from the claimed count first, so a frame
     * claiming two billion entries costs that allocation before the first element fails to read.
     * Truncating at the encoder is not the same guarantee: it bounds replies this server built and
     * says nothing about a frame somebody else wrote. A negative count is refused for the same
     * reason — it is not a small list, it is a claim that cannot be honoured.
     */
    static <T> List<T> readBounded(RegistryFriendlyByteBuf buf, int max,
                                   Function<RegistryFriendlyByteBuf, T> reader) {
        int size = buf.readVarInt();
        if (size < 0 || size > max) {
            throw new DecoderException("mcareputation: refusing a list of " + size
                    + " entries; at most " + max + " are legal here");
        }
        List<T> out = new ArrayList<>(Math.min(size, 16));
        for (int i = 0; i < size; i++) {
            out.add(reader.apply(buf));
        }
        return List.copyOf(out);
    }
}
