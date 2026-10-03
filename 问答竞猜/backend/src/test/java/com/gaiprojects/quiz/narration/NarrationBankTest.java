package com.gaiprojects.quiz.narration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiprojects.quiz.core.Question;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class NarrationBankTest {
  @TempDir Path root;
  final ObjectMapper json = new ObjectMapper();
  final Question q =
      new Question("q1", "en", "A test?", List.of("A", "B", "C", "D"), 2, "Only a fixture", 2000);

  byte[] wave() {
    var b = ByteBuffer.allocate(32044).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(0x46464952)
        .putInt(32036)
        .putInt(0x45564157)
        .putInt(0x20746d66)
        .putInt(16)
        .putShort((short) 1)
        .putShort((short) 1)
        .putInt(16000)
        .putInt(32000)
        .putShort((short) 2)
        .putShort((short) 16)
        .putInt(0x61746164)
        .putInt(32000);
    return b.array();
  }

  Map<String, Object> entry() {
    return new HashMap<>(
        Map.ofEntries(
            Map.entry("bankVersion", "test"),
            Map.entry("questionId", q.id()),
            Map.entry("locale", "en"),
            Map.entry("text", q.text()),
            Map.entry("options", q.options()),
            Map.entry("readingMillis", 2000),
            Map.entry("audioSha256", NarrationBank.sha(wave())),
            Map.entry("durationMillis", 1000),
            Map.entry("listened", true),
            Map.entry("reviewedBy", "TEST_FIXTURE_ONLY"),
            Map.entry("reviewedAtMillis", 1),
            Map.entry(
                "rightsReference", "Synthetic silent test fixture; no real listening claim")));
  }

  NarrationBank load(List<Map<String, Object>> entries) throws Exception {
    Files.write(root.resolve(NarrationBank.sha(wave()) + ".wav"), wave());
    byte[] m = json.writeValueAsBytes(Map.of("schemaVersion", 1, "entries", entries));
    Files.write(root.resolve("manifest.json"), m);
    return new NarrationBank(root, NarrationBank.sha(m), 1000);
  }

  @Test
  void exactBindingAndDefensiveBytes() throws Exception {
    var bank = load(List.of(entry()));
    var clip = bank.find("test", q);
    assertEquals(1000, clip.durationMillis());
    var copy = clip.bytes();
    copy[0] = 0;
    assertEquals('R', clip.bytes()[0]);
    assertNull(bank.find("other", q));
    assertNull(bank.find("test", new Question("q1", "en", "Changed?", q.options(), 2, "x", 2000)));
    assertNull(
        bank.find(
            "test", new Question("q1", "en", q.text(), List.of("D", "C", "B", "A"), 2, "x", 2000)));
  }

  @Test
  void unlistenedAndBadReviewFail() throws Exception {
    for (var key : List.of("listened", "reviewedBy", "rightsReference", "reviewedAtMillis")) {
      var e = entry();
      e.put(key, key.equals("listened") ? false : key.equals("reviewedAtMillis") ? 2000 : "");
      assertThrows(IllegalArgumentException.class, () -> load(List.of(e)));
    }
  }

  @Test
  void shortReadingAndWrongDurationFail() {
    for (String key : List.of("readingMillis", "durationMillis")) {
      var e = entry();
      e.put(key, 100);
      assertThrows(IllegalArgumentException.class, () -> load(List.of(e)));
    }
  }

  @Test
  void duplicateBindingsAndUnknownFieldsFail() {
    assertThrows(IllegalArgumentException.class, () -> load(List.of(entry(), entry())));
    var e = entry();
    e.put("answer", 2);
    assertThrows(IllegalArgumentException.class, () -> load(List.of(e)));
  }

  @Test
  void manifestAndAudioTamperingFail() throws Exception {
    load(List.of(entry()));
    byte[] m = Files.readAllBytes(root.resolve("manifest.json"));
    assertThrows(
        IllegalArgumentException.class, () -> new NarrationBank(root, "0".repeat(64), 1000));
    Files.write(root.resolve(NarrationBank.sha(wave()) + ".wav"), new byte[32044]);
    assertThrows(
        IllegalArgumentException.class, () -> new NarrationBank(root, NarrationBank.sha(m), 1000));
  }

  @Test
  void symlinkAndTraversalFail() throws Exception {
    load(List.of(entry()));
    byte[] m = Files.readAllBytes(root.resolve("manifest.json"));
    Path audio = root.resolve(NarrationBank.sha(wave()) + ".wav");
    Files.delete(audio);
    Files.createSymbolicLink(audio, root.resolve("manifest.json"));
    assertThrows(
        IllegalArgumentException.class, () -> new NarrationBank(root, NarrationBank.sha(m), 1000));
    var e = entry();
    e.put("audioSha256", "../outside");
    assertThrows(IllegalArgumentException.class, () -> load(List.of(e)));
  }

  @Test
  void waveHeaderAndTrailingBytesFail() {
    var b = wave();
    b[22] = 2;
    assertThrows(IllegalArgumentException.class, () -> NarrationBank.duration(b));
    assertThrows(
        IllegalArgumentException.class, () -> NarrationBank.duration(Arrays.copyOf(wave(), 32046)));
  }

  @Test
  void scalarCoercionFails() {
    var e = entry();
    e.put("durationMillis", "1000");
    assertThrows(IllegalArgumentException.class, () -> load(List.of(e)));
  }
}
