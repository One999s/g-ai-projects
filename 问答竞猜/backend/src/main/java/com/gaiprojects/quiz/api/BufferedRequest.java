package com.gaiprojects.quiz.api;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

final class BufferedRequest extends HttpServletRequestWrapper {
  private final byte[] bytes;

  BufferedRequest(HttpServletRequest source, byte[] bytes) {
    super(source);
    this.bytes = bytes;
  }

  @Override
  public ServletInputStream getInputStream() {
    var input = new ByteArrayInputStream(bytes);
    return new ServletInputStream() {
      public int read() {
        return input.read();
      }

      public int read(byte[] b, int off, int len) {
        return input.read(b, off, len);
      }

      public boolean isFinished() {
        return input.available() == 0;
      }

      public boolean isReady() {
        return true;
      }

      public void setReadListener(ReadListener listener) {
        throw new UnsupportedOperationException("Synchronous bounded body only");
      }
    };
  }

  @Override
  public BufferedReader getReader() {
    return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
  }

  @Override
  public int getContentLength() {
    return bytes.length;
  }

  @Override
  public long getContentLengthLong() {
    return bytes.length;
  }
}
