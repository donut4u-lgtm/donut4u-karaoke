package com.donut4u.karaoke

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

import dev.kotlinds.fluidsynthkmp.MidiFilePlayer

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
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

    @Volatile
    private var activeCallback: Callback? = null

    @Volatile
    private var activeTask: Future<*>? = null

    @Volatile
    private var lastNativeTick: Long = 0L

    @Volatile
    private var activeSessionId: Long = 0L

    @Volatile
    private var pausedByEngine: Boolean = false

    companion object {

        private const val MIDI_DIRECTORY = "stage3_midi"
        private const val SOUNDFONT_DIRECTORY = "stage3_soundfonts"
        private const val DEFAULT_SOUNDFONT = "builtin://default"

        private const val DEFAULT_SOUNDFONT_ASSET =
            "GeneralUser-GS.sf2"

        private const val TAG = "Stage3MidiEngine"

        private const val DIAGNOSTIC_FILE =
            "stage3_midi_diagnostic.txt"

        private const val DIAGNOSTIC_PREFS =
            "stage3_midi_crash_diagnostics"

        private const val DIAGNOSTIC_KEY =
            "last_checkpoint"

        @JvmStatic
        fun create(context: Context): Stage3MidiEngine {

            val appContext = context.applicationContext

            val preferences = appContext.getSharedPreferences(
                DIAGNOSTIC_PREFS,
                Context.MODE_PRIVATE
            )

            val previousCheckpoint = preferences.getString(
                DIAGNOSTIC_KEY,
                null
            )

            if (!previousCheckpoint.isNullOrBlank()) {

                Toast.makeText(
                    context,
                    "Previous Stage 3 checkpoint: " +
                        previousCheckpoint,
                    Toast.LENGTH_LONG
                ).show()

                preferences.edit()
                    .remove(DIAGNOSTIC_KEY)
                    .commit()
            }

            return Stage3MidiEngine(appContext)
        }
    }

    private fun markDiagnostic(checkpoint: String) {

        val line = System.currentTimeMillis().toString() +
            " | " + checkpoint

        try {

            context.getSharedPreferences(
                DIAGNOSTIC_PREFS,
                Context.MODE_PRIVATE
            ).edit()
                .putString(DIAGNOSTIC_KEY, checkpoint)
                .commit()

        } catch (error: Throwable) {

            Log.e(
                TAG,
                "Could not save diagnostic preference.",
                error
            )
        }

        try {

            File(
                context.filesDir,
                DIAGNOSTIC_FILE
            ).appendText(line + "\n")

        } catch (error: Throwable) {

            Log.e(
                TAG,
                "Could not append diagnostic checkpoint.",
                error
            )
        }

        Log.i(
            TAG,
            "STAGE3_CHECKPOINT: " + checkpoint
        )
    }

    private fun clearDiagnostic() {

        try {

            context.getSharedPreferences(
                DIAGNOSTIC_PREFS,
                Context.MODE_PRIVATE
            ).edit()
                .remove(DIAGNOSTIC_KEY)
                .commit()

        } catch (error: Throwable) {

            Log.e(
                TAG,
                "Could not clear diagnostic preference.",
                error
            )
        }
    }

    private fun recordLinkageFailure(
        error: LinkageError
    ): String {

        val message = buildString {

            append("MIDI native-library error: ")

            append(error.javaClass.simpleName)

            val detail = error.message

            if (!detail.isNullOrBlank()) {
                append(": ")
                append(detail)
            }
        }

        val stackTrace = error.stackTraceToString()

        Log.e(
            TAG,
            message,
            error
        )

        try {

            File(
                context.filesDir,
                DIAGNOSTIC_FILE
            ).writeText(
                buildString {
                    appendLine("Donut4U Stage 3 MIDI diagnostic")
                    appendLine("Error: $message")
                    appendLine()
                    appendLine(stackTrace)
                }
            )

        } catch (writeError: Exception) {

            Log.e(
                TAG,
                "Could not save MIDI diagnostic file.",
                writeError
            )
        }

        markDiagnostic(
            "LINKAGE_ERROR " + message
        )

        return message
    }

    @Synchronized
    fun play(
        midiUri: String,
        soundFontUri: String,
        callback: Callback
    ) {

        if (closed) {
            throw IllegalStateException(
                "The MIDI engine has already been released."
            )
        }

        stopCurrentTask()

        val sessionId = activeSessionId

        activeCallback = callback

        activeTask = worker.submit {

            try {

                markDiagnostic("PLAY_REQUEST_RECEIVED")

                if (midiUri.isBlank()) {
                    throw IllegalArgumentException(
                        "No MIDI/KAR song was selected."
                    )
                }

                checkSession(sessionId)

                val midiFile = copyUriToFile(
                    midiUri,
                    File(
                        context.filesDir,
                        MIDI_DIRECTORY
                    ),
                    "song.mid",
                    sessionId
                )

                checkSession(sessionId)

                markDiagnostic(
                    "MIDI_FILE_COPIED bytes=" +
                        midiFile.length()
                )

                val soundFontFile = if (
                    soundFontUri.isBlank() ||
                    soundFontUri == DEFAULT_SOUNDFONT
                ) {

                    copyDefaultSoundFont(sessionId)

                } else {

                    copyUriToFile(
                        soundFontUri,
                        File(
                            context.cacheDir,
                            SOUNDFONT_DIRECTORY
                        ),
                        "soundfont.sf2",
                        sessionId
                    )
                }

                checkSession(sessionId)

                markDiagnostic(
                    "SOUNDFONT_FILE_READY bytes=" +
                        soundFontFile.length()
                )

                if (
                    !midiFile.exists() ||
                    midiFile.length() == 0L
                ) {
                    throw IllegalStateException(
                        "The selected MIDI/KAR file is empty or unavailable."
                    )
                }

                if (!isValidMidiFile(midiFile)) {
                    throw IllegalStateException(
                        "The selected file is not a valid standard MIDI/KAR file."
                    )
                }

                if (!isValidSoundFont(soundFontFile)) {
                    throw IllegalStateException(
                        "The bundled or imported SoundFont is invalid."
                    )
                }

                checkSession(sessionId)

                markDiagnostic("FILES_VALIDATED")

                markDiagnostic(
                    "BEFORE_FLUIDSYNTH_CONSTRUCTOR"
                )

                val newPlayer = MidiFilePlayer(
                    soundFontFile.absolutePath,
                    midiFile.absolutePath
                )

                markDiagnostic(
                    "AFTER_FLUIDSYNTH_CONSTRUCTOR"
                )

                synchronized(this) {

                    if (!isSessionActive(sessionId)) {

                        try {
                            newPlayer.stop()
                        } catch (_: Exception) {
                        }

                        try {
                            newPlayer.close()
                        } catch (_: Exception) {
                        }

                        throw CancellationException(
                            "Playback was cancelled."
                        )
                    }

                    midiPlayer = newPlayer

                    lastNativeTick = 0L

                    pausedByEngine = false
                }

                markDiagnostic("BEFORE_FLUIDSYNTH_PLAY")

                newPlayer.play {

                    mainHandler.post {
                        finishSession(sessionId)
                    }
                }

                markDiagnostic("AFTER_FLUIDSYNTH_PLAY")

                mainHandler.post {

                    synchronized(this) {

                        if (
                            isSessionActive(sessionId) &&
                            midiPlayer === newPlayer
                        ) {

                            markDiagnostic(
                                "PLAYBACK_READY durationTicks=" +
                                    newPlayer.durationTicks
                            )

                            activeCallback?.onReady(
                                newPlayer.durationTicks.coerceAtLeast(0L)
                            )
                        }
                    }
                }

            } catch (_: CancellationException) {

                // Expected when a song is stopped or replaced.

            } catch (error: LinkageError) {

                val message = recordLinkageFailure(error)

                mainHandler.post {
                    failSession(sessionId, message)
                }

            } catch (error: Exception) {

                markDiagnostic(
                    "JAVA_EXCEPTION " +
                        error.javaClass.simpleName +
                        ": " + error.message
                )

                Log.e(
                    TAG,
                    "Could not start MIDI playback.",
                    error
                )

                val message = error.message
                    ?: "Could not start MIDI playback."

                mainHandler.post {
                    failSession(sessionId, message)
                }

            } catch (error: Throwable) {

                val message = buildString {

                    append("MIDI playback fatal error: ")

                    append(error.javaClass.simpleName)

                    if (!error.message.isNullOrBlank()) {
                        append(": ")
                        append(error.message)
                    }
                }

                markDiagnostic(
                    "FATAL_THROWABLE " + message
                )

                Log.e(
                    TAG,
                    message,
                    error
                )

                try {

                    File(
                        context.filesDir,
                        DIAGNOSTIC_FILE
                    ).appendText(
                        error.stackTraceToString() + "\n"
                    )

                } catch (_: Throwable) {
                }

                mainHandler.post {
                    failSession(sessionId, message)
                }
            }
        }
    }

    @Synchronized
    private fun isSessionActive(
        sessionId: Long
    ): Boolean {

        return !closed &&
            activeSessionId == sessionId
    }

    private fun checkSession(
        sessionId: Long
    ) {

        if (
            Thread.currentThread().isInterrupted ||
            !isSessionActive(sessionId)
        ) {
            throw CancellationException(
                "Playback was cancelled."
            )
        }
    }

    private fun isValidMidiFile(
        file: File
    ): Boolean {

        if (
            !file.exists() ||
            file.length() < 14L
        ) {
            return false
        }

        return try {

            RandomAccessFile(
                file,
                "r"
            ).use { input ->

                val header = ByteArray(4)

                input.readFully(header)

                String(
                    header,
                    0,
                    4,
                    Charsets.US_ASCII
                ) == "MThd"
            }

        } catch (_: Exception) {
            false
        }
    }

    /**
     * Copies the SoundFont bundled inside the APK assets
     * into temporary app storage for FluidSynth.
     *
     * No Google Drive or internet download is required.
     */
    private fun copyDefaultSoundFont(
        sessionId: Long
    ): File {

        val directory = File(
            context.cacheDir,
            SOUNDFONT_DIRECTORY
        )

        if (
            !directory.exists() &&
            !directory.mkdirs()
        ) {
            throw IllegalStateException(
                "Could not create temporary SoundFont storage."
            )
        }

        val destination = File(
            directory,
            "soundfont.sf2"
        )

        val temporary = File(
            directory,
            "soundfont.sf2.tmp"
        )

        destination.delete()
        temporary.delete()

        try {

            checkSession(sessionId)

            markDiagnostic(
                "BEFORE_BUNDLED_SOUNDFONT_ASSET"
            )

            context.assets.open(
                DEFAULT_SOUNDFONT_ASSET
            ).use { input ->

                FileOutputStream(temporary).use { output ->

                    val buffer = ByteArray(8192)

                    while (true) {

                        checkSession(sessionId)

                        val read = input.read(buffer)

                        if (read == -1) break

                        output.write(buffer, 0, read)
                    }

                    output.flush()
                }
            }

            checkSession(sessionId)

            if (!isValidSoundFont(temporary)) {
                throw IllegalStateException(
                    "The bundled GeneralUser SoundFont is missing, " +
                        "incomplete, or invalid."
                )
            }

            if (!temporary.renameTo(destination)) {
                throw IllegalStateException(
                    "Could not save the bundled SoundFont."
                )
            }

            checkSession(sessionId)

            markDiagnostic(
                "BUNDLED_SOUNDFONT_COPIED bytes=" +
                    destination.length()
            )

            return destination

        } catch (error: Exception) {

            temporary.delete()
            destination.delete()

            if (error is CancellationException) {
                throw error
            }

            throw IllegalStateException(
                "Could not load bundled SoundFont " +
                    DEFAULT_SOUNDFONT_ASSET +
                    ": " +
                    (error.message ?: "Unknown error"),
                error
            )
        }
    }

    private fun isValidSoundFont(
        file: File
    ): Boolean {

        if (
            !file.exists() ||
            file.length() < 1024L
        ) {
            return false
        }

        return try {

            RandomAccessFile(
                file,
                "r"
            ).use { input ->

                if (input.length() < 12L) {

                    false

                } else {

                    val header = ByteArray(12)

                    input.readFully(header)

                    String(
                        header,
                        0,
                        4,
                        Charsets.US_ASCII
                    ) == "RIFF" &&
                        String(
                            header,
                            8,
                            4,
                            Charsets.US_ASCII
                        ) == "sfbk"
                }
            }

        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun pause() {

        val player = midiPlayer ?: return

        if (closed || pausedByEngine) return

        val position = getCurrentTick()

        pausedByEngine = true

        try {

            player.pause()

        } finally {

            lastNativeTick = position
        }
    }

    @Synchronized
    fun resume() {

        val player = midiPlayer ?: return

        if (closed || !pausedByEngine) return

        player.play()

        pausedByEngine = false
    }

    @Synchronized
    fun seekTo(
        tick: Long
    ) {

        val target = tick.coerceAtLeast(0L)

        val player = midiPlayer ?: return

        if (closed) return

        if (pausedByEngine) {

            player.play()

            player.seekTo(target)

            player.pause()

            pausedByEngine = true

        } else {

            player.seekTo(target)
        }

        lastNativeTick = target
    }

    @Synchronized
    fun getCurrentTick(): Long {

        val player = midiPlayer ?: return 0L

        return try {

            val nativeTick =
                player.currentTick.coerceAtLeast(0L)

            val duration =
                player.durationTicks.coerceAtLeast(0L)

            val position = if (duration > 0L) {

                nativeTick.coerceAtMost(duration)

            } else {

                nativeTick
            }

            lastNativeTick = position

            position

        } catch (_: Exception) {

            lastNativeTick.coerceAtLeast(0L)
        }
    }

    @Synchronized
    fun getDurationTicks(): Long {

        return try {

            midiPlayer?.durationTicks?.coerceAtLeast(0L)
                ?: 0L

        } catch (_: Exception) {

            0L
        }
    }

    @Synchronized
    fun isPlaying(): Boolean {

        if (pausedByEngine) return false

        return try {

            midiPlayer?.isPlaying ?: false

        } catch (_: Exception) {

            false
        }
    }

    @Synchronized
    fun stop() {

        stopCurrentTask()
    }

    private fun stopCurrentTask() {

        activeSessionId++

        activeCallback = null

        activeTask?.cancel(true)

        activeTask = null

        closeMidiPlayer()

        lastNativeTick = 0L

        pausedByEngine = false

        cleanupTemporaryFiles()
    }

    private fun closeMidiPlayer() {

        val player = midiPlayer

        midiPlayer = null

        if (player != null) {

            try {
                player.stop()
            } catch (_: Exception) {
            }

            try {
                player.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun cleanupTemporaryFiles() {

        deleteRecursively(
            File(
                context.filesDir,
                MIDI_DIRECTORY
            )
        )

        deleteRecursively(
            File(
                context.cacheDir,
                SOUNDFONT_DIRECTORY
            )
        )
    }

    private fun deleteRecursively(
        file: File
    ) {

        if (!file.exists()) return

        if (file.isDirectory) {

            file.listFiles()?.forEach { child ->

                deleteRecursively(child)
            }
        }

        try {

            file.delete()

        } catch (_: Exception) {
        }
    }

    private fun copyUriToFile(
        uriString: String,
        directory: File,
        fileName: String,
        sessionId: Long
    ): File {

        checkSession(sessionId)

        val uri = Uri.parse(uriString)

        val scheme = uri.scheme?.lowercase()

        if (
            scheme == "http" ||
            scheme == "https"
        ) {

            return copyHttpUrlToFile(
                uriString,
                directory,
                fileName,
                sessionId
            )
        }

        if (
            !directory.exists() &&
            !directory.mkdirs()
        ) {
            throw IllegalStateException(
                "Could not create temporary Stage 3 storage."
            )
        }

        val destination = File(
            directory,
            fileName
        )

        val temporary = File(
            directory,
            "$fileName.tmp"
        )

        destination.delete()

        temporary.delete()

        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException(
                "Cannot read the selected file. Import it again."
            )

        try {

            input.use { source ->

                FileOutputStream(temporary).use { output ->

                    val buffer = ByteArray(8192)

                    while (true) {

                        checkSession(sessionId)

                        val read = source.read(buffer)

                        if (read == -1) break

                        output.write(buffer, 0, read)
                    }

                    output.flush()
                }
            }

            checkSession(sessionId)

            if (temporary.length() == 0L) {
                throw IllegalStateException(
                    "The selected file is empty."
                )
            }

            if (!temporary.renameTo(destination)) {
                throw IllegalStateException(
                    "Could not save the temporary Stage 3 file."
                )
            }

            checkSession(sessionId)

            return destination

        } catch (error: Exception) {

            temporary.delete()

            destination.delete()

            throw error
        }
    }

    private fun copyHttpUrlToFile(
        urlString: String,
        directory: File,
        fileName: String,
        sessionId: Long
    ): File {

        if (
            !directory.exists() &&
            !directory.mkdirs()
        ) {
            throw IllegalStateException(
                "Could not create temporary Stage 3 storage."
            )
        }

        val destination = File(
            directory,
            fileName
        )

        val temporary = File(
            directory,
            "$fileName.tmp"
        )

        destination.delete()

        temporary.delete()

        val connection = (
            URL(urlString).openConnection()
                as HttpURLConnection
            ).apply {

            requestMethod = "GET"

            connectTimeout = 30000

            readTimeout = 120000

            instanceFollowRedirects = true

            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Android) Donut4U-Karaoke"
            )

            setRequestProperty(
                "Accept",
                "application/octet-stream,*/*"
            )
        }

        try {

            checkSession(sessionId)

            connection.connect()

            val status = connection.responseCode

            if (status !in 200..299) {
                throw IllegalStateException(
                    "Online song download failed (HTTP $status)."
                )
            }

            val contentType =
                connection.contentType?.lowercase() ?: ""

            if (contentType.contains("text/html")) {
                throw IllegalStateException(
                    "The song link returned a webpage instead of a file. " +
                        "Use the direct download URL."
                )
            }

            connection.inputStream.use { source ->

                FileOutputStream(temporary).use { output ->

                    val buffer = ByteArray(8192)

                    while (true) {

                        checkSession(sessionId)

                        val read = source.read(buffer)

                        if (read == -1) break

                        output.write(buffer, 0, read)
                    }

                    output.flush()
                }
            }

            checkSession(sessionId)

            if (temporary.length() == 0L) {
                throw IllegalStateException(
                    "The online karaoke file is empty."
                )
            }

            if (!isValidMidiFile(temporary)) {
                throw IllegalStateException(
                    "The downloaded file is not a valid MIDI/KAR file."
                )
            }

            if (!temporary.renameTo(destination)) {
                throw IllegalStateException(
                    "Could not save the downloaded karaoke file."
                )
            }

            checkSession(sessionId)

            return destination

        } catch (error: Exception) {

            temporary.delete()

            destination.delete()

            throw error

        } finally {

            connection.disconnect()
        }
    }

    @Synchronized
    private fun finishSession(
        sessionId: Long
    ) {

        if (!isSessionActive(sessionId)) return

        if (pausedByEngine) return

        val player = midiPlayer ?: return

        val duration = try {

            player.durationTicks.coerceAtLeast(0L)

        } catch (_: Exception) {

            0L
        }

        val position = try {

            player.currentTick.coerceAtLeast(0L)

        } catch (_: Exception) {

            lastNativeTick.coerceAtLeast(0L)
        }

        if (
            duration <= 0L ||
            position < duration - 2L
        ) {
            return
        }

        val callback = activeCallback

        closeMidiPlayer()

        activeTask = null

        activeCallback = null

        lastNativeTick = 0L

        pausedByEngine = false

        cleanupTemporaryFiles()

        clearDiagnostic()

        callback?.onFinished()
    }

    @Synchronized
    private fun failSession(
        sessionId: Long,
        message: String
    ) {

        if (!isSessionActive(sessionId)) return

        val callback = activeCallback

        closeMidiPlayer()

        activeTask = null

        activeCallback = null

        lastNativeTick = 0L

        pausedByEngine = false

        cleanupTemporaryFiles()

        callback?.onError(message)
    }

    @Synchronized
    fun release() {

        if (closed) return

        closed = true

        activeSessionId++

        activeCallback = null

        activeTask?.cancel(true)

        activeTask = null

        closeMidiPlayer()

        lastNativeTick = 0L

        pausedByEngine = false

        cleanupTemporaryFiles()

        clearDiagnostic()

        worker.shutdownNow()
    }
}
