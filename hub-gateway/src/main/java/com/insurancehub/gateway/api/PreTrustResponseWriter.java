package com.insurancehub.gateway.api;

import com.insurancehub.common.error.HubErrorCode;
import com.insurancehub.common.error.HubResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

// Shared by HubAuthenticationEntryPoint, HubAccessDeniedHandler and IpAllowlistFilter/
// RequestBodySizeFilter - every rejection that happens before trust is established
// (api-contract.md §4) writes this exact plain-JSON shape, never the crypto envelope, since
// nothing has been decrypted yet at the point any of these fire.
@Component
public class PreTrustResponseWriter {

  private final ObjectMapper objectMapper;

  public PreTrustResponseWriter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public void write(HttpServletResponse response, HubErrorCode code) throws IOException {
    response.setStatus(code.httpStatus().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write(objectMapper.writeValueAsString(HubResponse.preTrustFailure(code)));
  }
}
