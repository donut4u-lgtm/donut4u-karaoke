
package com.donut4u.karaoke;

public final class Stage3KaraokeScorer {

    private static final double MIN_CONFIDENCE = 0.78;
    private static final int MIN_MIC_LEVEL = 8;

    private long evaluatedFrames;
    private long matchedFrames;
    private long perfectFrames;

    private double earnedPoints;
    private double possiblePoints;

    private int consecutiveMatches;
    private int bestStreak;

    private int lastExpectedNote = -1;
    private int lastDetectedNote = -1;

    private double lastConfidence;

    public Stage3KaraokeScorer() {
        reset();
    }

    /**
     * Compare the singer's detected MIDI pitch
     * with the expected MIDI melody pitch.
     *
     * Call approximately every 100 milliseconds.
     */
    public synchronized void update(
            int expectedNote,
            int detectedNote,
            double confidence,
            int microphoneLevel) {

        lastExpectedNote = expectedNote;
        lastDetectedNote = detectedNote;
        lastConfidence = confidence;

        // No melody note is active.
        if (expectedNote < 0) {
            consecutiveMatches = 0;
            return;
        }

        // Ignore frames with insufficient microphone input.
        if (microphoneLevel < MIN_MIC_LEVEL) {
            consecutiveMatches = 0;
            return;
        }

        // A frame with weak or unreliable pitch detection
        // is not accepted as a singing match.
        if (detectedNote < 0
                || confidence < MIN_CONFIDENCE
                || confidence > 1.0) {

            evaluatedFrames++;

            possiblePoints += 100.0;

            consecutiveMatches = 0;

            return;
        }

        evaluatedFrames++;

        possiblePoints += 100.0;

        int difference = Math.abs(
                expectedNote - detectedNote);

        double points = calculatePoints(difference);

        earnedPoints += points;

        if (points >= 99.0) {

            perfectFrames++;
            matchedFrames++;

            consecutiveMatches++;

            bestStreak = Math.max(
                    bestStreak,
                    consecutiveMatches);

        } else if (points > 0.0) {

            matchedFrames++;

            consecutiveMatches = 0;

        } else {

            consecutiveMatches = 0;
        }
    }

    private double calculatePoints(int difference) {

        if (difference <= 1) {
            return 100.0;
        }

        if (difference == 2) {
            return 70.0;
        }

        if (difference == 3) {
            return 40.0;
        }

        if (difference == 4) {
            return 15.0;
        }

        return 0.0;
    }

    public synchronized void reset() {

        evaluatedFrames = 0;
        matchedFrames = 0;
        perfectFrames = 0;

        earnedPoints = 0.0;
        possiblePoints = 0.0;

        consecutiveMatches = 0;
        bestStreak = 0;

        lastExpectedNote = -1;
        lastDetectedNote = -1;

        lastConfidence = 0.0;
    }

    public synchronized int getScorePercent() {

        if (possiblePoints <= 0.0) {
            return 0;
        }

        return (int) Math.round(
                Math.min(
                        100.0,
                        earnedPoints / possiblePoints * 100.0));
    }

    public synchronized long getEvaluatedFrames() {
        return evaluatedFrames;
    }

    public synchronized long getMatchedFrames() {
        return matchedFrames;
    }

    public synchronized long getPerfectFrames() {
        return perfectFrames;
    }

    public synchronized int getCurrentStreak() {
        return consecutiveMatches;
    }

    public synchronized int getBestStreak() {
        return bestStreak;
    }

    public synchronized int getLastExpectedNote() {
        return lastExpectedNote;
    }

    public synchronized int getLastDetectedNote() {
        return lastDetectedNote;
    }

    public synchronized double getLastConfidence() {
        return lastConfidence;
    }

    public synchronized String getRating() {

        int score = getScorePercent();

        if (possiblePoints <= 0.0) {
            return "NOT SCORED";
        }

        if (score >= 95) {
            return "SUPERSTAR";
        }

        if (score >= 85) {
            return "EXCELLENT";
        }

        if (score >= 70) {
            return "VERY GOOD";
        }

        if (score >= 55) {
            return "GOOD";
        }

        if (score >= 40) {
            return "KEEP PRACTICING";
        }

        return "TRY AGAIN";
    }

    public synchronized boolean hasScored() {
        return possiblePoints > 0.0;
    }
}
