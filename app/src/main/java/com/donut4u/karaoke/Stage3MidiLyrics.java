
/**
 * Returns the countdown number for the next lyric cue.
 *
 * The number is based on beats remaining, not the absolute song beat:
 * 5, 4, 3, 2, 1, then 0 when the lyric begins.
 */
public int getCountdownNumberAtTick(long tick) {
    if (events.isEmpty()) return 0;

    long safeTick = Math.max(0L, tick);
    long beat = Math.max(1L, ticksPerQuarterNote);
    int targetLyricIndex = -1;

    // Before the first lyric, count down to the first lyric event.
    int firstLyric = firstLyricIndex();
    if (firstLyric < 0) return 0;

    LyricEvent first = events.get(firstLyric);

    if (safeTick < first.tick) {
        targetLyricIndex = firstLyric;
    } else {
        // Later countdowns are allowed only after a marked
        // instrumental section such as INTRO, SOLO or INTERLUDE.
        int sectionIndex = mostRecentSectionIndex(safeTick);
        if (sectionIndex < 0) return 0;

        LyricEvent section = events.get(sectionIndex);

        if (!isInstrumentalHeading(section.text)) return 0;

        targetLyricIndex = nextLyricIndex(sectionIndex + 1);
    }

    if (targetLyricIndex < 0) return 0;

    long targetTick = events.get(targetLyricIndex).tick;

    if (targetTick <= safeTick) return 0;

    long remainingTicks = targetTick - safeTick;
    long beatsRemaining = remainingTicks / beat;

    if (remainingTicks % beat != 0L) {
        beatsRemaining++;
    }

    if (beatsRemaining < 1L || beatsRemaining > 5L) {
        return 0;
    }

    return (int) beatsRemaining;
}

public boolean shouldShowDotsAtTick(long tick) {
    return getCountdownNumberAtTick(tick) > 0;
}
