package dev.otectus.mcareputation.network;

import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;

/**
 * The server-safe boundary between packet handling and client-only code (spec §11, §36.5).
 *
 * <p>Payload handlers are registered on both physical sides, so their dispatch methods are reachable
 * from a dedicated server's classloader even though they only ever <em>run</em> on a client.
 * Referencing {@code Minecraft} or a {@code Screen} from here risks a {@code NoClassDefFoundError} the
 * moment the JVM decides to verify the method.
 *
 * <p><b>Why an installable sink rather than {@code DistExecutor}.</b> The Forge build used
 * {@code DistExecutor.unsafeRunWhenOn}, whose doubly-nested lambda kept the client class out of this
 * class's constant pool. NeoForge 1.21.1 removed {@code DistExecutor}, and the obvious replacement —
 * importing {@code ClientReputationData} directly and guarding with a dist check — would put every
 * client type it names into this class's constant pool, which is exactly what the rule forbids.
 *
 * <p>So the direction is inverted. This class declares what it needs in terms of its own packet
 * records and nothing else; {@link Sink} mentions no {@code net.minecraft.client} type anywhere in its
 * signatures. {@code ReputationClient} installs a {@code ClientReputationData}-backed implementation
 * during {@code FMLClientSetupEvent}, which only ever runs on a physical client. On a dedicated server
 * nothing installs anything and {@link #NO_OP} stays in place, so the four dispatch methods are
 * harmless no-ops and no client class is ever resolved.
 *
 * <p>Public only because the installer lives in the separate {@code client} package. Nothing outside
 * this mod should call it; the dispatch methods stay package-private so only {@link ReputationNetwork}
 * can reach them.
 */
@ApiStatus.Internal
public final class ClientPacketHandler {

    /**
     * Everything the client half must do with an inbound payload.
     *
     * <p>Deliberately expressed only in this package's record types plus {@code void}. If a
     * {@code Screen}, a {@code Toast}, or {@code Minecraft} ever appears in one of these signatures,
     * the seam is broken and {@code OptionalClassloadTest} fails the build.
     */
    public interface Sink {

        void acceptSnapshot(ReputationNetwork.SnapshotS2C packet);

        void openScreen();

        void acceptChange(ReputationNetwork.ChangeS2C packet);

        void acceptToast(ReputationNetwork.TierToastS2C packet);
    }

    /** What a dedicated server keeps forever, and what a client holds until client setup runs. */
    private static final Sink NO_OP = new Sink() {

        @Override
        public void acceptSnapshot(ReputationNetwork.SnapshotS2C packet) {
        }

        @Override
        public void openScreen() {
        }

        @Override
        public void acceptChange(ReputationNetwork.ChangeS2C packet) {
        }

        @Override
        public void acceptToast(ReputationNetwork.TierToastS2C packet) {
        }
    };

    /**
     * Volatile because installation happens on the client-setup thread while dispatch happens on the
     * client's main thread; without it a client could keep observing the no-op sink after setup.
     */
    private static volatile Sink sink = NO_OP;

    private ClientPacketHandler() {
    }

    /**
     * Installs the client implementation. The only mutation point, called once from
     * {@code ReputationClient} during client setup — before a client can join a world, and without
     * needing a player to exist yet.
     */
    public static void install(Sink implementation) {
        sink = Objects.requireNonNull(implementation, "sink");
    }

    /** Test seam: drop back to the no-op sink so one test's fake cannot leak into the next. */
    static void resetForTest() {
        sink = NO_OP;
    }

    static void acceptSnapshot(ReputationNetwork.SnapshotS2C packet) {
        sink.acceptSnapshot(packet);
    }

    static void openScreen() {
        sink.openScreen();
    }

    static void acceptChange(ReputationNetwork.ChangeS2C packet) {
        sink.acceptChange(packet);
    }

    static void acceptToast(ReputationNetwork.TierToastS2C packet) {
        sink.acceptToast(packet);
    }
}
