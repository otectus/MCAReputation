package dev.otectus.mcareputation.network;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

/**
 * The server-safe boundary between packet handling and client-only code (spec §11, §36.5).
 *
 * <p>Packet classes are registered on both physical sides, so their {@code handle} methods are
 * reachable from a dedicated server's classloader even though they only ever <em>run</em> on a client.
 * Referencing {@code Minecraft} or a {@code Screen} directly from there risks a
 * {@code NoClassDefFoundError} the moment the JVM decides to verify the method.
 *
 * <p>Every hop into client code therefore goes through {@link DistExecutor#unsafeRunWhenOn}, whose
 * doubly-nested lambda keeps the client class out of this class's constant pool: the inner
 * {@code Runnable} is only ever constructed on a client. This is the standard Forge idiom, and it is
 * what makes "no client class reference from dedicated-server initialisation" true rather than hoped
 * for.
 */
final class ClientPacketHandler {

    private ClientPacketHandler() {
    }

    static void acceptSnapshot(ReputationNetwork.SnapshotS2C packet) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> dev.otectus.mcareputation.client.ClientReputationData.acceptSnapshot(packet));
    }

    static void openScreen() {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> dev.otectus.mcareputation.client.ClientReputationData.openScreen());
    }

    static void acceptChange(ReputationNetwork.ChangeS2C packet) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> dev.otectus.mcareputation.client.ClientReputationData.acceptChange(packet));
    }

    static void acceptToast(ReputationNetwork.TierToastS2C packet) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> dev.otectus.mcareputation.client.ClientReputationData.acceptToast(packet));
    }
}
