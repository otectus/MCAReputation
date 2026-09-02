package dev.otectus.mcareputation.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.otectus.mcareputation.McaReputation;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * The standing screen's sprite sheet and the only place its UV map is written down (spec §28.2).
 *
 * <p>The screen used to be flat {@code fill()} rectangles in a colour scheme of its own, which read
 * as an overlay bolted onto the game rather than part of it. Everything here is instead drawn from
 * {@code textures/gui/reputation.png}, whose frame is pixel-identical to vanilla's container GUI —
 * see {@code tools/GenerateGuiTexture.java}, which produces the sheet and records where each colour
 * was sampled from.
 *
 * <h2>Why nine-slicing</h2>
 *
 * <p>§28.2 requires the panel to shrink to fit at small GUI scales, so a fixed-size container
 * texture is not an option. Every sprite is therefore authored for {@code blitNineSliced}: corners
 * are copied, edges and centres repeat. That keeps the existing responsive layout untouched while
 * still being genuinely texture-based.
 *
 * <p>Three of the sprites are deliberately drawn at exactly their source width or height, which
 * makes {@code blitNineSliced} take its three-slice path and stretch along one axis only — the
 * progress bar and separator horizontally, the scroller vertically. Changing those fixed dimensions
 * without changing the sheet would silently switch the slicing.
 *
 * <p>Blending is enabled before each draw because the panel corners are chamfered — that is, they
 * are transparent — and {@code Screen.renderBackground} leaves blend disabled behind it. Vanilla's
 * own {@code AbstractButton.renderWidget} does the same for the same reason and likewise leaves it
 * enabled afterwards, since font rendering enables it for itself.
 */
final class GuiTextures {

    private static final ResourceLocation SHEET =
            McaReputation.id("textures/gui/reputation.png");

    /** Frame: 24x24 at (0,0), sliced seven in from each side. */
    private static final int PANEL_U = 0;
    private static final int PANEL_V = 0;
    private static final int PANEL_SIZE = 24;
    private static final int PANEL_CORNER = 7;

    /** Sunken well: 16x16 at (32,0), sliced three in from each side. */
    private static final int WELL_U = 32;
    private static final int WELL_V = 0;
    private static final int WELL_SIZE = 16;
    private static final int WELL_CORNER = 3;

    /** Progress track: 8x5 at (0,32), stretched horizontally only. */
    private static final int TRACK_U = 0;
    private static final int TRACK_V = 32;
    private static final int TRACK_WIDTH = 8;
    private static final int TRACK_CAP = 2;

    /** Progress fill: 8x3 at (16,32), stretched horizontally only. */
    private static final int FILL_U = 16;
    private static final int FILL_V = 32;
    private static final int FILL_WIDTH = 8;
    private static final int FILL_CAP = 2;

    /** Scroller channel: 14x16 at (0,48), stretched vertically only. */
    private static final int GROOVE_U = 0;
    private static final int GROOVE_V = 48;
    private static final int GROOVE_HEIGHT = 16;
    private static final int GROOVE_CAP = 3;

    /** Scroller thumb: 12x16 at (16,48), stretched vertically only. */
    private static final int THUMB_U = 16;
    private static final int THUMB_V = 48;
    private static final int THUMB_HEIGHT = 16;
    private static final int THUMB_CAP = 3;

    /** Engraved rule: 8x2 at (0,72), stretched horizontally only. */
    private static final int RULE_U = 0;
    private static final int RULE_V = 72;
    private static final int RULE_WIDTH = 8;
    private static final int RULE_CAP = 2;

    /** Arrow faces: 8x8 each, at (0,80) and (16,80). */
    private static final int ARROW_LEFT_U = 0;
    private static final int ARROW_RIGHT_U = 16;
    private static final int ARROW_V = 80;

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
        blend();
        graphics.blitNineSliced(SHEET, x, y, width, height, PANEL_CORNER,
                PANEL_SIZE, PANEL_SIZE, PANEL_U, PANEL_V);
    }

    /** A sunken area on the frame — the deed ledger sits in one. */
    static void well(GuiGraphics graphics, int x, int y, int width, int height) {
        blend();
        graphics.blitNineSliced(SHEET, x, y, width, height, WELL_CORNER,
                WELL_SIZE, WELL_SIZE, WELL_U, WELL_V);
    }

    /** The progress groove. Height is fixed at {@link #PROGRESS_HEIGHT}. */
    static void progressTrack(GuiGraphics graphics, int x, int y, int width) {
        blend();
        graphics.blitNineSliced(SHEET, x, y, width, PROGRESS_HEIGHT, TRACK_CAP, 0, TRACK_CAP, 0,
                TRACK_WIDTH, PROGRESS_HEIGHT, TRACK_U, TRACK_V);
    }

    /** The filled part, drawn one pixel inside the track so the recess frames it. */
    static void progressFill(GuiGraphics graphics, int x, int y, int width) {
        if (width <= 0) {
            return;
        }
        blend();
        graphics.blitNineSliced(SHEET, x, y, width, PROGRESS_HEIGHT - 2, FILL_CAP, 0, FILL_CAP, 0,
                FILL_WIDTH, PROGRESS_HEIGHT - 2, FILL_U, FILL_V);
    }

    /** The channel the scroller runs in. Width is fixed at {@link #GROOVE_WIDTH}. */
    static void scrollGroove(GuiGraphics graphics, int x, int y, int height) {
        blend();
        graphics.blitNineSliced(SHEET, x, y, GROOVE_WIDTH, height, 0, GROOVE_CAP, 0, GROOVE_CAP,
                GROOVE_WIDTH, GROOVE_HEIGHT, GROOVE_U, GROOVE_V);
    }

    /** The scroller itself. Width is fixed at {@link #THUMB_WIDTH}. */
    static void scrollThumb(GuiGraphics graphics, int x, int y, int height) {
        blend();
        graphics.blitNineSliced(SHEET, x, y, THUMB_WIDTH, height, 0, THUMB_CAP, 0, THUMB_CAP,
                THUMB_WIDTH, THUMB_HEIGHT, THUMB_U, THUMB_V);
    }

    /** An engraved rule between sections. Height is fixed at {@link #RULE_HEIGHT}. */
    static void separator(GuiGraphics graphics, int x, int y, int width) {
        blend();
        graphics.blitNineSliced(SHEET, x, y, width, RULE_HEIGHT, RULE_CAP, 0, RULE_CAP, 0,
                RULE_WIDTH, RULE_HEIGHT, RULE_U, RULE_V);
    }

    /** A button face: a solid triangle over a drop shadow, as vanilla draws its button labels. */
    static void arrow(GuiGraphics graphics, int x, int y, boolean pointsLeft) {
        blend();
        graphics.blit(SHEET, x, y, pointsLeft ? ARROW_LEFT_U : ARROW_RIGHT_U, ARROW_V,
                ARROW_SIZE, ARROW_SIZE);
    }

    private static void blend() {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
    }
}
