package dev.otectus.mcareputation.api;

/**
 * The handle returned when a {@link CoreIncidentAuthority} is registered, and the only way to revoke it.
 *
 * <p>A handle rather than an {@code unregister(authority)} method because revocation has to be
 * unambiguous. Companions commonly register an anonymous or short-lived implementation, and asking
 * them to hold onto the exact instance for an equality-based removal is the kind of contract that
 * works until a mod reloads its config and quietly leaks a claim that nothing can any longer withdraw.
 * A claim that cannot be withdrawn suppresses this mod's detection forever.
 *
 * <p>Extends {@link AutoCloseable} with the checked exception removed, so a claim can be held in a
 * try-with-resources block during a reload without a pointless {@code catch}.
 *
 * @since MCA: Reputation 0.3.0
 */
public interface CoreIncidentAuthorityRegistration extends AutoCloseable {

    /** The authority this handle registered. */
    CoreIncidentAuthority authority();

    /**
     * Whether the claim still stands.
     *
     * <p>False once {@link #close()} has been called. It never becomes false on its own: this reports
     * whether the <em>registration</em> is live, not whether the authority currently
     * {@linkplain CoreIncidentAuthority#owns owns} anything — those are different questions, and a
     * companion whose config has temporarily switched its detection off is still registered.
     */
    boolean isActive();

    /**
     * Withdraws the claim. Idempotent: closing twice is a no-op, never an error.
     *
     * <p>Detection returns to this mod on the next event.
     */
    @Override
    void close();
}
