package sv.vlad.lector;

import org.junit.Test;
import static org.junit.Assert.*;

public class SpeechResumeStateTest {
    @Test public void pauseKeepsSpeechPositionAcrossPassiveReportsAndRepeatedPauses() {
        SpeechResumeState state = new SpeechResumeState();
        assertFalse(state.canResume());
        assertTrue(state.protectsPosition(true));
        state.paused(true);
        assertTrue(state.canResume());
        assertTrue(state.protectsPosition(false)); // reflow/theme/rotation must not replace word offset
        state.paused(false); // opening a voice menu must not destroy the resume point
        assertTrue(state.canResume());
    }
    @Test public void explicitNavigationAllowsNewVisiblePosition() {
        SpeechResumeState state = new SpeechResumeState();
        state.paused(true);
        state.clear(); // manual turn, slider, chapter, another book, or playback started
        assertFalse(state.canResume());
        assertFalse(state.protectsPosition(false));
    }
    @Test public void noResumePointWithoutPriorPlaybackOrInNewSession() {
        SpeechResumeState state = new SpeechResumeState();
        state.paused(false);
        assertFalse(state.canResume());
        state.paused(true);
        assertFalse(new SpeechResumeState().canResume());
    }
}
