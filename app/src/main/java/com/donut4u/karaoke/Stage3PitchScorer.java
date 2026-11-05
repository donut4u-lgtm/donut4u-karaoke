package com.donut4u.karaoke;

/**
 * Stage 3 karaoke pitch scoring.
 *
 * Compares the expected MIDI melody pitch with the
 * pitch detected by Stage3MicrophoneMonitor.
 *
 * This class does not control audio playback.
 */
public final class Stage3PitchScorer {

    private static final double MIN_CONFIDENCE = 0.72;

    private int totalFrames;
    private int exactMatches;
    private int closeMatches;
    private int missedFrames;

    private long totalPoints;

    /**
     * Records one microphone measurement while a melody
     * note is active.
     *
     * @param expectedNote expected MIDI pitch, or -1 if none
     * @param detectedNote microphone MIDI pitch, or -1 if none
     * @param confidence pitch detector confidence, 0.0 to 1.0
     *
     * @return points awarded for this frame, from 0 to 100
     */
    public synchronized int recordFrame(
            int expectedNote,
            int detectedNote,
            double confidence) {

        if (expectedNote < 0 || expectedNote > 127) {
            return -1;
        }

        totalFrames++;

        int points = 0;

        if (detectedNote < 0
                || detectedNote > 127
                || confidence < MIN_CONFIDENCE) {

            missedFrames++;

        } else {

            int difference = Math.abs(
                    expectedNote - detectedNote);

            if (difference == 0) {

                points = 100;
                exactMatches++;

            } else if (difference == 1) {

                points = 70;
                closeMatches++;

            } else if (difference == 2) {

                points = 35;

            } else {

                missedFrames++;
            }
        }

        totalPoints += points;

        return points;
    }

    /**
     * Current score expressed as a percentage from 0 to 100.
     */
    public synchronized int getScore() {

        if (totalFrames == 0) {
            return 0;
        }

        return (int) Math.round(
                totalPoints / (double) totalFrames);
    }

    public synchronized int getTotalFrames() {
        return totalFrames;
    }

    public synchronized int getExactMatches() {
        return exactMatches;
    }

    public synchronized int getCloseMatches() {
        return closeMatches;
    }

    public synchronized int getMissedFrames() {
        return missedFrames;
    }

    /**
     * Returns the percentage of exact pitch matches.
     */
    public synchronized int getExactAccuracy() {

        if (totalFrames == 0) {
            return 0;
        }

        return (int) Math.round(
                exactMatches * 100.0 / totalFrames);
    }

    /**
     * Returns a performance label based on the score.
     */
    public synchronized String getRating() {

        int score = getScore();

        if (totalFrames == 0) {
            return "NOT SCORED";
        }

        if (score >= 95) {
            return "SUPERSTAR";
        }

        if (score >= 85) {
            return "EXCELLENT";
        }

        if (score >= 75) {
            return "VERY GOOD";
        }

        if (score >= 60) {
            return "GOOD";
        }

        if (score >= 40) {
            return "KEEP PRACTICING";
        }

        return "KEEP SINGING";
    }

    /**
     * Clears the score before a new song.
     */
    public synchronized void reset() {

        totalFrames = 0;
        exactMatches = 0;
        closeMatches = 0;
        missedFrames = 0;
        totalPoints = 0;
    }

    public synchronized boolean hasScore() {
        return totalFrames > 0;
    }
}
