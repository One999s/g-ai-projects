package com.gaiprojects.quiz.quota;

import com.gaiprojects.quiz.api.RequestQuota;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "quiz.redis.enabled", havingValue = "true")
public class RedisQuotaConfiguration {
  @Bean(destroyMethod = "close")
  RedisQuotaConnection quizQuotaConnection(Environment env) {
    return new RedisQuotaConnection(RedisQuotaEndpoint.read(env));
  }

  @Bean
  RequestQuota quizRequestQuota(RedisQuotaConnection connection) {
    return new RedisRequestQuota(connection);
  }
}
