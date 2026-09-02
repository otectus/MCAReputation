import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Generates {@code assets/mcareputation/textures/gui/reputation.png}, the standing screen's sprite
 * sheet (spec §28.2).
 *
 * <p>Run from the project root with JDK 17+:
 *
 * <pre>java tools/GenerateGuiTexture.java</pre>
 *
 * <p>The art is flat-colour pixel work, exactly as vanilla's own GUI textures are, so it is kept as
 * a program rather than an opaque binary: the frame can be re-derived and reviewed instead of being
 * edited blind in an image editor. Every colour below was sampled from the 1.20.1 client jar —
 * {@code textures/gui/container/generic_54.png} for the panel and the recessed insets,
 * {@code textures/gui/container/creative_inventory/tabs.png} for the scroller, and
 * {@code textures/gui/icons.png} for the progress fill — so the result sits against MCA's screens
 * as a stock container GUI rather than merely a grey one.
 *
 * <p>The sheet is 256x256 because {@code GuiGraphics.blit} and {@code blitNineSliced} assume that
 * size. See {@code GuiTextures} for the UV map and the slicing each sprite is authored for.
 */
public final class GenerateGuiTexture {

    private static final int SHEET = 256;

    // Vanilla's container palette, sampled rather than guessed.
    private static final int CLEAR = 0x00000000;
    private static final int EDGE = 0xFF000000;   // outer outline
    private static final int LIGHT = 0xFFFFFFFF;  // top/left bevel
    private static final int SHADE = 0xFF555555;  // bottom/right bevel
    private static final int FACE = 0xFFC6C6C6;   // panel face
    private static final int MID = 0xFF8B8B8B;    // recessed face, scroller ribbing
    private static final int INSET = 0xFF373737;  // recess top/left

    // The experience bar's greens, for the one element that has to read as "filled".
    private static final int FILL_HI = 0xFF8DC064;
    private static final int FILL_MID = 0xFF5C853B;
    private static final int FILL_LO = 0xFF476F26;

    // Vanilla draws button labels white over a dark drop shadow; the arrow faces match.
    private static final int GLYPH = 0xFFFFFFFF;
    private static final int GLYPH_SHADOW = 0xFF3F3F3F;

    private GenerateGuiTexture() {
    }

    public static void main(String[] args) throws IOException {
        BufferedImage sheet = new BufferedImage(SHEET, SHEET, BufferedImage.TYPE_INT_ARGB);
        panel(sheet, 0, 0, 24, 24);
        recess(sheet, 32, 0, 16, 16, FACE);
        recess(sheet, 0, 32, 8, 5, MID);
        progressFill(sheet, 16, 32);
        recess(sheet, 0, 48, 14, 16, MID);
        scroller(sheet, 16, 48, 12, 16);
        separator(sheet, 0, 72, 8, 2);
        arrow(sheet, 0, 80, true);
        arrow(sheet, 16, 80, false);

        File out = new File(args.length > 0 ? args[0]
                : "src/main/resources/assets/mcareputation/textures/gui/reputation.png");
        ImageIO.write(sheet, "PNG", out);
        System.out.println("wrote " + out.getPath());
    }

    /**
     * The container frame: black outline with a chamfered corner, a two-pixel white bevel along the
     * top and left, a two-pixel dark bevel along the bottom and right, and the face between them.
     *
     * <p>The four corners are not alike, which is the detail a hand-drawn imitation gets wrong.
     * Where the light and dark bevels meet — top-right and bottom-left — vanilla steps the outline
     * out by one pixel and leaves a single face-coloured pixel in the notch, so those two corners
     * are chamfered by three where the other two are chamfered by two.
     */
    private static void panel(BufferedImage image, int u, int v, int w, int h) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int dx = w - 1 - x;
                int dy = h - 1 - y;
                int colour;
                if (x + y < 2 || dx + dy < 2 || dx + y < 3 || x + dy < 3) {
                    colour = CLEAR;
                } else if (x == 0 || y == 0 || dx == 0 || dy == 0
                        || x + y == 2 || dx + dy == 2 || dx + y == 3 || x + dy == 3) {
                    colour = EDGE;
                } else if (dx >= 3 && dy >= 3 && (x <= 2 || y <= 2 || x + y <= 6)) {
                    colour = LIGHT;
                } else if (x >= 3 && y >= 3 && (dx <= 2 || dy <= 2 || dx + dy <= 6)) {
                    colour = SHADE;
                } else {
                    colour = FACE;
                }
                image.setRGB(u + x, v + y, colour);
            }
        }
    }

    /**
     * A sunken area on the panel — the deed well, the progress track, the scroller channel. One
     * pixel of {@link #INSET} along the top and left and one of {@link #LIGHT} along the bottom and
     * right reads as a recess. The face is a parameter because the deed well holds body text and so
     * keeps the panel's own light face for contrast, while the narrow ones take vanilla's darker
     * slot fill.
     */
    private static void recess(BufferedImage image, int u, int v, int w, int h, int face) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int colour;
                if (y == 0) {
                    colour = INSET;
                } else if (y == h - 1) {
                    colour = LIGHT;
                } else if (x == 0) {
                    colour = INSET;
                } else if (x == w - 1) {
                    colour = LIGHT;
                } else {
                    colour = face;
                }
                image.setRGB(u + x, v + y, colour);
            }
        }
    }

    /** Three uniform rows, drawn inside the progress track so the track's recess frames it. */
    private static void progressFill(BufferedImage image, int u, int v) {
        int[] rows = {FILL_HI, FILL_MID, FILL_LO};
        for (int y = 0; y < rows.length; y++) {
            for (int x = 0; x < 8; x++) {
                image.setRGB(u + x, v + y, rows[y]);
            }
        }
    }

    /**
     * Vanilla's creative-inventory scroller, re-authored one pixel taller so its ribbed middle can
     * be tiled to any thumb height. The ribs fall on even rows, and the sliced middle spans rows 3
     * to 12 — ten rows, an even number — so the pattern stays in phase across every repeat.
     */
    private static void scroller(BufferedImage image, int u, int v, int w, int h) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int colour;
                if (y == 0) {
                    colour = x == w - 1 ? MID : LIGHT;
                } else if (y == h - 1) {
                    colour = x == 0 ? MID : SHADE;
                } else if (x == 0) {
                    colour = LIGHT;
                } else if (x == w - 1) {
                    colour = SHADE;
                } else if (y % 2 == 0 && x >= 2 && x <= w - 3) {
                    colour = MID;
                } else {
                    colour = FACE;
                }
                image.setRGB(u + x, v + y, colour);
            }
        }
    }

    /** A two-pixel engraved rule, for the divisions between sections of the panel. */
    private static void separator(BufferedImage image, int u, int v, int w, int h) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                image.setRGB(u + x, v + y, y == 0 ? MID : LIGHT);
            }
        }
    }

    /** A solid triangle four wide and seven tall, over a one-pixel drop shadow. */
    private static void arrow(BufferedImage image, int u, int v, boolean pointsLeft) {
        for (int pass = 0; pass < 2; pass++) {
            for (int y = 0; y < 7; y++) {
                int taper = Math.abs(y - 3);
                for (int x = 0; x < 4; x++) {
                    if (pointsLeft ? x < taper : x > 3 - taper) {
                        continue;
                    }
                    if (pass == 0) {
                        image.setRGB(u + x + 2, v + y + 1, GLYPH_SHADOW);
                    } else {
                        image.setRGB(u + x + 1, v + y, GLYPH);
                    }
                }
            }
        }
    }
}
