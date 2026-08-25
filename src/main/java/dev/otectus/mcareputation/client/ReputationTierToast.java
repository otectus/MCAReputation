package dev.otectus.mcareputation.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * The toast shown the first time a player reaches a new best standing with a village (§17.3, §28.3).
 *
 * <p>Shown only for an upward transition that beats the stored high-water mark. Oscillating around a
 * threshold, or re-earning a tier after a fall, is not a milestone and gets nothing — which is what
 * keeps the toast meaning something.
 */
public final class ReputationTierToast implements Toast {

    private static final long DISPLAY_TIME_MS = 5000L;

    /**
     * Vanilla's advancement-toast frame, addressed as a GUI sprite.
     *
     * <p>1.20.1's {@code Toast.TEXTURE} atlas constant is gone in 1.21.1; toast backgrounds are
     * sprites now, and each vanilla toast names its own. This is the same frame the advancement
     * toast uses, which is the point: a standing milestone should sit visually alongside the game's
     * other "you achieved something" notifications rather than introduce an unrelated frame.
     */
    private static final ResourceLocation BACKGROUND_SPRITE =
            ResourceLocation.withDefaultNamespace("toast/advancement");

    private final Component communityName;
    private final Component tierName;

    public ReputationTierToast(Component communityName, Component tierName) {
        this.communityName = communityName;
        this.tierName = tierName;
    }

    @Override
    public Visibility render(GuiGraphics graphics, ToastComponent toasts, long timeSinceLastVisible) {
        graphics.blitSprite(BACKGROUND_SPRITE, 0, 0, width(), height());
        graphics.drawString(toasts.getMinecraft().font,
                Component.translatable("mcareputation.toast.title"), 30, 7, 0xFF88FF, false);
        graphics.drawString(toasts.getMinecraft().font,
                Component.translatable("mcareputation.toast.body", tierName, communityName),
                30, 18, 0xFFFFFF, false);
        return timeSinceLastVisible >= DISPLAY_TIME_MS ? Visibility.HIDE : Visibility.SHOW;
    }

    @Override
    public Object getToken() {
        // One token per community so simultaneous milestones with two villages both show, rather than
        // the second replacing the first.
        return communityName.getString();
    }
}
