package dev.otectus.mcareputation.client;

/**
 * The standing screen's text colours (spec §28.4).
 *
 * <p>There are only two, and that is the point. Vanilla's container screens label everything in
 * {@code 0x404040} with {@code 0x7F7F7F} for anything secondary, unshadowed, on the light panel
 * face; matching them is most of what makes a modded screen look like part of the game.
 *
 * <p>It also settles §28.4 outright. The screen previously carried meaning in a fifteen-value
 * violet scheme, which put the burden on every future edit to keep colour from becoming the only
 * signal. With two neutral greys there is no polarity left to encode in colour — emphasis is bold
 * or italic, and the sign, status and wording that {@code renderDeed} assembles carry the rest.
 */
final class GuiPalette {

    /** Headings, the tier line, deed text — anything the player is meant to read first. */
    static final int TEXT = 0x404040;

    /** Captions, ages, dimensions, empty states — present, but not competing. */
    static final int TEXT_MUTED = 0x7F7F7F;

    private GuiPalette() {
    }
}
