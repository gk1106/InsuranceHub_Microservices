package com.insurancehub.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.insurancehub.gateway.api.PreTrustResponseWriter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class RequestBodySizeFilterTest {

  private final PreTrustResponseWriter responseWriter =
      new PreTrustResponseWriter(JsonMapper.builder().build());
  private final RequestBodySizeFilter filter = new RequestBodySizeFilter(responseWriter);

  @Test
  void rejectsADeclaredContentLengthOverTheLimit() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setContentType("application/json");
    request.setContent(
        new byte[300 * 1024]); // MockHttpServletRequest derives Content-Length from this
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(413);
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  void allowsABodyWithinTheLimitThrough() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setContentType("application/json");
    request.setContent("{\"enc\":\"small\"}".getBytes());
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    verify(chain).doFilter(any(), any());
  }
}
