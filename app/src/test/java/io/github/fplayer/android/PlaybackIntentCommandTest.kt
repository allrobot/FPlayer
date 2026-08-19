package io.github.fplayer.android

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackIntentCommandTest {
    @Test fun allNotificationActionsDecodeDeterministically() {
        assertEquals(PlaybackIntentCommand.START, PlaybackIntentCommands.decode(PlaybackService.ACTION_START))
        assertEquals(PlaybackIntentCommand.PLAY, PlaybackIntentCommands.decode(PlaybackService.ACTION_PLAY))
        assertEquals(PlaybackIntentCommand.PAUSE, PlaybackIntentCommands.decode(PlaybackService.ACTION_PAUSE))
        assertEquals(PlaybackIntentCommand.STOP_DEVICES, PlaybackIntentCommands.decode(PlaybackService.ACTION_STOP_DEVICES))
        assertEquals(PlaybackIntentCommand.EXIT, PlaybackIntentCommands.decode(PlaybackService.ACTION_EXIT))
        assertEquals(PlaybackIntentCommand.UNKNOWN, PlaybackIntentCommands.decode(null))
        assertEquals(PlaybackIntentCommand.UNKNOWN, PlaybackIntentCommands.decode("other"))
    }
}
