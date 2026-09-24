package com.insurancehub.gateway.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.insurancehub.gateway.mapping.ValidExternalDate;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

// api-contract.md §3's full policyDetails field list, every field an unconstrained String -
// per-field/per-group constraints are the only thing that makes a field "required", so a field
// irrelevant to the resolved code (e.g. everything here except policyNum, for code 03) never
// gets checked even if it arrives as "". @Pattern's "^$|..." form allows blank-or-valid, since
// most of these fields are format-checked-if-present rather than required.
@JsonIgnoreProperties(ignoreUnknown = true)
public record RawPolicyDetails(
    String regionCode,
    String regionName,
    String branchCode,
    String branchName,
    @NotBlank(groups = {NewPolicy.class, Renewal.class}) String cif,
    String accountNum,
    @NotBlank(groups = {NewPolicy.class, Renewal.class}) String insuranceType,
    String insuranceName,
    String applicationNum,
    // Required for 01/02 (the policy this term belongs to) and 03 (service-design.md §4 /
    // api-contract.md's "Required for 03" - the only policyDetails field 03 actually uses).
    @NotBlank(groups = {NewPolicy.class, Renewal.class, Claim.class}) String policyNum,
    @NotBlank(groups = {NewPolicy.class, Renewal.class}) String name,
    @Pattern(
            regexp = "^$|^\\d{10}$",
            groups = {NewPolicy.class, Renewal.class})
        String mobileNum,
    String address,
    String applicationStatus,
    @Pattern(
            regexp = "^$|^\\d{2}/\\d{2}/\\d{4}$",
            groups = {NewPolicy.class, Renewal.class})
        @ValidExternalDate(groups = {NewPolicy.class, Renewal.class})
        String issueDate,
    @NotBlank(groups = {NewPolicy.class, Renewal.class})
        @Pattern(
            regexp = "^$|^\\d{2}/\\d{2}/\\d{4}$",
            groups = {NewPolicy.class, Renewal.class})
        @ValidExternalDate(groups = {NewPolicy.class, Renewal.class})
        String startDate,
    @NotBlank(groups = {NewPolicy.class, Renewal.class})
        @Pattern(
            regexp = "^$|^\\d{2}/\\d{2}/\\d{4}$",
            groups = {NewPolicy.class, Renewal.class})
        @ValidExternalDate(groups = {NewPolicy.class, Renewal.class})
        String expiryDate,
    @NotBlank(groups = {NewPolicy.class, Renewal.class})
        @Pattern(
            regexp = "^$|^\\d{1,13}(\\.\\d{1,2})?$",
            groups = {NewPolicy.class, Renewal.class})
        String netPremium,
    @Pattern(
            regexp = "^$|^\\d{1,13}(\\.\\d{1,2})?$",
            groups = {NewPolicy.class, Renewal.class})
        String gstAmt,
    @NotBlank(groups = {NewPolicy.class, Renewal.class})
        @Pattern(
            regexp = "^$|^\\d{1,13}(\\.\\d{1,2})?$",
            groups = {NewPolicy.class, Renewal.class})
        String grossPremium,
    @NotBlank(groups = {NewPolicy.class, Renewal.class})
        @Pattern(
            regexp = "^$|^\\d{1,13}(\\.\\d{1,2})?$",
            groups = {NewPolicy.class, Renewal.class})
        String sumInsured,
    String loanAcctNum,
    String specPerNum,
    String specPerName,
    // Format-checked here; the 0-100 range is checked programmatically in
    // HubRequestValidator, once the format is already known to be a valid amount.
    @Pattern(
            regexp = "^$|^\\d{1,13}(\\.\\d{1,2})?$",
            groups = {NewPolicy.class, Renewal.class})
        String commissionPer,
    @Pattern(
            regexp = "^$|^\\d{1,13}(\\.\\d{1,2})?$",
            groups = {NewPolicy.class, Renewal.class})
        String commissionAmt) {}
