package com.insurancehub.gateway.domain;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.insurancehub.gateway.mapping.ValidExternalDate;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

// Union of 03's and 04's claimDetails fields (api-contract.md §3) - a field only 04 uses (e.g.
// settledAmt) is simply unconstrained for the Claim(03) group and vice versa, same
// empty-string-as-null treatment RawPolicyDetails uses. @JsonAlias on intimationDate: spec quirk
// #7 - "IntimationDate" in some places, "intimationDate" in others.
@JsonIgnoreProperties(ignoreUnknown = true)
public record RawClaimDetails(
    // 04 only - "verify the claim belongs to that policy" (api-contract.md §7). 03's own
    // policyNum comes from policyDetails instead (see RawPolicyDetails), so this is irrelevant
    // and unconstrained for the Claim group.
    @NotBlank(groups = ClaimStatus.class) String policyNum,
    @NotBlank(groups = {Claim.class, ClaimStatus.class}) String claimNum,
    // Present in both, but api-contract.md only lists it as *required* for 03.
    @NotBlank(groups = Claim.class) String claimType,
    String lossDisc,
    String natureOfLoss,
    String lossCity,
    @NotBlank(groups = Claim.class)
        @Pattern(regexp = "^$|^\\d{2}/\\d{2}/\\d{4}$", groups = Claim.class)
        @ValidExternalDate(groups = Claim.class)
        String dateOfLoss,
    @JsonAlias("IntimationDate")
        @NotBlank(groups = Claim.class)
        @Pattern(
            regexp = "^$|^\\d{2}/\\d{2}/\\d{4}$",
            groups = {Claim.class, ClaimStatus.class})
        @ValidExternalDate(groups = {Claim.class, ClaimStatus.class})
        String intimationDate,
    @NotBlank(groups = Claim.class)
        @Pattern(
            regexp = "^$|^\\d{1,13}(\\.\\d{1,2})?$",
            groups = {Claim.class, ClaimStatus.class})
        String claimedAmt,
    // Required for 04 (the transition target); optional for 03 - defaults to REGISTERED when
    // blank (service-design.md §3).
    @NotBlank(groups = ClaimStatus.class) String claimStatus,
    String claimCode,
    @Pattern(regexp = "^$|^\\d{1,13}(\\.\\d{1,2})?$", groups = ClaimStatus.class) String settledAmt,
    @Pattern(regexp = "^$|^\\d{2}/\\d{2}/\\d{4}$", groups = ClaimStatus.class)
        @ValidExternalDate(groups = ClaimStatus.class)
        String finalizationDate,
    @Pattern(regexp = "^$|^\\d{1,9}$", groups = ClaimStatus.class) String osAgeing,
    String requireDetails,
    @Pattern(regexp = "^$|^\\d{2}/\\d{2}/\\d{4}$", groups = ClaimStatus.class)
        @ValidExternalDate(groups = ClaimStatus.class)
        String repuCancelDate,
    String reasonOfClosure) {}
