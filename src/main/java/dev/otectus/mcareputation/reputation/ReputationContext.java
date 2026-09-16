package dev.otectus.mcareputation.reputation;

import dev.otectus.mcareputation.state.ReputationSavedData;
import net.minecraft.server.MinecraftServer;

/**
 * The read seam every reputation subsystem runs against: one policy snapshot, one evaluation time,
 * one store.
 *
 * <p><b>Internal SPI, not API.</b> This interface is public for exactly one reason: the sibling
 * top-level packages that later phases add — profile evidence and repeat-credit accounting — have to
 * evaluate against the <em>same</em> policy snapshot, the <em>same</em> "now", and the
 * <em>same</em> canonical store as the transaction that accepted the deed, or they answer a different
 * question than the one the ledger recorded. A package-private seam forces those calculators to live
 * inside {@code reputation} or to re-read the config and the clock for themselves, and the second of
 * those is the defect: a mid-operation config reload or a clock read taken twice produces two
 * incompatible answers for one accepted operation (§11.1, I09).
 *
 * <p>It is deliberately <em>not</em> in the api jar's export list and carries no source-compatibility
 * promise. It names an internal mutable type ({@link ReputationSavedData}) on purpose, which is the
 * clearest possible signal that no add-on should be holding one. Add-ons keep calling the public
 * {@code MinecraftServer}-taking entry points on {@link ReputationService} and
 * {@code McaReputationApi}.
 *
 * <p>The full transaction seam — the server-thread assertion, the online-player lookup and the event
 * bus — stays package-private on {@link ServiceContext}. Writing is not part of this view.
 */
public interface ReputationContext {

    /** The canonical store. Production resolves it from the overworld's data storage on each call. */
    ReputationSavedData data();

    /**
     * The current world time: "now", as distinct from the occurrence time a request carries. Backdated
     * delivery needs the two to be separable, and only the context knows the live clock.
     *
     * <p>Read <b>once</b> per operation and passed down; §11.1 requires one evaluation time for the
     * whole staged operation, so an admission preflight and the aging that follows it cannot disagree
     * about what time it is.
     */
    long now();

    /**
     * The policy this operation runs under, read once so a mid-transaction config reload cannot change
     * the rules half way through. Tests inject a fixed snapshot instead.
     */
    default ReputationPolicy policy() {
        return ReputationPolicy.fromConfig();
    }

    /** A read-only view over a live server, for a caller that has no transaction of its own. */
    static ReputationContext of(MinecraftServer server) {
        return ServiceContext.of(server);
    }
}
