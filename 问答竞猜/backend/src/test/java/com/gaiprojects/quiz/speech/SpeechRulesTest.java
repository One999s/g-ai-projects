package com.gaiprojects.quiz.speech;

import static org.junit.jupiter.api.Assertions.*;

import com.gaiprojects.quiz.core.RuleException;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class SpeechRulesTest {
  static byte[] wave(int samples) {
    var b = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
    b.put("RIFF".getBytes(StandardCharsets.US_ASCII))
        .putInt(b.capacity() - 8)
        .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII))
        .putInt(16)
        .putShort((short) 1)
        .putShort((short) 1)
        .putInt(16000)
        .putInt(32000)
        .putShort((short) 2)
        .putShort((short) 16)
        .put("data".getBytes(StandardCharsets.US_ASCII))
        .putInt(samples * 2);
    return b.array();
  }

  @Test
  void canonicalSixSecondPcmIsAccepted() {
    assertDoesNotThrow(() -> CanonicalWave.verify(wave(96000)));
    assertDoesNotThrow(() -> CanonicalWave.verify(wave(1600)));
  }

  @Test
  void sizeCodecChannelsAndRateCannotBeForged() {
    for (int index : List.of(0, 4, 8, 12, 16, 20, 22, 24, 28, 32, 34, 36, 40)) {
      byte[] bad = wave(16000);
      bad[index] ^= 1;
      assertThrows(RuleException.class, () -> CanonicalWave.verify(bad));
    }
    assertThrows(RuleException.class, () -> CanonicalWave.verify(wave(96001)));
    assertThrows(RuleException.class, () -> CanonicalWave.verify(new byte[3]));
  }

  @Test
  void clearEnglishAndChineseChoicesAreOnlySuggestions() {
    var o = List.of("Mars", "Saturn", "Earth", "Sun");
    assertEquals(1, ChoiceMatcher.suggest("My answer is option B.", o, Set.of()));
    assertEquals(1, ChoiceMatcher.suggest("我选 B。", o, Set.of()));
    assertEquals(1, ChoiceMatcher.suggest("Saturn", o, Set.of()));
    assertEquals(2, ChoiceMatcher.suggest("第三项", o, Set.of()));
  }

  @Test
  void negativeMultipleUncertainOrEliminatedSpeechHasNoCandidate() {
    for (String s :
        List.of(
            "not A",
            "A or B",
            "I choose B, no C",
            "I don't know a thing",
            "hello world",
            "",
            "A/B"))
      assertNull(ChoiceMatcher.suggest(s, List.of("Mars", "Saturn", "Earth", "Sun"), Set.of()));
    assertNull(ChoiceMatcher.suggest("B", List.of("Mars", "Saturn", "Earth", "Sun"), Set.of(1)));
  }

  @Test
  void optionLabelCollisionIsAmbiguous() {
    assertNull(ChoiceMatcher.suggest("B", List.of("B", "A", "C", "D"), Set.of()));
  }

  @Test
  void onlyExactLiteralLoopbackPrivatePathIsAllowed() {
    for (String url :
        Arrays.asList(
            null,
            "http://localhost:8000/internal/quiz/asr",
            "https://127.0.0.1:8000/internal/quiz/asr",
            "http://127.0.0.1:8000/internal/quiz/asr?token=x",
            "http://user:pass@127.0.0.1:8000/internal/quiz/asr",
            "http://127.0.0.1:8000/internal/quiz/%61sr",
            "http://127.0.0.2:8000/internal/quiz/asr",
            "http://127.0.0.1/internal/quiz/asr"))
      assertThrows(IllegalStateException.class, () -> LoopbackTranscriber.validateEndpoint(url));
    assertDoesNotThrow(
        () -> LoopbackTranscriber.validateEndpoint("http://127.0.0.1:9609/internal/quiz/asr"));
  }
}
