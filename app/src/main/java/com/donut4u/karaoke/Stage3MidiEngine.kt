
package com.donut4u.karaoke

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper

import dev.kotlinds.fluidsynthkmp.MidiFilePlayer

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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

    companion object {
        private const val MIDI_DIRECTORY = "stage3_midi"
        private const val SOUNDFONT_DIRECTORY = "stage3_soundfonts"
        private const val DEFAULT_SOUNDFONT = "builtin://default"

        private const val DRIVE_FILE_ID =
            "17UmucO4VmekeBKWPimDB93JCWsVWsQb_"

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
                if (midiUri.isBlank()) {
                    throw IllegalArgumentException(
                        "No MIDI/KAR song was selected."
                    )
                }

                checkSession(sessionId)

                val midiFile = copyUriToFile(
                    midiUri,
                    File(context.filesDir, MIDI_DIRECTORY),
                    "song.mid",
                    sessionId
                )

                checkSession(sessionId)

                val soundFontFile = if (
                    soundFontUri.isBlank() ||
                    soundFontUri == DEFAULT_SOUNDFONT
                ) {
                    copyDefaultSoundFont(sessionId)
                } else {
                    copyUriToFile(
                        soundFontUri,
                        File(context.cacheDir, SOUNDFONT_DIRECTORY),
                        "soundfont.sf2",
                        sessionId
                    )
                }

                checkSession(sessionId)

                if (!midiFile.exists() || midiFile.length() == 0L) {
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
                        "The SoundFont is invalid. Import a valid .sf2 file."
                    )
                }

                checkSession(sessionId)

                val newPlayer = MidiFilePlayer(
                    soundFontFile.absolutePath,
                    midiFile.absolutePath
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
                }

                newPlayer.play {
                    mainHandler.post {
                        finishSession(sessionId)
                    }
                }

                mainHandler.post {
                    synchronized(this) {
                        if (
                            isSessionActive(sessionId) &&
                            midiPlayer === newPlayer
                        ) {
                            activeCallback?.onReady(
                                newPlayer.durationTicks.coerceAtLeast(0L)
                            )
                        }
                    }
                }

            } catch (_: CancellationException) {
                cleanupTemporaryFiles()

            } catch (error: Exception) {
                val message = error.message
                    ?: "Could not start MIDI playback."

                mainHandler.post {
                    failSession(sessionId, message)
                }
            }
        }
    }

    @Synchronized
    private fun isSessionActive(sessionId: Long): Boolean {
        return !closed && activeSessionId == sessionId
    }

    private fun checkSession(sessionId: Long) {
        if (Thread.currentThread().isInterrupted ||
            !isSessionActive(sessionId)
        ) {
            throw CancellationException(
                "Playback was cancelled."
            )
        }
    }

    private fun isValidMidiFile(file: File): Boolean {
        if (!file.exists() || file.length() < 14L) {
            return false
        }

        return try {
            RandomAccessFile(file, "r").use { input ->
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

    private fun copyDefaultSoundFont(sessionId: Long): File {
        val directory = File(context.cacheDir, SOUNDFONT_DIRECTORY)

        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException(
                "Could not create temporary SoundFont storage."
            )
        }

        val destination = File(directory, "soundfont.sf2")
        val temporary = File(directory, "soundfont.sf2.tmp")

        destination.delete()
        temporary.delete()

        try {
            checkSession(sessionId)
            downloadSoundFontFromDrive(temporary, sessionId)
            checkSession(sessionId)

            if (!isValidSoundFont(temporary)) {
                throw IllegalStateException(
                    "Google Drive did not return a valid SoundFont. " +
                        "Check the file and its sharing permissions."
                )
            }

            if (!temporary.renameTo(destination)) {
                throw IllegalStateException(
                    "Could not save the temporary SoundFont."
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

    private fun downloadSoundFontFromDrive(
        destination: File,
        sessionId: Long
    ) {
        val baseUrl = "https://drive.google.com/uc"
        val encodedId = URLEncoder.encode(DRIVE_FILE_ID, "UTF-8")
        val cookies = mutableListOf<String>()

        var requestUrl =
            "$baseUrl?export=download&id=$encodedId"

        var attempt = 0

        while (attempt < 3) {
            checkSession(sessionId)
            attempt++

            val connection = (URL(requestUrl).openConnection()
                as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 30000
                readTimeout = 120000
                instanceFollowRedirects = true

                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Android) Donut4U-Karaoke"
                )

                if (cookies.isNotEmpty()) {
                    setRequestProperty(
                        "Cookie",
                        cookies.joinToString("; ")
                    )
                }
            }

            try {
                connection.connect()
                checkSession(sessionId)

                connection.getHeaderFields()["Set-Cookie"]
                    ?.forEach { cookie ->
                        val firstPart =
                            cookie.substringBefore(";").trim()

                        if (
                            firstPart.isNotEmpty() &&
                            !cookies.contains(firstPart)
                        ) {
                            cookies.add(firstPart)
                        }
                    }

                val status = connection.responseCode

                if (status !in 200..299) {
                    throw IllegalStateException(
                        "SoundFont download failed (HTTP $status). " +
                            "Check Google Drive sharing permissions."
                    )
                }

                val contentType =
                    connection.contentType?.lowercase() ?: ""

                if (contentType.contains("text/html")) {
                    val page = connection.inputStream
                        .bufferedReader(Charsets.UTF_8)
                        .use { it.readText() }

                    checkSession(sessionId)

                    val match = Regex(
                        """confirm=([0-9A-Za-z_-]+)"""
                    ).find(page)

                    val token = match?.groupValues?.getOrNull(1)

                    if (token == null || attempt >= 3) {
                        throw IllegalStateException(
                            "Google Drive returned a webpage instead " +
                                "of the SoundFont file. Check that the link " +
                                "is public and allows downloads."
                        )
                    }

                    requestUrl =
                        "$baseUrl?export=download&confirm=" +
                            URLEncoder.encode(token, "UTF-8") +
                            "&id=$encodedId"

                    continue
                }

                connection.inputStream.use { input ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int

                        while (true) {
                            checkSession(sessionId)

                            read = input.read(buffer)
                            if (read == -1) break

                            output.write(buffer, 0, read)
                        }

                        output.flush()
                    }
                }

                checkSession(sessionId)

                if (destination.length() == 0L) {
                    throw IllegalStateException(
                        "The downloaded SoundFont is empty."
                    )
                }

                return

            } finally {
                connection.disconnect()
            }
        }

        throw IllegalStateException(
            "Could not download the SoundFont from Google Drive."
        )
    }

    private fun isValidSoundFont(file: File): Boolean {
        if (!file.exists() || file.length() < 1024L) {
            return false
        }

        return try {
            RandomAccessFile(file, "r").use { input ->
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
        val position = getCurrentTick()

        try {
            midiPlayer?.pause()
        } finally {
            lastNativeTick = position
        }
    }

    @Synchronized
    fun resume() {
        val player = midiPlayer ?: return
        if (closed) return

        player.play()
    }

    @Synchronized
    fun seekTo(tick: Long) {
        val target = tick.coerceAtLeast(0L)
        val player = midiPlayer ?: return

        player.seekTo(target)
        lastNativeTick = target
    }

    @Synchronized
    fun getCurrentTick(): Long {
        val player = midiPlayer ?: return 0L

        return try {
            val nativeTick = player.currentTick.coerceAtLeast(0L)
            val duration = player.durationTicks.coerceAtLeast(0L)

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
            midiPlayer?.durationTicks?.coerceAtLeast(0L) ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    @Synchronized
    fun isPlaying(): Boolean {
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
        // Invalidate callbacks before stopping or deleting files.
        activeSessionId++

        activeCallback = null

        activeTask?.cancel(true)
        activeTask = null

        closeMidiPlayer()

        lastNativeTick = 0L

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
            File(context.filesDir, MIDI_DIRECTORY)
        )

        deleteRecursively(
            File(context.cacheDir, SOUNDFONT_DIRECTORY)
        )
    }

    private fun deleteRecursively(file: File) {
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

        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException(
                "Could not create temporary Stage 3 storage."
            )
        }

        val destination = File(directory, fileName)
        val temporary = File(directory, "$fileName.tmp")

        destination.delete()
        temporary.delete()

        val uri = Uri.parse(uriString)

        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException(
                "Cannot read the selected file. Import it again."
            )

        try {
            input.use { source ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int

                    while (true) {
                        checkSession(sessionId)

                        read = source.read(buffer)
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

    @Synchronized
    private fun finishSession(sessionId: Long) {
        if (!isSessionActive(sessionId)) return

        val callback = activeCallback

        closeMidiPlayer()
        activeTask = null
        activeCallback = null
        lastNativeTick = 0L

        cleanupTemporaryFiles()

        callback?.onFinished()
    }

    @Synchronized
    private fun failSession(
        sessionId: Long,
        message: String
    ) {
        if (!isSessionActive(sessionId)) {
            cleanupTemporaryFiles()
            return
        }

        val callback = activeCallback

        closeMidiPlayer()
        activeTask = null
        activeCallback = null
        lastNativeTick = 0L

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

        cleanupTemporaryFiles()

        worker.shutdownNow()
    }
}
