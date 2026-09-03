import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Generates the standing screen's GUI sprites under
 * {@code assets/mcareputation/textures/gui/sprites/reputation/} (spec §13.4).
 *
 * <p>Run from the project root with JDK 21:
 *
 * <pre>java tools/GenerateGuiTexture.java</pre>
 *
 * <p>The art is flat-colour pixel work, exactly as vanilla's own GUI textures are, so it is kept as
 * a program rather than an opaque binary: the frame can be re-derived and reviewed instead of being
 * edited blind in an image editor. Every colour below was sampled from the client jar —
 * {@code textures/gui/container/generic_54.png} for the panel and the recessed insets,
 * {@code textures/gui/container/creative_inventory/tabs.png} for the scroller, and
 * {@code textures/gui/icons.png} for the progress fill — so the result sits against MCA's screens
 * as a stock container GUI rather than merely a grey one.
 *
 * <p>1.20.1 packed all nine pieces into one 256x256 sheet, because {@code blitNineSliced} took UVs
 * into a fixed-size texture. 1.21.1 stitches GUI sprites into an atlas instead, so each piece is
 * its own PNG at its own size and the slicing is declared in a sibling {@code .png.mcmeta} rather
 * than passed at the call site. The painters below are unchanged from the sheet era; only where
 * they draw has moved, from an offset in the sheet to the origin of a right-sized image.
 *
 * <p>The output is deterministic: the same painters, the same sizes, the same bytes.
 */
public final class GenerateGuiTexture {

    /** The default output directory, relative to the project root. */
    private static final String OUT_DIR =
            "src/main/resources/assets/mcareputation/textures/gui/sprites/reputation";


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
        File dir = new File(args.length > 0 ? args[0] : OUT_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir.getPath());
        }

        write(dir, "panel", draw(24, 24, image -> panel(image, 0, 0, 24, 24)), 7, 7, 7, 7);
        write(dir, "well", draw(16, 16, image -> recess(image, 0, 0, 16, 16, FACE)), 3, 3, 3, 3);
        write(dir, "progress_track", draw(8, 5, image -> recess(image, 0, 0, 8, 5, MID)),
                2, 2, 0, 0);
        write(dir, "progress_fill", draw(8, 3, image -> progressFill(image, 0, 0)), 2, 2, 0, 0);
        write(dir, "scroll_groove", draw(14, 16, image -> recess(image, 0, 0, 14, 16, MID)),
                0, 0, 3, 3);
        write(dir, "scroll_thumb", draw(12, 16, image -> scroller(image, 0, 0, 12, 16)),
                0, 0, 3, 3);
        write(dir, "separator", draw(8, 2, image -> separator(image, 0, 0, 8, 2)), 2, 2, 0, 0);

        // The arrows are button faces at a fixed size, so they stretch by default and carry no
        // metadata at all.
        write(dir, "arrow_left", draw(8, 8, image -> arrow(image, 0, 0, true)));
        write(dir, "arrow_right", draw(8, 8, image -> arrow(image, 0, 0, false)));
    }

    /** A transparent image of exactly the sprite's size, handed to one of the painters. */
    private static BufferedImage draw(int width, int height, Painter painter) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        painter.paint(image);
        return image;
    }

    /** Writes a sprite with no scaling metadata. */
    private static void write(File dir, String name, BufferedImage image) throws IOException {
        File png = new File(dir, name + ".png");
        ImageIO.write(image, "PNG", png);
        System.out.println("wrote " + png.getPath());
    }

    /**
     * Writes a sprite and the {@code nine_slice} record the GUI atlas reads its slicing from.
     *
     * <p>No {@code stretch_inner}: the field is not part of the 1.21.1 nine-slice record, and the
     * three pieces that are authored at exactly their source width or height take the atlas's
     * three-slice path from a zero border rather than from a flag.
     */
    private static void write(File dir, String name, BufferedImage image,
                              int left, int right, int top, int bottom) throws IOException {
        write(dir, name, image);
        File meta = new File(dir, name + ".png.mcmeta");
        String json = "{\"gui\":{\"scaling\":{\"type\":\"nine_slice\",\"width\":" + image.getWidth()
                + ",\"height\":" + image.getHeight() + ",\"border\":{\"left\":" + left
                + ",\"right\":" + right + ",\"top\":" + top + ",\"bottom\":" + bottom + "}}}}\n";
        Files.writeString(meta.toPath(), json, StandardCharsets.UTF_8);
        System.out.println("wrote " + meta.getPath());
    }

    /** One piece's painter, so {@link #draw} can own the image and the painters need not. */
    @FunctionalInterface
    private interface Painter {
        void paint(BufferedImage image);
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
