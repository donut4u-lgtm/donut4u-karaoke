
package com.donut4u.karaoke;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.media.audiofx.AudioEffect;
import android.os.Handler;
import android.os.Looper;

public final class Stage3MicrophoneMonitor {

    public interface Listener {
        void onUpdate(int levelPercent, int midiNote,
                      double confidence);
        void onError(String message);
    }

    private static final int SAMPLE_RATE = 22050;
    private static final int FRAME_SIZE = 2048;

    private final Listener listener;
    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private volatile boolean running;

    private AudioRecord recorder;
    private AcousticEchoCanceler echoCanceler;
    private NoiseSuppressor noiseSuppressor;
    private AutomaticGainControl gainControl;

    private Thread worker;

    private volatile boolean echoCancellationEnabled;
    private volatile boolean noiseSuppressionEnabled;

    public Stage3MicrophoneMonitor(Listener listener) {
        this.listener = listener;
    }

    public synchronized boolean start() {
        if (running) return true;

        int minimum = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);

        if (minimum == AudioRecord.ERROR
                || minimum == AudioRecord.ERROR_BAD_VALUE) {
            notifyError("This device cannot open a microphone stream.");
            return false;
        }

        try {
            recorder = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minimum, FRAME_SIZE * 4));

            if (recorder.getState()
                    != AudioRecord.STATE_INITIALIZED) {
                releaseRecorder();
                notifyError("Microphone initialization failed.");
                return false;
            }

            enableAudioProcessing(recorder.getAudioSessionId());

            recorder.startRecording();

            if (recorder.getRecordingState()
                    != AudioRecord.RECORDSTATE_RECORDING) {
                releaseRecorder();
                notifyError("Microphone did not start.");
                return false;
            }

            running = true;

            worker = new Thread(
                    this::recordLoop,
                    "Stage3-Microphone");

            worker.start();
            return true;

        } catch (SecurityException error) {
            releaseRecorder();
            notifyError("Microphone permission was not granted.");
            return false;

        } catch (Exception error) {
            releaseRecorder();
            notifyError("Microphone error: "
                    + error.getMessage());
            return false;
        }
    }

    private void enableAudioProcessing(int sessionId) {

        echoCancellationEnabled = false;
        noiseSuppressionEnabled = false;

        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler =
                        AcousticEchoCanceler.create(sessionId);

                if (echoCanceler != null) {
                    echoCanceler.setEnabled(true);

                    echoCancellationEnabled =
                            echoCanceler.getEnabled();
                }
            }
        } catch (Exception ignored) {
            echoCancellationEnabled = false;
        }

        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor =
                        NoiseSuppressor.create(sessionId);

                if (noiseSuppressor != null) {
                    noiseSuppressor.setEnabled(true);

                    noiseSuppressionEnabled =
                            noiseSuppressor.getEnabled();
                }
            }
        } catch (Exception ignored) {
            noiseSuppressionEnabled = false;
        }

        try {
            if (AutomaticGainControl.isAvailable()) {
                gainControl =
                        AutomaticGainControl.create(sessionId);

                if (gainControl != null) {
                    gainControl.setEnabled(true);
                }
            }
        } catch (Exception ignored) {
            // Continue without automatic gain control.
        }
    }

    private void recordLoop() {

        short[] samples = new short[FRAME_SIZE];

        while (running) {

            AudioRecord active = recorder;

            if (active == null) break;

            int count;

            try {
                count = active.read(
                        samples, 0, samples.length);

            } catch (Exception error) {
                if (running) {
                    notifyError("Microphone read failed: "
                            + error.getMessage());
                }
                break;
            }

            if (count <= 0) {
                if (count == AudioRecord.ERROR_INVALID_OPERATION
                        || count == AudioRecord.ERROR_BAD_VALUE) {

                    if (running) {
                        notifyError("Microphone input unavailable.");
                    }
                    break;
                }

                continue;
            }

            double sum = 0.0;

            for (int i = 0; i < count; i++) {
                double value = samples[i];
                sum += value * value;
            }

            double rms = Math.sqrt(sum / count);

            int level = (int) Math.min(
                    100.0, rms / 240.0);

            int note = -1;
            double confidence = 0.0;

            if (rms >= 180.0 && count >= 1024) {

                double[] detected =
                        detectPitch(samples, count);

                if (detected[0] > 0.0) {

                    note = (int) Math.round(
                            69.0 + 12.0 *
                            (Math.log(detected[0] / 440.0)
                            / Math.log(2.0)));

                    confidence = detected[1];
                }
            }

            final int resultLevel = level;
            final int resultNote = note;
            final double resultConfidence = confidence;

            mainHandler.post(() -> {
                if (running && listener != null) {
                    listener.onUpdate(
                            resultLevel,
                            resultNote,
                            resultConfidence);
                }
            });
        }

        running = false;
        releaseRecorder();
    }

    private double[] detectPitch(short[] data, int count) {

        int minLag = SAMPLE_RATE / 1000;
        int maxLag = SAMPLE_RATE / 75;

        int usable = Math.min(count, FRAME_SIZE);

        if (usable <= maxLag + 32) {
            return new double[]{0.0, 0.0};
        }

        double best = -1.0;
        int bestLag = -1;

        for (int lag = minLag; lag <= maxLag; lag++) {

            double correlation = 0.0;
            double energyA = 0.0;
            double energyB = 0.0;

            int limit = usable - lag;

            for (int i = 0; i < limit; i += 2) {

                double a = data[i];
                double b = data[i + lag];

                correlation += a * b;
                energyA += a * a;
                energyB += b * b;
            }

            double denominator =
                    Math.sqrt(energyA * energyB);

            if (denominator <= 0.0) continue;

            double normalized =
                    correlation / denominator;

            if (normalized > best) {
                best = normalized;
                bestLag = lag;
            }
        }

        if (bestLag <= 0 || best < 0.72) {
            return new double[]{
                    0.0, Math.max(0.0, best)
            };
        }

        return new double[]{
                SAMPLE_RATE / (double) bestLag,
                best
        };
    }

    private void notifyError(String message) {

        mainHandler.post(() -> {
            if (listener != null) {
                listener.onError(message);
            }
        });
    }

    public void stop() {

        running = false;

        AudioRecord active = recorder;

        if (active != null) {
            try {
                if (active.getRecordingState()
                        == AudioRecord.RECORDSTATE_RECORDING) {
                    active.stop();
                }
            } catch (Exception ignored) {
            }
        }

        Thread thread = worker;
        worker = null;

        if (thread != null
                && thread != Thread.currentThread()) {

            try {
                thread.join(500);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }

        releaseRecorder();
    }

    private synchronized void releaseRecorder() {

        AudioRecord active = recorder;
        recorder = null;

        if (active != null) {
            try {
                if (active.getRecordingState()
                        == AudioRecord.RECORDSTATE_RECORDING) {
                    active.stop();
                }
            } catch (Exception ignored) {
            }

            try {
                active.release();
            } catch (Exception ignored) {
            }
        }

        if (echoCanceler != null) {
            try {
                echoCanceler.release();
            } catch (Exception ignored) {
            }
            echoCanceler = null;
        }

        if (noiseSuppressor != null) {
            try {
                noiseSuppressor.release();
            } catch (Exception ignored) {
            }
            noiseSuppressor = null;
        }

        if (gainControl != null) {
            try {
                gainControl.release();
            } catch (Exception ignored) {
            }
            gainControl = null;
        }

        echoCancellationEnabled = false;
        noiseSuppressionEnabled = false;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isEchoCancellationEnabled() {
        return echoCancellationEnabled;
    }

    public boolean isNoiseSuppressionEnabled() {
        return noiseSuppressionEnabled;
    }
}
