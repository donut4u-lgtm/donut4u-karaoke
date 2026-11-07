
# Rhino's optional JSR-223 scripting integration is not available on Android.
-dontwarn javax.script.ScriptEngineFactory
-dontwarn javax.script.ScriptEngine
-dontwarn javax.script.ScriptEngineManager
-dontwarn javax.script.ScriptException

# Preserve FluidSynth classes and members accessed by Stage3MidiGain.
-keep class dev.kotlinds.fluidsynthkmp.MidiFilePlayer {
    *;
}

-keep class dev.kotlinds.fluidsynthkmp.FluidSynthContext {
    *;
}

-keep class dev.kotlinds.fluidsynthkmp.FluidSynthJni {
    *;
}
