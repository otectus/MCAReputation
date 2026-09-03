package dev.otectus.mcareputation.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.otectus.mcareputation.TestPaths;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec §13.4: the standing screen's GUI sprites and the nine-slice records that scale them.
 *
 * <p>This is the check that {@code tools/GenerateGuiTexture.java} and the atlas agree. A sprite
 * whose {@code .mcmeta} claims a size the PNG does not have still loads — the atlas simply slices
 * at the wrong pixels — so the frame comes out subtly wrong rather than missing, which is the
 * failure mode no runtime error catches and no unit test would catch either unless it read both
 * files off disk and compared them.
 *
 * <p>Read from the source tree through {@link TestPaths} rather than the classpath, because the
 * PNG's own dimensions are half of what is being asserted.
 */
class GuiSpriteMetadataTest {

    private static final String SPRITE_DIR =
            "assets/mcareputation/textures/gui/sprites/reputation";

    /** A sprite's authored size and its nine-slice border, straight from the §13.4 table. */
    private record Sliced(int width, int height, int left, int right, int top, int bottom) {
    }

    private static final Map<String, Sliced> SLICED = new LinkedHashMap<>(Map.of(
            "panel", new Sliced(24, 24, 7, 7, 7, 7),
            "well", new Sliced(16, 16, 3, 3, 3, 3),
            "progress_track", new Sliced(8, 5, 2, 2, 0, 0),
            "progress_fill", new Sliced(8, 3, 2, 2, 0, 0),
            "scroll_groove", new Sliced(14, 16, 0, 0, 3, 3),
            "scroll_thumb", new Sliced(12, 16, 0, 0, 3, 3),
            "separator", new Sliced(8, 2, 2, 2, 0, 0)));

    /** The two button faces: fixed size, default stretch, deliberately no metadata. */
    private static final List<String> ARROWS = List.of("arrow_left", "arrow_right");

    private static Path spriteDir() {
        return TestPaths.mainResources().resolve(SPRITE_DIR);
    }

    @Test
    void everyNineSliceSpriteMatchesItsMetadata() throws IOException {
        for (Map.Entry<String, Sliced> entry : SLICED.entrySet()) {
            String name = entry.getKey();
            Sliced expected = entry.getValue();

            BufferedImage image = read(name);
            assertEquals(expected.width(), image.getWidth(), name + ".png width");
            assertEquals(expected.height(), image.getHeight(), name + ".png height");

            Path meta = spriteDir().resolve(name + ".png.mcmeta");
            assertTrue(Files.isRegularFile(meta), "missing " + meta);
            JsonObject scaling = JsonParser
                    .parseString(Files.readString(meta, StandardCharsets.UTF_8))
                    .getAsJsonObject()
                    .getAsJsonObject("gui")
                    .getAsJsonObject("scaling");

            assertEquals("nine_slice", scaling.get("type").getAsString(), name + " scaling type");
            assertEquals(image.getWidth(), scaling.get("width").getAsInt(),
                    name + " metadata width must match the PNG");
            assertEquals(image.getHeight(), scaling.get("height").getAsInt(),
                    name + " metadata height must match the PNG");

            JsonObject border = scaling.getAsJsonObject("border");
            assertEquals(expected.left(), border.get("left").getAsInt(), name + " border.left");
            assertEquals(expected.right(), border.get("right").getAsInt(), name + " border.right");
            assertEquals(expected.top(), border.get("top").getAsInt(), name + " border.top");
            assertEquals(expected.bottom(), border.get("bottom").getAsInt(),
                    name + " border.bottom");

            // stretch_inner is not part of the 1.21.1 nine-slice record; an unknown field would
            // fail the codec at pack load, which is a crash on entering the screen.
            assertFalse(scaling.has("stretch_inner"),
                    name + " must not declare stretch_inner (spec §13.4)");
        }
    }

    @Test
    void arrowsAreEightPixelsSquareAndCarryNoMetadata() throws IOException {
        for (String name : ARROWS) {
            BufferedImage image = read(name);
            assertEquals(8, image.getWidth(), name + ".png width");
            assertEquals(8, image.getHeight(), name + ".png height");
            assertFalse(Files.exists(spriteDir().resolve(name + ".png.mcmeta")),
                    name + " is a fixed-size button face and must stretch by default");
        }
    }

    /**
     * The 1.20.1 sheet must not survive alongside the sprites cut from it. Two sources for one
     * frame is how a texture edit lands in the file nothing reads.
     */
    @Test
    void theOldSheetIsGone() {
        Path sheet = TestPaths.mainResources()
                .resolve("assets/mcareputation/textures/gui/reputation.png");
        assertFalse(Files.exists(sheet),
                "the 1.20.1 sprite sheet is superseded by " + SPRITE_DIR);
    }

    private static BufferedImage read(String name) throws IOException {
        Path png = spriteDir().resolve(name + ".png");
        assertTrue(Files.isRegularFile(png), "missing " + png);
        BufferedImage image;
        try (var input = Files.newInputStream(png)) {
            image = ImageIO.read(input);
        }
        assertTrue(image != null, "not a readable PNG: " + png);
        return image;
    }
}
