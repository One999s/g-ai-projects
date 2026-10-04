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
  void completeTraditionalOrdinalsAndExplicitPrefixesRemainExactSuggestions() {
    var options = List.of("Mars", "Saturn", "Earth", "Sun");
    var numerals = List.of("一", "二", "三", "四");
    for (int i = 0; i < 4; i++) {
      for (String prefix : List.of("", "我選擇", "我選", "選擇", "選項", "選", "答案是", "我的答案是")) {
        for (String suffix : List.of("項", "個")) {
          assertEquals(
              i,
              ChoiceMatcher.suggest(
                  prefix + "第" + numerals.get(i) + suffix + "。", options, Set.of()));
        }
      }
    }
    assertEquals(2, ChoiceMatcher.suggest("我選擇 C。", options, Set.of()));
    assertEquals(2, ChoiceMatcher.suggest("我选择第三項。", options, Set.of()));
  }

  @Test
  void traditionalNegationMultipleChoicesAndMisrecognizedThirteenStayUnmatched() {
    for (String text :
        List.of(
            "C?",
            "我選擇第三項？",
            "第三項⁇",
            "無選擇第三項",
            "不選第三項",
            "不要選第三項",
            "我不選擇第三項",
            "我選擇第三項嗎",
            "可能選第三項",
            "我選擇第三項或第四項",
            "第三項/第四項",
            "答案13",
            "答案十三",
            "我選擇第十三項",
            "我選擇第三項然後第四項")) {
      assertNull(
          ChoiceMatcher.suggest(text, List.of("Mars", "Saturn", "Earth", "Sun"), Set.of()), text);
    }
  }

  @Test
  void traditionalLabelsStillRejectEliminatedAndCollidingOptions() {
    assertNull(
        ChoiceMatcher.suggest("我選擇第三項", List.of("Mars", "Saturn", "Earth", "Sun"), Set.of(2)));
    assertNull(ChoiceMatcher.suggest("第三項", List.of("第三項", "Saturn", "Earth", "Sun"), Set.of()));
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
