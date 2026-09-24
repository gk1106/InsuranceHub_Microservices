package com.insurancehub.gateway.api;

import com.insurancehub.common.error.HubResponse;
import com.insurancehub.gateway.domain.HubServiceCode;
import com.insurancehub.gateway.domain.RawHubRequestBody;

// One implementation per HubServiceCode (service-design.md §4: "dispatch... and mapping logic
// each live in their own class, so each can be tested in isolation"). Lives in api/, not
// application/: each handler owns mapping raw -> internal request (api.mapping) as well as
// calling the application-layer gateway port and decoding the result - it's the orchestrator,
// analogous to how PolicyController maps then calls PolicyCreationService.
public interface CodeHandler {

  HubServiceCode code();

  HubResponse handle(RawHubRequestBody body);
}
