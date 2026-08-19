package io.github.fplayer.android

/** Pure action decoding keeps notification and MediaSession fault tests off Android UI state. */
enum class PlaybackIntentCommand { START, PLAY, PAUSE, STOP_DEVICES, EXIT, UNKNOWN }

object PlaybackIntentCommands {
    fun decode(action: String?): PlaybackIntentCommand = when (action) {
        PlaybackService.ACTION_START -> PlaybackIntentCommand.START
        PlaybackService.ACTION_PLAY -> PlaybackIntentCommand.PLAY
        PlaybackService.ACTION_PAUSE -> PlaybackIntentCommand.PAUSE
        PlaybackService.ACTION_STOP_DEVICES -> PlaybackIntentCommand.STOP_DEVICES
        PlaybackService.ACTION_EXIT -> PlaybackIntentCommand.EXIT
        else -> PlaybackIntentCommand.UNKNOWN
    }
}
