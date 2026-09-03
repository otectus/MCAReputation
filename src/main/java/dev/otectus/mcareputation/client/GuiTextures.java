package dev.otectus.mcareputation.client;

import dev.otectus.mcareputation.McaReputation;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * The standing screen's GUI sprites, and the only place their ids are written down (spec §13.4).
 *
 * <p>The screen used to be flat {@code fill()} rectangles in a colour scheme of its own, which read
 * as an overlay bolted onto the game rather than part of it. Everything here is instead drawn from
 * {@code textures/gui/sprites/reputation/}, whose frame is pixel-identical to vanilla's container
 * GUI — see {@code tools/GenerateGuiTexture.java}, which produces the sprites and records where
 * each colour was sampled from.
 *
 * <h2>Where the nine-slicing lives</h2>
 *
 * <p>§28.2 requires the panel to shrink to fit at small GUI scales, so a fixed-size container
 * texture is not an option. In 1.20.1 the slicing was an argument list at every call site, passed
 * to {@code blitNineSliced} along with UVs into one 256x256 sheet. 1.21.1 stitches GUI sprites into
 * an atlas that carries their scaling with them: each sprite's {@code .png.mcmeta} declares the
 * {@code nine_slice} record, and every draw below is a single {@code blitSprite} that takes only a
 * destination rectangle. Change the borders in the metadata, not here.
 *
 * <p>Three of the sprites have a zero border on one axis, which is what makes the atlas stretch
 * along the other axis only — the progress bar and separator horizontally, the scroller vertically.
 * The fixed dimensions those pieces are drawn at are the constants below, which the screen's layout
 * reads; changing one without changing its metadata would silently change the slicing.
 *
 * <p>No manual {@code RenderSystem.enableBlend()}: the sprite renderer sets its own blend state, so
 * the chamfered — that is, transparent — panel corners come out right without the caller helping.
 */
final class GuiTextures {

    private static final ResourceLocation PANEL = McaReputation.id("reputation/panel");
    private static final ResourceLocation WELL = McaReputation.id("reputation/well");
    private static final ResourceLocation PROGRESS_TRACK =
            McaReputation.id("reputation/progress_track");
    private static final ResourceLocation PROGRESS_FILL =
            McaReputation.id("reputation/progress_fill");
    private static final ResourceLocation SCROLL_GROOVE =
            McaReputation.id("reputation/scroll_groove");
    private static final ResourceLocation SCROLL_THUMB =
            McaReputation.id("reputation/scroll_thumb");
    private static final ResourceLocation SEPARATOR = McaReputation.id("reputation/separator");
    private static final ResourceLocation ARROW_LEFT = McaReputation.id("reputation/arrow_left");
    private static final ResourceLocation ARROW_RIGHT = McaReputation.id("reputation/arrow_right");

    /** The progress track's fixed height; the fill sits one pixel inside it on both axes. */
    static final int PROGRESS_HEIGHT = 5;

    /** The scroller channel's fixed width, and the thumb's, which runs one pixel inside it. */
    static final int GROOVE_WIDTH = 14;
    static final int THUMB_WIDTH = 12;

    /** The engraved rule's fixed height. */
    static final int RULE_HEIGHT = 2;

    /** The arrow faces are square. */
    static final int ARROW_SIZE = 8;

    private GuiTextures() {
    }

    /** The container frame, at any size down to twice the corner. */
    static void panel(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.blitSprite(PANEL, x, y, width, height);
    }

    /** A sunken area on the frame — the deed ledger sits in one. */
    static void well(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.blitSprite(WELL, x, y, width, height);
    }

    /** The progress groove. Height is fixed at {@link #PROGRESS_HEIGHT}. */
    static void progressTrack(GuiGraphics graphics, int x, int y, int width) {
        graphics.blitSprite(PROGRESS_TRACK, x, y, width, PROGRESS_HEIGHT);
    }

    /** The filled part, drawn one pixel inside the track so the recess frames it. */
    static void progressFill(GuiGraphics graphics, int x, int y, int width) {
        if (width <= 0) {
            return;
        }
        graphics.blitSprite(PROGRESS_FILL, x, y, width, PROGRESS_HEIGHT - 2);
    }

    /** The channel the scroller runs in. Width is fixed at {@link #GROOVE_WIDTH}. */
    static void scrollGroove(GuiGraphics graphics, int x, int y, int height) {
        graphics.blitSprite(SCROLL_GROOVE, x, y, GROOVE_WIDTH, height);
    }

    /** The scroller itself. Width is fixed at {@link #THUMB_WIDTH}. */
    static void scrollThumb(GuiGraphics graphics, int x, int y, int height) {
        graphics.blitSprite(SCROLL_THUMB, x, y, THUMB_WIDTH, height);
    }

    /** An engraved rule between sections. Height is fixed at {@link #RULE_HEIGHT}. */
    static void separator(GuiGraphics graphics, int x, int y, int width) {
        graphics.blitSprite(SEPARATOR, x, y, width, RULE_HEIGHT);
    }

    /** A button face: a solid triangle over a drop shadow, as vanilla draws its button labels. */
    static void arrow(GuiGraphics graphics, int x, int y, boolean pointsLeft) {
        graphics.blitSprite(pointsLeft ? ARROW_LEFT : ARROW_RIGHT, x, y, ARROW_SIZE, ARROW_SIZE);
    }
}
