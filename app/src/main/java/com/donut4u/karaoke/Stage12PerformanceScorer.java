package com.donut4u.karaoke;

/**
 * Shared voice-performance scorer for Stage 1 and Stage 2.
 *
 * Measures pitch stability and vocal control.
 * Does not claim exact melody matching.
 *
 * Stage 3 remains independent and unchanged.
 */
public final class Stage12PerformanceScorer {

    private static final double MIN_CONFIDENCE = 0.72;
    private static final int MIN_MIC_LEVEL = 8;

    private long voicedFrames;
    private long pitchFrames;

    private double pitchPoints;
    private double possiblePitchPoints;

    private double controlPoints;
    private double possibleControlPoints;

    private int previousNote = -1;
    private int bestStreak;
    private int currentStreak;

    private int lastNote = -1;
    private int lastLevel;

    public Stage12PerformanceScorer() {
        reset();
    }

    /**
     * Call approximately every 100 milliseconds
     * while karaoke playback is active.
     */
    public synchronized void update(
            int microphoneLevel,
            int detectedNote,
            double confidence) {

        lastLevel = microphoneLevel;
        lastNote = detectedNote;

        if (microphoneLevel < MIN_MIC_LEVEL) {
            return;
        }

        voicedFrames++;

        // Vocal control: reward a usable,
        // controlled microphone input level.
        possibleControlPoints += 100.0;

        double controlScore;

        if (microphoneLevel >= 15
                && microphoneLevel <= 80) {

            controlScore = 100.0;

        } else if (microphoneLevel >= 10
                && microphoneLevel <= 90) {

            controlScore = 75.0;

        } else {

            controlScore = 40.0;
        }

        controlPoints += controlScore;

        // Ignore unreliable pitch detections.
        if (detectedNote < 0
                || confidence < MIN_CONFIDENCE
                || confidence > 1.0) {

            previousNote = -1;
            currentStreak = 0;
            return;
        }

        pitchFrames++;

        if (previousNote >= 0) {

            int difference = Math.abs(
                    detectedNote - previousNote);

            double points;

            if (difference <= 1) {
                points = 100.0;
                currentStreak++;

            } else if (difference == 2) {
                points = 75.0;
                currentStreak = 0;

            } else if (difference == 3) {
                points = 50.0;
                currentStreak = 0;

            } else if (difference == 4) {
                points = 25.0;
                currentStreak = 0;

            } else {
                points = 0.0;
                currentStreak = 0;
            }

            possiblePitchPoints += 100.0;
            pitchPoints += points;

            bestStreak = Math.max(
                    bestStreak,
                    currentStreak);
        }

        previousNote = detectedNote;
    }

    public synchronized void reset() {

        voicedFrames = 0;
        pitchFrames = 0;

        pitchPoints = 0.0;
        possiblePitchPoints = 0.0;

        controlPoints = 0.0;
        possibleControlPoints = 0.0;

        previousNote = -1;
        bestStreak = 0;
        currentStreak = 0;

        lastNote = -1;
        lastLevel = 0;
    }

    public synchronized int getScorePercent() {

        if (possiblePitchPoints <= 0.0
                || possibleControlPoints <= 0.0) {
            return 0;
        }

        double pitchScore =
                pitchPoints / possiblePitchPoints;

        double controlScore =
                controlPoints / possibleControlPoints;

        double finalScore =
                (pitchScore * 0.70)
                + (controlScore * 0.30);

        return (int) Math.round(
                Math.max(0.0,
                        Math.min(100.0, finalScore * 100.0)));
    }

    public synchronized int getPitchStabilityPercent() {

        if (possiblePitchPoints <= 0.0) {
            return 0;
        }

        return (int) Math.round(
                pitchPoints / possiblePitchPoints * 100.0);
    }

    public synchronized int getVocalControlPercent() {

        if (possibleControlPoints <= 0.0) {
            return 0;
        }

        return (int) Math.round(
                controlPoints / possibleControlPoints * 100.0);
    }

    public synchronized int getBestStreak() {
        return bestStreak;
    }

    public synchronized int getCurrentStreak() {
        return currentStreak;
    }

    public synchronized long getVoicedFrames() {
        return voicedFrames;
    }

    public synchronized long getPitchFrames() {
        return pitchFrames;
    }

    public synchronized int getLastNote() {
        return lastNote;
    }

    public synchronized int getLastLevel() {
        return lastLevel;
    }

    public synchronized boolean hasScored() {
        return possiblePitchPoints > 0.0;
    }

    public synchronized String getRating() {

        if (!hasScored()) {
            return "WAITING FOR SINGING";
        }

        int score = getScorePercent();

        if (score >= 95) return "SUPERSTAR";
        if (score >= 85) return "EXCELLENT";
        if (score >= 70) return "VERY GOOD";
        if (score >= 55) return "GOOD";
        if (score >= 40) return "KEEP PRACTICING";

        return "TRY AGAIN";
    }
}
