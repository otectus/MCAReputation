package dev.otectus.mcareputation.reputation;

import dev.otectus.mcareputation.state.ReputationSavedData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.Event;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * The only places the reputation transaction touches a live server, behind one seam.
 *
 * <p>§11 requires the score/tier/decay/resolution logic to be testable without a running game, and the
 * transaction in {@link ReputationService} is the one piece of that logic that must also reach the
 * server: the thread assertion, the canonical store, the online-player lookup for events and names,
 * and the Forge event bus. Everything else it needs is already pure. Funnelling those four touches
 * through this interface is what lets {@code ReputationServiceTest} run the <em>real</em> transaction —
 * ordering, dedupe, containment and all — against an in-memory store and a recording bus.
 *
 * <p>Deliberately package-private: this is a seam, not API. Integrations keep calling the public
 * {@code MinecraftServer}-taking entry points, which wrap themselves in {@link #of}.
 */
interface ServiceContext {

    boolean isServerThread();

    /** The canonical store. Production resolves it from the overworld's data storage on each call. */
    ReputationSavedData data();

    @Nullable
    ServerPlayer onlinePlayer(UUID playerId);

    /**
     * Posts on the event bus, propagating whatever a listener throws. Callers that must survive a
     * broken listener wrap this in {@link ReputationService#postSafely}; the raw method exists so the
     * tests can simulate exactly that listener.
     */
    void post(Event event);

    static ServiceContext of(MinecraftServer server) {
        return new ServiceContext() {
            @Override
            public boolean isServerThread() {
                return server.isSameThread();
            }

            @Override
            public ReputationSavedData data() {
                return ReputationSavedData.get(server);
            }

            @Override
            @Nullable
            public ServerPlayer onlinePlayer(UUID playerId) {
                return playerId == null ? null : server.getPlayerList().getPlayer(playerId);
            }

            @Override
            public void post(Event event) {
                MinecraftForge.EVENT_BUS.post(event);
            }
        };
    }
}
