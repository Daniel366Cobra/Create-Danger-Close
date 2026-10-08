package io.github.daniel366cobra.createdangerclose.ponder.scenes;

/**
 * Text timing in ticks: a text shows {@link #TEXT} ({@link #LONG_TEXT} past {@link #LONG_TEXT_CHARS}
 * characters), then the next follows {@link #GAP} after it fades.
 */
public final class PonderTiming {

    public static final int TEXT = 90;
    public static final int LONG_TEXT = 110;
    public static final int LONG_TEXT_CHARS = 70;
    public static final int GAP = 10;

    public static final int AFTER_TEXT = TEXT + GAP;
    public static final int AFTER_LONG_TEXT = LONG_TEXT + GAP;

    private PonderTiming() {}
}