package com.gaiprojects.quiz.speech;

import java.io.*;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
  private final CompletableFuture<byte[]> result = new CompletableFuture<>();
  private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
  private Flow.Subscription subscription;

  public CompletionStage<byte[]> getBody() {
    return result;
  }

  public void onSubscribe(Flow.Subscription s) {
    subscription = s;
    s.request(1);
  }

  public void onNext(List<ByteBuffer> values) {
    for (var value : values) {
      if (value.remaining() > 4096 - bytes.size()) {
        subscription.cancel();
        result.completeExceptionally(new IOException("Response limit"));
        return;
      }
      byte[] next = new byte[value.remaining()];
      value.get(next);
      bytes.writeBytes(next);
    }
    subscription.request(1);
  }

  public void onError(Throwable t) {
    result.completeExceptionally(t);
  }

  public void onComplete() {
    result.complete(bytes.toByteArray());
  }
}
