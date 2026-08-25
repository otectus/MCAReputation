package dev.otectus.mcareputation.compat;

import net.conczin.mca.client.gui.InteractScreen;
import net.minecraft.client.gui.screens.Screen;

/**
 * The client-side half of the MCA compatibility layer: recognising MCA's own screens (spec §28.1).
 *
 * <p>Kept in {@code compat} with the rest of the MCA imports, so §11's rule — only this package may
 * touch {@code net.conczin.mca.*} — holds on the client too. Client-only by construction; nothing on a
 * dedicated server references it.
 *
 * <p><b>Why a type check and not a mixin.</b> {@code InteractScreen} keeps its villager in a private
 * field with no accessor, so reading it would need an accessor mixin — and an accessor cannot be made
 * optional, so a field rename in a future MCA would stop the game from starting. Instead the button is
 * added through Forge's {@code ScreenEvent.Init.Post} and the villager's identity comes from the
 * client's own record of what the player just interacted with, which the server then validates anyway
 * (§27.2). The worst case if MCA reshapes this screen is that the button stops appearing.
 */
public final class McaScreenCompat {

    private McaScreenCompat() {
    }

    /** True when this is MCA's villager interaction screen. */
    public static boolean isVillagerInteractScreen(Screen screen) {
        try {
            return screen instanceof InteractScreen;
        } catch (Throwable t) {
            // A LinkageError here means MCA's screen class has moved; degrade to "no button".
            return false;
        }
    }
}
