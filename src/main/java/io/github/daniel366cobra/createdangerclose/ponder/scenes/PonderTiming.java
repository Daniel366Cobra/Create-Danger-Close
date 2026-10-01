package io.github.daniel366cobra.createdangerclose.ponder.scenes;

/**
 * Shared text timing for this mod's Ponder scenes, in ticks (20 per second).
 * A text block shows for {@link #TEXT} ticks ({@link #LONG_TEXT} if its text
 * is over {@link #LONG_TEXT_CHARS} characters); the next block appears
 * {@link #GAP} ticks after it fades out. So after a text,
 * a scene idles for {@link #AFTER_TEXT} or {@link #AFTER_LONG_TEXT}.
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