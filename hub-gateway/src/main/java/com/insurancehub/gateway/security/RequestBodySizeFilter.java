package com.insurancehub.gateway.security;

import static com.insurancehub.common.error.HubErrorCode.PAYLOAD_TOO_LARGE;

import com.insurancehub.gateway.api.PreTrustResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.web.filter.OncePerRequestFilter;

// cross-cutting.md §4: 256 KB request body size limit (hub-gateway only - the only place an
// untrusted external caller's body ever lands). Two layers: a declared Content-Length over the
// limit is rejected immediately, before any body is read; a stream wrapper also enforces the
// cap while actually reading, as a backstop against a missing/understated Content-Length
// (chunked transfer encoding) - that backstop deliberately isn't precisely categorized as
// PAYLOAD_TOO_LARGE (it surfaces as a generic read failure deep inside Spring MVC's body
// parsing) since the realistic, tested case is the Content-Length check above.
public class RequestBodySizeFilter extends OncePerRequestFilter {

  private static final long MAX_BYTES = 256 * 1024L;

  private final PreTrustResponseWriter responseWriter;

  public RequestBodySizeFilter(PreTrustResponseWriter responseWriter) {
    this.responseWriter = responseWriter;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    long declared = request.getContentLengthLong();
    if (declared > MAX_BYTES) {
      responseWriter.write(response, PAYLOAD_TOO_LARGE);
      return;
    }
    filterChain.doFilter(new SizeLimitingRequestWrapper(request, MAX_BYTES), response);
  }

  private static final class SizeLimitingRequestWrapper extends HttpServletRequestWrapper {

    private final long maxBytes;

    SizeLimitingRequestWrapper(HttpServletRequest request, long maxBytes) {
      super(request);
      this.maxBytes = maxBytes;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
      return new SizeLimitingServletInputStream(super.getInputStream(), maxBytes);
    }

    @Override
    public BufferedReader getReader() throws IOException {
      String encoding = getCharacterEncoding();
      Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
      return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }
  }

  private static final class SizeLimitingServletInputStream extends ServletInputStream {

    private final ServletInputStream delegate;
    private final long maxBytes;
    private long bytesRead;

    SizeLimitingServletInputStream(ServletInputStream delegate, long maxBytes) {
      this.delegate = delegate;
      this.maxBytes = maxBytes;
    }

    @Override
    public int read() throws IOException {
      int b = delegate.read();
      if (b != -1 && ++bytesRead > maxBytes) {
        throw new IOException("request body exceeded " + maxBytes + " bytes");
      }
      return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int n = delegate.read(b, off, len);
      if (n > 0 && (bytesRead += n) > maxBytes) {
        throw new IOException("request body exceeded " + maxBytes + " bytes");
      }
      return n;
    }

    @Override
    public boolean isFinished() {
      return delegate.isFinished();
    }

    @Override
    public boolean isReady() {
      return delegate.isReady();
    }

    @Override
    public void setReadListener(ReadListener readListener) {
      delegate.setReadListener(readListener);
    }
  }
}
