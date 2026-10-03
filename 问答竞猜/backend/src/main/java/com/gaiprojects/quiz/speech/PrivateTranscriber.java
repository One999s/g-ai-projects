package com.gaiprojects.quiz.speech;

/**
 * Private processor receives only PCM audio and language, never identity tokens or game answers.
 */
public interface PrivateTranscriber {
  String transcribe(byte[] wave, String locale, long timeoutMillis);
}
