package com.gaiprojects.quiz.narration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.gaiprojects.quiz.core.Question;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/**
 * Immutable, bounded, explicitly reviewed local assets. No URL fetching or runtime filesystem
 * access.
 */
public final class NarrationBank {
  public record Entry(
      String bankVersion,
      String questionId,
      String locale,
      String text,
      List<String> options,
      long readingMillis,
      String audioSha256,
      long durationMillis,
      boolean listened,
      String reviewedBy,
      long reviewedAtMillis,
      String rightsReference) {}

  public record Manifest(int schemaVersion, List<Entry> entries) {}

  public record Clip(String sha256, long durationMillis, byte[] bytes) {
    public Clip {
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }

  private record Bound(Entry entry, Clip clip) {}

  private final List<Bound> clips;
  private static final int MAX_WAVE = 1920044, MAX_TOTAL = 33554432;

  public NarrationBank(Path root, String manifestSha256, long now) {
    try {
      if (root == null
          || !root.isAbsolute()
          || !root.normalize().equals(root)
          || !root.toRealPath().equals(root)
          || !hash(manifestSha256)) throw new IllegalArgumentException();
      Path manifest = root.resolve("manifest.json");
      byte[] raw = read(manifest, 1048576);
      if (!sha(raw).equals(manifestSha256)) throw new IllegalArgumentException();
      var json =
          JsonMapper.builder()
              .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
              .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
              .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
              .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
              .build();
      Manifest m = json.readValue(raw, Manifest.class);
      if (m.schemaVersion() != 1
          || m.entries() == null
          || m.entries().isEmpty()
          || m.entries().size() > 100) throw new IllegalArgumentException();
      var loaded = new ArrayList<Bound>();
      var keys = new HashSet<List<String>>();
      int total = 0;
      for (Entry e : m.entries()) {
        if (e == null
            || blank(e.bankVersion(), 80)
            || blank(e.questionId(), 64)
            || !Set.of("en", "zh-CN").contains(e.locale())
            || blank(e.text(), 1000)
            || e.options() == null
            || e.options().size() != 4
            || e.options().stream().anyMatch(x -> blank(x, 400))
            || e.options().stream().distinct().count() != 4
            || e.readingMillis() < 1000
            || e.readingMillis() > 60000
            || !hash(e.audioSha256())
            || e.durationMillis() < 100
            || e.durationMillis() + 1000 > e.readingMillis()
            || !e.listened()
            || blank(e.reviewedBy(), 128)
            || e.reviewedAtMillis() <= 0
            || e.reviewedAtMillis() > now
            || blank(e.rightsReference(), 2000)
            || !keys.add(List.of(e.bankVersion(), e.questionId(), e.locale())))
          throw new IllegalArgumentException();
        byte[] wave = read(root.resolve(e.audioSha256() + ".wav"), MAX_WAVE);
        if ((total += wave.length) > MAX_TOTAL
            || !sha(wave).equals(e.audioSha256())
            || duration(wave) != e.durationMillis()) throw new IllegalArgumentException();
        loaded.add(new Bound(e, new Clip(e.audioSha256(), e.durationMillis(), wave)));
      }
      clips = List.copyOf(loaded);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid reviewed narration bundle");
    }
  }

  public Clip find(String bankVersion, Question q) {
    for (Bound b : clips) {
      Entry e = b.entry();
      if (e.bankVersion().equals(bankVersion)
          && e.questionId().equals(q.id())
          && e.locale().equals(q.locale())
          && e.text().equals(q.text())
          && e.options().equals(q.options())
          && e.readingMillis() == q.readingMillis()) return b.clip();
    }
    return null;
  }

  private static boolean blank(String s, int max) {
    return s == null
        || s.isBlank()
        || s.length() > max
        || s.codePoints().anyMatch(Character::isISOControl);
  }

  private static boolean hash(String h) {
    return h != null && h.matches("[a-f0-9]{64}");
  }

  private static byte[] read(Path p, int cap) throws Exception {
    if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(p)
        || Files.size(p) > cap) throw new IllegalArgumentException();
    try (var in = Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS)) {
      byte[] bytes = in.readNBytes(cap + 1);
      if (bytes.length > cap) throw new IllegalArgumentException();
      return bytes;
    }
  }

  public static String sha(byte[] raw) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static long duration(byte[] b) {
    if (b.length < 3244 || b.length > MAX_WAVE || (b.length - 44) % 2 != 0)
      throw new IllegalArgumentException();
    var v = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
    if (v.getInt(0) != 0x46464952
        || v.getInt(4) != b.length - 8
        || v.getInt(8) != 0x45564157
        || v.getInt(12) != 0x20746d66
        || v.getInt(16) != 16
        || v.getShort(20) != 1
        || v.getShort(22) != 1
        || v.getInt(24) != 16000
        || v.getInt(28) != 32000
        || v.getShort(32) != 2
        || v.getShort(34) != 16
        || v.getInt(36) != 0x61746164
        || v.getInt(40) != b.length - 44) throw new IllegalArgumentException();
    return ((b.length - 44) * 1000L + 31999) / 32000;
  }
}
