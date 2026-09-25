package com.insurancehub.policy.infrastructure.security;

import static com.insurancehub.common.web.HubHeaders.INTERNAL_AUTH;

import com.insurancehub.common.error.HubErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

// docs/adr/0007-internal-service-auth.md: defense-in-depth behind network isolation, not the
// primary control. Registered only in front of /internal/** (see InternalAuthFilterConfig) -
// mirrors hub-gateway's IpAllowlistFilter's weight class: a plain servlet filter, not full
// Spring Security (this module has no Spring Security dependency, and none is added for this).
// Runs before DispatcherServlet, so a rejection is written directly here, not via
// @RestControllerAdvice - PolicyExceptionHandler is never reached from a filter.
public class InternalAuthFilter extends OncePerRequestFilter {

  private final String expectedSecret;
  private final ObjectMapper objectMapper;

  public InternalAuthFilter(String expectedSecret, ObjectMapper objectMapper) {
    this.expectedSecret = expectedSecret;
    this.objectMapper = objectMapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String provided = request.getHeader(INTERNAL_AUTH);
    if (provided == null || !constantTimeEquals(provided, expectedSecret)) {
      writeRejection(response);
      return;
    }
    filterChain.doFilter(request, response);
  }

  // MessageDigest.isEqual, not String.equals - a naive comparison short-circuits on the first
  // mismatched byte, which leaks how many leading characters were correct via response timing.
  private static boolean constantTimeEquals(String provided, String expected) {
    return MessageDigest.isEqual(
        provided.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
  }

  private void writeRejection(HttpServletResponse response) throws IOException {
    HubErrorCode code = HubErrorCode.INTERNAL_AUTH_FAILED;
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.httpStatus(), code.errorDesc());
    problem.setProperty("code", code.name());
    response.setStatus(code.httpStatus().value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write(objectMapper.writeValueAsString(problem));
  }
}
