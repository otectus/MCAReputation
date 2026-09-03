package dev.otectus.mcareputation.compat;

import net.minecraft.client.gui.screens.Screen;

/**
 * The client-side half of the MCA compatibility layer: recognising MCA's own screens (spec §28.1).
 *
 * <p>Client-only by construction; nothing on a dedicated server references it. The probe deliberately
 * lives here rather than in {@link McaReflect}, because {@code McaReflect} is initialised on a
 * dedicated server and must never ask a server's classloader for a client class.
 *
 * <p>Resolved by name for the same reason as everything else in this package: MCA renamed its base
 * package from {@code net.mca} to {@code net.conczin.mca} in 7.7.1, so an {@code import} would bind
 * this class to one MCA generation. See {@link McaReflect} for the full account.
 *
 * <p><b>Why a type check and not a mixin.</b> {@code InteractScreen} keeps its villager in a private
 * field with no accessor, so reading it would need an accessor mixin — and an accessor cannot be made
 * optional, so a field rename in a future MCA would stop the game from starting. Instead the button is
 * added through NeoForge's {@code ScreenEvent.Init.Post} and the villager's identity comes from the
 * client's own record of what the player just interacted with, which the server then validates anyway
 * (§27.2). The worst case if MCA reshapes this screen is that the button stops appearing.
 */
public final class McaScreenCompat {

    /** Null when no supported MCA is present; the button then simply never appears. */
    private static final Class<?> INTERACT_SCREEN = probe();

    private McaScreenCompat() {
    }

    private static Class<?> probe() {
        for (String root : McaReflect.SUPPORTED_ROOTS) {
            try {
                return Class.forName(root + ".client.gui.InteractScreen", false,
                        McaScreenCompat.class.getClassLoader());
            } catch (Throwable t) {
                // Wrong root, or MCA absent. Try the next one; a miss is not an error here.
            }
        }
        return null;
    }

    /** True when this is MCA's villager interaction screen. */
    public static boolean isVillagerInteractScreen(Screen screen) {
        return INTERACT_SCREEN != null && INTERACT_SCREEN.isInstance(screen);
    }
}
