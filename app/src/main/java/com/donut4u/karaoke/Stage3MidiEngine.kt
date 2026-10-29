
package com.donut4u.karaoke

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper

import dev.kotlinds.fluidsynthkmp.MidiFilePlayer

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.Future

class Stage3MidiEngine private constructor(
    private val context: Context
) {

    interface Callback {
        fun onReady(durationTicks: Long)
        fun onError(message: String)
        fun onFinished()
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var midiPlayer: MidiFilePlayer? = null

    @Volatile
    private var closed = false

    private var activeCallback: Callback? = null
    private var activeTask: Future<*>? = null

    companion object {
        private const val MIDI_DIRECTORY = "stage3_midi"
        private const val SOUNDFONT_DIRECTORY = "stage3_soundfonts"
        private const val DEFAULT_SOUNDFONT = "builtin://default"

        @JvmStatic
        fun create(context: Context): Stage3MidiEngine {
            return Stage3MidiEngine(context.applicationContext)
        }
    }

    @Synchronized
    fun play(
        midiUri: String,
        soundFontUri: String,
        callback: Callback
    ) {
        stopCurrentTask()

        closed = false
        activeCallback = callback

        activeTask = worker.submit {
            try {
                if (midiUri.isBlank()) {
                    throw IllegalArgumentException(
                        "No MIDI/KAR song was selected."
                    )
                }

                val midiFile = copyUriToFile(
                    midiUri,
                    MIDI_DIRECTORY,
                    "song.mid"
                )

                val soundFontFile = if (
                    soundFontUri.isBlank() ||
                    soundFontUri == DEFAULT_SOUNDFONT
                ) {
                    copyDefaultSoundFont()
                } else {
                    copyUriToFile(
                        soundFontUri,
                        SOUNDFONT_DIRECTORY,
                        "soundfont.sf2"
                    )
                }

                if (!midiFile.exists() || midiFile.length() == 0L) {
                    throw IllegalStateException(
                        "The selected MIDI/KAR file is empty or unavailable."
                    )
                }

                if (!soundFontFile.exists() ||
                    soundFontFile.length() == 0L
                ) {
                    throw IllegalStateException(
                        "The default SoundFont is missing or empty. " +
                        "Add app/src/main/assets/default.sf2 to the project."
                    )
                }

                val newPlayer = MidiFilePlayer(
                    soundFontFile.absolutePath,
                    midiFile.absolutePath
                )

                synchronized(this) {
                    if (closed) {
                        newPlayer.close()
                        return@submit
                    }
                    midiPlayer = newPlayer
                }

                newPlayer.play {
                    mainHandler.post {
                        if (!closed) {
                            activeCallback?.onFinished()
                        }
                    }
                }

                mainHandler.post {
                    if (!closed) {
                        activeCallback?.onReady(
                            newPlayer.durationTicks
                        )
                    }
                }
            } catch (error: Exception) {
                val message = error.message
                    ?: "Could not start MIDI playback."

                mainHandler.post {
                    if (!closed) {
                        activeCallback?.onError(message)
                    }
                }
            }
        }
    }

    private fun copyDefaultSoundFont(): File {
        val directory = File(context.filesDir, SOUNDFONT_DIRECTORY)

        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException(
                "Could not create the SoundFont storage directory."
            )
        }

        val destination = File(directory, "default.sf2")

        if (destination.exists() && destination.length() > 0L) {
            return destination
        }

        val temporary = File(directory, "default.sf2.tmp")

        context.assets.open("default.sf2").use { input ->
            FileOutputStream(temporary).use { output ->
                input.copyTo(output)
                output.flush()
            }
        }

        if (temporary.length() == 0L) {
            temporary.delete()
            throw IllegalStateException(
                "The bundled default SoundFont is empty."
            )
        }

        if (destination.exists()) {
            destination.delete()
        }

        if (!temporary.renameTo(destination)) {
            temporary.delete()
            throw IllegalStateException(
                "Could not install the default SoundFont."
            )
        }

        return destination
    }

    fun pause() {
        midiPlayer?.pause()
    }

    fun resume() {
        midiPlayer?.play()
    }

    fun seekTo(tick: Long) {
        midiPlayer?.seekTo(tick.coerceAtLeast(0L))
    }

    fun getCurrentTick(): Long {
        return midiPlayer?.currentTick ?: 0L
    }

    fun getDurationTicks(): Long {
        return midiPlayer?.durationTicks ?: 0L
    }

    fun isPlaying(): Boolean {
        return midiPlayer?.isPlaying ?: false
    }

    @Synchronized
    fun stop() {
        stopCurrentTask()
    }

    private fun stopCurrentTask() {
        activeTask?.cancel(true)
        activeTask = null

        try {
            midiPlayer?.stop()
        } catch (_: Exception) {
        }

        try {
            midiPlayer?.close()
        } catch (_: Exception) {
        }

        midiPlayer = null
    }

    private fun copyUriToFile(
        uriString: String,
        directoryName: String,
        fileName: String
    ): File {
        val directory = File(context.filesDir, directoryName)

        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException(
                "Could not create the Stage 3 storage directory."
            )
        }

        val destination = File(directory, fileName)
        val temporary = File(directory, "$fileName.tmp")

        val uri = Uri.parse(uriString)

        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException(
                "Cannot read the selected file. Import it again."
            )

        input.use { source ->
            FileOutputStream(temporary).use { output ->
                source.copyTo(output)
                output.flush()
            }
        }

        if (destination.exists() && !destination.delete()) {
            temporary.delete()
            throw IllegalStateException(
                "Could not replace the cached Stage 3 file."
            )
        }

        if (!temporary.renameTo(destination)) {
            temporary.delete()
            throw IllegalStateException(
                "Could not save the selected Stage 3 file."
            )
        }

        return destination
    }

    @Synchronized
    fun release() {
        if (closed) return

        closed = true
        activeCallback = null
        stopCurrentTask()
        worker.shutdownNow()
    }
}
