package com.gaiprojects.quiz.quota;

import com.gaiprojects.quiz.api.RequestQuota;
import com.gaiprojects.quiz.core.Player;
import com.gaiprojects.quiz.core.RuleException;
import java.util.Objects;
import java.util.UUID;

/** Atomic rolling admission, shared by all instances. No local counter or permissive fallback. */
public final class RedisRequestQuota implements RequestQuota {
  static final String SCRIPT =
      """
      local clock = redis.call('TIME')
      local now = clock[1] * 1000 + math.floor(clock[2] / 1000)
      for i = 1, #KEYS do
        local kind = redis.call('TYPE', KEYS[i]).ok
        if kind ~= 'none' and kind ~= 'zset' then return -1 end
        if kind == 'zset' and redis.call('PTTL', KEYS[i]) < 0 then return -1 end
      end
      for i = 1, #KEYS do
        redis.call('ZREMRANGEBYSCORE', KEYS[i], '-inf', now - 60000)
        if redis.call('ZCARD', KEYS[i]) >= tonumber(ARGV[i + 1]) then return 0 end
      end
      for i = 1, #KEYS do
        redis.call('ZADD', KEYS[i], now, ARGV[1])
        redis.call('PEXPIRE', KEYS[i], 65000)
      end
      return 1
      """;

  @FunctionalInterface
  interface Executor {
    Long eval(String script, String[] keys, String[] arguments);
  }

  private final Executor executor;

  RedisRequestQuota(Executor executor) {
    this.executor = Objects.requireNonNull(executor);
  }

  @Override
  public void check(Player player, String path) {
    Objects.requireNonNull(player);
    Objects.requireNonNull(path);
    String prefix = "quiz:quota:v1:{" + player.siteId() + ":" + player.siteUserId() + "}:";
    boolean create = path.equals("/api/quiz/sessions");
    String[] keys =
        create ? new String[] {prefix + "all", prefix + "create"} : new String[] {prefix + "all"};
    String nonce = UUID.randomUUID().toString();
    String[] args = create ? new String[] {nonce, "120", "6"} : new String[] {nonce, "120"};
    Long result;
    try {
      result = executor.eval(SCRIPT, keys, args);
    } catch (RuntimeException failure) {
      throw new RuleException("RATE_LIMIT_UNAVAILABLE", 503);
    }
    if (Long.valueOf(0).equals(result)) throw new RuleException("RATE_LIMITED", 429);
    if (!Long.valueOf(1).equals(result)) throw new RuleException("RATE_LIMIT_UNAVAILABLE", 503);
  }
}
