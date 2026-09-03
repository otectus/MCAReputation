package dev.otectus.mcareputation.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * A vanilla button whose label is a sprite rather than text (spec §28.2).
 *
 * <p>The community selector used to be two buttons captioned with the literal characters
 * {@code <} and {@code >}, which is the one part of a Minecraft screen that never looks native.
 * This draws the arrow faces from {@link GuiTextures} instead.
 *
 * <p>Only {@link #renderString} is overridden, which is the whole trick: vanilla's
 * {@code AbstractButton.renderWidget} draws the button frame from {@code widgets.png} and then
 * calls {@code renderString} for the label, so replacing just that one step keeps the real vanilla
 * chrome — hover, focus and disabled states, the click sound, resource-pack compatibility — and
 * swaps only what sits on top of it. Reproducing the frame by hand would have meant duplicating
 * {@code getTextureY()}, which is private.
 *
 * <p>The message is kept rather than emptied, because with no visible caption it is the only thing
 * a screen reader has to go on (§28.4). It doubles as the tooltip, so a sighted player gets the
 * same words on hover.
 */
final class SpriteButton extends Button {

    private final boolean pointsLeft;

    private SpriteButton(int x, int y, int width, int height, Component narration,
                         boolean pointsLeft, OnPress onPress) {
        super(x, y, width, height, narration, onPress, DEFAULT_NARRATION);
        this.pointsLeft = pointsLeft;
        setTooltip(Tooltip.create(narration));
    }

    static SpriteButton arrow(int x, int y, int width, int height, Component narration,
                              boolean pointsLeft, OnPress onPress) {
        return new SpriteButton(x, y, width, height, narration, pointsLeft, onPress);
    }

    @Override
    public void renderString(GuiGraphics graphics, Font font, int colour) {
        GuiTextures.arrow(graphics,
                getX() + (getWidth() - GuiTextures.ARROW_SIZE) / 2,
                getY() + (getHeight() - GuiTextures.ARROW_SIZE) / 2,
                pointsLeft);
    }
}
