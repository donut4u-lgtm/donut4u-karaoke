
package com.donut4u.karaoke

import android.util.Log
import dev.kotlinds.fluidsynthkmp.MidiFilePlayer

/**
 * Sets the FluidSynth master gain for Stage 3 MIDI/KAR playback.
 */
internal object Stage3MidiGain {

    private const val TAG = "Stage3MidiGain"

    // Full FluidSynth master gain.
    private const val TARGET_GAIN = 1.0f

    fun apply(player: MidiFilePlayer): Boolean {
        return try {
            val contextField =
                player.javaClass.getDeclaredField("context").apply {
                    isAccessible = true
                }

            val fluidContext = contextField.get(player)
                ?: throw IllegalStateException(
                    "FluidSynth context is unavailable."
                )

            val synthField =
                fluidContext.javaClass.getDeclaredField("synthPtr").apply {
                    isAccessible = true
                }

            val synthPointer = synthField.getLong(fluidContext)

            check(synthPointer != 0L) {
                "FluidSynth synth pointer is invalid."
            }

            val jniClass = Class.forName(
                "dev.kotlinds.fluidsynthkmp.FluidSynthJni"
            )

            val instanceField =
                jniClass.getDeclaredField("INSTANCE").apply {
                    isAccessible = true
                }

            val jniInstance = instanceField.get(null)
                ?: throw IllegalStateException(
                    "FluidSynth JNI instance is unavailable."
                )

            val gainMethod = jniClass.getDeclaredMethod(
                "setGain",
                Long::class.javaPrimitiveType,
                Float::class.javaPrimitiveType
            ).apply {
                isAccessible = true
            }

            gainMethod.invoke(
                jniInstance,
                synthPointer,
                TARGET_GAIN
            )

            Log.i(TAG, "Stage 3 MIDI gain set to FULL: $TARGET_GAIN")

            true

        } catch (error: Exception) {
            Log.e(TAG, "Could not adjust Stage 3 MIDI gain.", error)
            false
        } catch (error: LinkageError) {
            Log.e(TAG, "FluidSynth native gain function unavailable.", error)
            false
        }
    }
}
