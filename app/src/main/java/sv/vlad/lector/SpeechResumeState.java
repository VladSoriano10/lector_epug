package sv.vlad.lector;

/** Session-only policy: passive layout reports must not replace a paused speech position. */
final class SpeechResumeState {
    private boolean pausedSpeech;
    void paused(boolean wasPlaying) { if (wasPlaying) pausedSpeech = true; }
    boolean canResume() { return pausedSpeech; }
    boolean protectsPosition(boolean playing) { return playing || pausedSpeech; }
    void clear() { pausedSpeech = false; }
}
