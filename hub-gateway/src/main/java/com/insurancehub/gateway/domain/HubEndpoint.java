package com.insurancehub.gateway.domain;

// The two external entry points (api-contract.md §1). A code is only valid on the endpoint the
// spec routes it through - 04's payload posted to /v1/policydetail is rejected the same way an
// unknown serviceType would be.
public enum HubEndpoint {
  POLICY_DETAIL,
  CLAIM_STATUS_UPDATE
}
