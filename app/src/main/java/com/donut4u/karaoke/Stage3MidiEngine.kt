
package com.donut4u.karaoke

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

import dev.kotlinds.fluidsynthkmp.MidiFilePlayer

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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

    @Volatile
    private var fallbackPpq: Long = 480L

    @Volatile
    private var fallbackBaseTick: Long = 0L

    @Volatile
    private var fallbackStartedAtMs: Long = 0L

    @Volatile
    private var fallbackPaused = true

    @Volatile
    private var lastNativeTick: Long = 0L

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

                if (!isValidSoundFont(soundFontFile)) {
                    throw IllegalStateException(
                        "The SoundFont is invalid. Import a valid .sf2 file."
                    )
                }

                fallbackPpq = readMidiPpq(midiFile).coerceAtLeast(1L)
                fallbackBaseTick = 0L
                fallbackStartedAtMs = 0L
                fallbackPaused = false
                lastNativeTick = 0L

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

                fallbackStartedAtMs = SystemClock.elapsedRealtime()

                newPlayer.play {
                    mainHandler.post {
                        val finalPosition = getCurrentTick()
                        fallbackBaseTick = finalPosition
                        lastNativeTick = maxOf(
                            lastNativeTick,
                            finalPosition
                        )
                        fallbackPaused = true

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

        if (isValidSoundFont(destination)) {
            return destination
        }

        if (destination.exists()) {
            destination.delete()
        }

        val temporary = File(directory, "default.sf2.tmp")
        if (temporary.exists()) temporary.delete()

        try {
            downloadSoundFontFromDrive(temporary)

            if (!isValidSoundFont(temporary)) {
                throw IllegalStateException(
                    "Google Drive did not return a valid SoundFont. " +
                    "Check the file and its sharing permissions."
                )
            }

            if (destination.exists()) destination.delete()

            if (!temporary.renameTo(destination)) {
                throw IllegalStateException(
                    "Could not save the downloaded SoundFont."
                )
            }

            return destination
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    private fun downloadSoundFontFromDrive(destination: File) {
        val baseUrl = "https://drive.google.com/uc"
        val encodedId = URLEncoder.encode(DRIVE_FILE_ID, "UTF-8")
        val cookies = mutableListOf<String>()

        var requestUrl =
            "$baseUrl?export=download&id=$encodedId"

        var attempt = 0

        while (attempt < 3) {
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

                connection.getHeaderFields()["Set-Cookie"]
                    ?.forEach { cookie ->
                        val firstPart =
                            cookie.substringBefore(";").trim()

                        if (firstPart.isNotEmpty() &&
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
                        input.copyTo(output)
                        output.flush()
                    }
                }

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
            fallbackBaseTick = position
            lastNativeTick = maxOf(lastNativeTick, position)
            fallbackPaused = true
        }
    }

    @Synchronized
    fun resume() {
        val player = midiPlayer ?: return

        val position = getCurrentTick()

        fallbackBaseTick = position
        lastNativeTick = maxOf(lastNativeTick, position)
        fallbackStartedAtMs = SystemClock.elapsedRealtime()
        fallbackPaused = false

        player.play()
    }

    @Synchronized
    fun seekTo(tick: Long) {
        val target = tick.coerceAtLeast(0L)

        midiPlayer?.seekTo(target)

        fallbackBaseTick = target
        lastNativeTick = target
        fallbackStartedAtMs = SystemClock.elapsedRealtime()
        fallbackPaused = false
    }

    /**
     * Uses the greatest observed playback position or an elapsed-time
     * estimate so a stale native tick value cannot freeze the counter.
     */
    fun getCurrentTick(): Long {
        val player = midiPlayer ?: return 0L

        val nativeTick = try {
            player.currentTick.coerceAtLeast(0L)
        } catch (_: Exception) {
            0L
        }

        if (fallbackPaused) {
            val pausedPosition = maxOf(
                fallbackBaseTick,
                lastNativeTick,
                nativeTick
            )

            lastNativeTick = pausedPosition
            return pausedPosition
        }

        val elapsedMs = (
            SystemClock.elapsedRealtime() - fallbackStartedAtMs
        ).coerceAtLeast(0L)

        val estimatedTick = fallbackBaseTick +
            (elapsedMs * fallbackPpq * 2L / 1000L)

        val currentPosition = maxOf(
            nativeTick,
            estimatedTick,
            lastNativeTick
        )

        val duration = try {
            player.durationTicks.coerceAtLeast(0L)
        } catch (_: Exception) {
            0L
        }

        val result = if (duration > 0L) {
            currentPosition.coerceAtMost(duration)
        } else {
            currentPosition
        }

        lastNativeTick = result
        return result
    }

    private fun readMidiPpq(file: File): Long {
        return try {
            RandomAccessFile(file, "r").use { input ->
                if (input.length() < 14L) return 480L

                val header = ByteArray(14)
                input.readFully(header)

                if (String(
                        header,
                        0,
                        4,
                        Charsets.US_ASCII
                    ) != "MThd"
                ) {
                    return 480L
                }

                val division =
                    ((header[12].toInt() and 0xFF) shl 8) or
                    (header[13].toInt() and 0xFF)

                if ((division and 0x8000) != 0 || division == 0) {
                    480L
                } else {
                    (division and 0x7FFF).toLong()
                }
            }
        } catch (_: Exception) {
            480L
        }
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
        fallbackBaseTick = 0L
        fallbackStartedAtMs = 0L
        fallbackPaused = true
        lastNativeTick = 0L
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
