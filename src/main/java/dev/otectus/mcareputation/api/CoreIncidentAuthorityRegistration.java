package dev.otectus.mcareputation.api;

import net.minecraft.resources.ResourceLocation;

/**
 * The handle returned by
 * {@link McaReputationApi#registerCoreIncidentAuthority(CoreIncidentAuthority)}.
 *
 * <p>Closing it withdraws the claim, and Reputation's native detector resumes for every kind that
 * authority owned. Closing twice is harmless. A registration that was rejected (duplicate id, null
 * authority) still returns a handle — a closed, inert one — so a caller never has to null-check.
 *
 * <p>{@link AutoCloseable#close()} is narrowed to throw nothing: withdrawing a claim can always
 * succeed, and a bridge shutting down should not have to handle an exception.
 */
public interface CoreIncidentAuthorityRegistration extends AutoCloseable {

    /** The id this registration was made under. */
    ResourceLocation authorityId();

    /** Whether this registration is currently active. False once closed, or if it was rejected. */
    boolean isActive();

    /** Withdraws the claim. Idempotent. */
    @Override
    void close();
}
