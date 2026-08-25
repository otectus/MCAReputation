package dev.otectus.mcareputation.event;

import dev.otectus.mcareputation.McaReputation;
import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.api.ImportResult;
import dev.otectus.mcareputation.api.LegacyImportProvider;
import dev.otectus.mcareputation.api.LegacyImportRequest;
import dev.otectus.mcareputation.reputation.ReputationService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry of {@link LegacyImportProvider}s and the one place they are invoked (spec §32.2).
 *
 * <p>Empty on a standalone install, which is exactly right: with no companion mod there is no legacy
 * standing to inherit and every player honestly starts at zero.
 */
public final class LegacyImportProviders {

    private static final List<LegacyImportProvider> PROVIDERS = new CopyOnWriteArrayList<>();

    private LegacyImportProviders() {
    }

    public static void register(LegacyImportProvider provider) {
        if (provider != null && !PROVIDERS.contains(provider)) {
            PROVIDERS.add(provider);
            McaReputation.LOGGER.info("[MCA: Reputation] registered legacy import provider '{}'",
                    provider.providerName());
        }
    }

    public static void unregister(LegacyImportProvider provider) {
        PROVIDERS.remove(provider);
    }

    public static boolean hasProviders() {
        return !PROVIDERS.isEmpty();
    }

    public static List<String> providerNames() {
        return PROVIDERS.stream().map(LegacyImportProvider::providerName).toList();
    }

    /**
     * Asks every provider for this player's legacy standing and imports whatever comes back.
     *
     * <p>Each provider is isolated: one that throws is logged and skipped, and the others still run.
     * The import itself is idempotent per source id, so calling this on every login is harmless — the
     * second call finds the migration marker and stops.
     *
     * @param force bypass provider eligibility heuristics; set only by the administrative command
     */
    public static List<ImportResult> runFor(MinecraftServer server, ServerPlayer player, boolean force) {
        List<ImportResult> results = new ArrayList<>();
        if (PROVIDERS.isEmpty() || !McaReputationConfig.migrateLegacyQuestsData()) {
            return results;
        }
        for (LegacyImportProvider provider : PROVIDERS) {
            try {
                Optional<LegacyImportRequest> request = provider.buildRequest(server, player, force);
                if (request.isEmpty()) {
                    continue;
                }
                ImportResult result = ReputationService.importLegacy(request.get());
                results.add(result);
                if (result.applied()) {
                    McaReputation.LOGGER.info("[MCA: Reputation] migration from '{}' for {}: {}",
                            provider.providerName(), player.getGameProfile().getName(), result.summary());
                }
            } catch (Throwable t) {
                McaReputation.LOGGER.error("[MCA: Reputation] legacy import provider '{}' threw for {}; "
                                + "skipping it, the player remains eligible to retry",
                        provider.providerName(), player.getGameProfile().getName(), t);
            }
        }
        return results;
    }

    /** Dry run: report what would be imported without writing anything. */
    public static List<ImportResult> previewFor(MinecraftServer server, ServerPlayer player, boolean force) {
        List<ImportResult> results = new ArrayList<>();
        for (LegacyImportProvider provider : PROVIDERS) {
            try {
                provider.buildRequest(server, player, force).ifPresent(request -> {
                    LegacyImportRequest dry = new LegacyImportRequest(request.server(), request.playerId(),
                            request.sourceId(), request.sourceVersion(), request.baselines(),
                            request.tierHighWater(), request.villageTitles(), request.globalTitles(),
                            request.communityNames(), true);
                    results.add(ReputationService.importLegacy(dry));
                });
            } catch (Throwable t) {
                McaReputation.LOGGER.error("[MCA: Reputation] legacy import provider '{}' threw during a "
                        + "dry run for {}", provider.providerName(), player.getGameProfile().getName(), t);
            }
        }
        return results;
    }
}
