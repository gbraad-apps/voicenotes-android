package nl.gbraad.transcribe;

public interface WhisperCallback {
    /**
     * @param noSpeechProb 0.0–1.0; values above ~0.6 indicate likely silence/noise.
     *                     Use to insert paragraph breaks independently of timing gaps.
     */
    void onNewSegment(long startCentis, long endCentis, String text, float noSpeechProb);
    void onProgress(int progress);
    void onComplete();
}
