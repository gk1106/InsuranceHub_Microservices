package com.insurancehub.gateway.domain;

import java.util.Arrays;
import java.util.Optional;

// The 4 known (serviceType, appStatusCode) pairs (api-contract.md §1), each carrying which
// Bean Validation group applies and which external endpoint it's valid on. resolve() is the
// single place "is this a real, correctly-paired, correctly-routed code" gets decided -
// HubDispatcher calls it before anything else touches the request body, so an unknown or
// mismatched pair (or a real code posted to the wrong endpoint) never reaches field validation.
public enum HubServiceCode {
  NEW_POLICY("NewPolicyService", "01", NewPolicy.class, HubEndpoint.POLICY_DETAIL),
  RENEWAL("RenewalService", "02", Renewal.class, HubEndpoint.POLICY_DETAIL),
  CLAIM_REGISTER("ClaimService", "03", Claim.class, HubEndpoint.POLICY_DETAIL),
  CLAIM_STATUS_UPDATE(
      "ClaimStatusService", "04", ClaimStatus.class, HubEndpoint.CLAIM_STATUS_UPDATE);

  private final String serviceType;
  private final String appStatusCode;
  private final Class<?> validationGroup;
  private final HubEndpoint endpoint;

  HubServiceCode(
      String serviceType, String appStatusCode, Class<?> validationGroup, HubEndpoint endpoint) {
    this.serviceType = serviceType;
    this.appStatusCode = appStatusCode;
    this.validationGroup = validationGroup;
    this.endpoint = endpoint;
  }

  public String serviceType() {
    return serviceType;
  }

  public String appStatusCode() {
    return appStatusCode;
  }

  public Class<?> validationGroup() {
    return validationGroup;
  }

  public HubEndpoint endpoint() {
    return endpoint;
  }

  // Empty if serviceType/appStatusCode don't form one of the 4 known pairs, or if the pair is
  // known but not valid on the endpoint the request actually arrived on.
  public static Optional<HubServiceCode> resolve(
      String serviceType, String appStatusCode, HubEndpoint endpoint) {
    return Arrays.stream(values())
        .filter(code -> code.serviceType.equals(serviceType))
        .filter(code -> code.appStatusCode.equals(appStatusCode))
        .filter(code -> code.endpoint == endpoint)
        .findFirst();
  }
}
