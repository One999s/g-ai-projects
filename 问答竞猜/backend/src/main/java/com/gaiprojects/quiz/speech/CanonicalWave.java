package com.gaiprojects.quiz.speech;

import com.gaiprojects.quiz.core.RuleException;
import java.nio.*;
import java.nio.charset.StandardCharsets;

/** Only canonical 44-byte-header PCM16 mono 16kHz. No compressed media decoder or file paths. */
public final class CanonicalWave {
  public static final int MAX_BYTES = 192044;

  public static void verify(byte[] bytes) {
    if (bytes == null
        || bytes.length < 3244
        || bytes.length > MAX_BYTES
        || (bytes.length - 44) % 2 != 0) fail();
    var b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    if (!tag(bytes, 0, "RIFF")
        || b.getInt(4) != bytes.length - 8
        || !tag(bytes, 8, "WAVE")
        || !tag(bytes, 12, "fmt ")
        || b.getInt(16) != 16
        || b.getShort(20) != 1
        || b.getShort(22) != 1
        || b.getInt(24) != 16000
        || b.getInt(28) != 32000
        || b.getShort(32) != 2
        || b.getShort(34) != 16
        || !tag(bytes, 36, "data")
        || b.getInt(40) != bytes.length - 44) fail();
  }

  private static boolean tag(byte[] bytes, int offset, String value) {
    return new String(bytes, offset, 4, StandardCharsets.US_ASCII).equals(value);
  }

  private static void fail() {
    throw new RuleException("CANONICAL_WAV_REQUIRED", 415);
  }
}
