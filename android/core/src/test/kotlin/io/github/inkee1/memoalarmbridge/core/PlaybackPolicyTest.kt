package io.github.inkee1.memoalarmbridge.core

import org.junit.Assert.*
import org.junit.Test

class PlaybackPolicyTest {
 @Test fun stopDoesNotStopAnotherAlarm(){val q=PlaybackQueue();q.offer("first");q.offer("second");assertFalse(q.remove("second"));assertEquals("first",q.active);q.offer("third");assertTrue(q.remove("first"));assertEquals("third",q.active)}
 @Test fun duplicateQueueEntryNeverReplays(){val q=PlaybackQueue();q.offer("same");q.offer("same");q.remove("same");assertNull(q.active)}
 @Test fun silentPolicyRespected(){assertFalse(PlaybackPolicy.allowed(0,false,false,DndMode.ALL));assertFalse(PlaybackPolicy.allowed(5,true,true,DndMode.ALL));assertFalse(PlaybackPolicy.allowed(5,false,false,DndMode.NONE));assertTrue(PlaybackPolicy.allowed(5,true,false,DndMode.ALARMS));assertTrue(PlaybackPolicy.allowed(5,false,false,DndMode.PRIORITY))}
}
