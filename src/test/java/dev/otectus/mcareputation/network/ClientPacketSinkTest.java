package dev.otectus.mcareputation.network;

import dev.otectus.mcareputation.TestFixtures;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * §36.5: the replacement for Forge's {@code DistExecutor} must actually dispatch.
 *
 * <p>{@code OptionalClassloadTest} proves the seam names no client type. This proves the other half —
 * that all four server-to-client payloads reach the installed sink, and that with nothing installed
 * (a dedicated server, which never runs client setup) they are silent no-ops rather than a crash.
 */
class ClientPacketSinkTest {

    /** Records what it was handed, in order, so dispatch can be checked rather than assumed. */
    private static final class RecordingSink implements ClientPacketHandler.Sink {

        private final List<String> calls = new ArrayList<>();

        @Override
        public void acceptSnapshot(ReputationNetwork.SnapshotS2C packet) {
            calls.add("snapshot:" + packet.communities().size());
        }

        @Override
        public void openScreen() {
            calls.add("openScreen");
        }

        @Override
        public void acceptChange(ReputationNetwork.ChangeS2C packet) {
            calls.add("change:" + packet.delta());
        }

        @Override
        public void acceptToast(ReputationNetwork.TierToastS2C packet) {
            calls.add("toast:" + packet.tierName().getString());
        }
    }

    @AfterEach
    void restoreTheNoOpSink() {
        // Static state: one test's fake must never leak into the next, or into a dedicated-server
        // assertion that expects the default.
        ClientPacketHandler.resetForTest();
    }

    private static ReputationNetwork.SnapshotS2C snapshot() {
        return new ReputationNetwork.SnapshotS2C(
                List.of(new ReputationNetwork.CommunitySummary(
                        TestFixtures.OVERWORLD_3, "Riverbend", 90, "friend")),
                Optional.empty(), List.of());
    }

    @Test
    void allFourPathsReachTheInstalledSinkInOrder() {
        RecordingSink sink = new RecordingSink();
        ClientPacketHandler.install(sink);

        ClientPacketHandler.acceptSnapshot(snapshot());
        ClientPacketHandler.openScreen();
        ClientPacketHandler.acceptChange(new ReputationNetwork.ChangeS2C(
                Component.literal("Riverbend"), -12, Component.literal("Wary"), true, true, false));
        ClientPacketHandler.acceptToast(new ReputationNetwork.TierToastS2C(
                Component.literal("Riverbend"), Component.literal("Honored")));

        assertEquals(List.of("snapshot:1", "openScreen", "change:-12", "toast:Honored"), sink.calls);
    }

    @Test
    void installingASecondSinkReplacesTheFirst() {
        RecordingSink first = new RecordingSink();
        RecordingSink second = new RecordingSink();
        ClientPacketHandler.install(first);
        ClientPacketHandler.install(second);

        ClientPacketHandler.openScreen();

        assertEquals(List.of(), first.calls, "the replaced sink must stop receiving");
        assertEquals(List.of("openScreen"), second.calls);
    }

    /** A dedicated server never runs client setup, so nothing is ever installed there. */
    @Test
    void withNothingInstalledEveryPathIsASilentNoOp() {
        ClientPacketHandler.resetForTest();
        ClientPacketHandler.acceptSnapshot(snapshot());
        ClientPacketHandler.openScreen();
        ClientPacketHandler.acceptChange(new ReputationNetwork.ChangeS2C(
                Component.literal("Riverbend"), 5, Component.literal("Friend"), false, false, false));
        ClientPacketHandler.acceptToast(new ReputationNetwork.TierToastS2C(
                Component.literal("Riverbend"), Component.literal("Friend")));
        // Reaching here without a NullPointerException or NoClassDefFoundError is the assertion.
    }

    @Test
    void installingNullIsRejectedRatherThanSilentlyDisablingDispatch() {
        assertThrows(NullPointerException.class, () -> ClientPacketHandler.install(null));
    }

    /** The sink type is part of the seam's contract, so it must stay reachable and client-free. */
    @Test
    void theSinkInterfaceIsPublicAndNamesOnlyThisModsPayloads() throws Exception {
        Class<?> sink = ClientPacketHandler.Sink.class;
        assertNotNull(sink);
        assertEquals(4, sink.getDeclaredMethods().length, "the seam is exactly four operations");
        for (var method : sink.getDeclaredMethods()) {
            for (Class<?> parameter : method.getParameterTypes()) {
                assertSame(ReputationNetwork.class, parameter.getEnclosingClass(),
                        () -> "Sink." + method.getName() + " must take only this mod's payload records, "
                                + "not " + parameter.getName());
            }
        }
    }
}
