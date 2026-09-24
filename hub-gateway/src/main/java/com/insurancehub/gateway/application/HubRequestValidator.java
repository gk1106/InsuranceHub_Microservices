package com.insurancehub.gateway.application;

import static com.insurancehub.common.error.HubErrorCode.VALIDATION_FAILED;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toCollection;

import com.insurancehub.common.error.HubBusinessException;
import com.insurancehub.gateway.domain.Claim;
import com.insurancehub.gateway.domain.NewPolicy;
import com.insurancehub.gateway.domain.RawClaimDetails;
import com.insurancehub.gateway.domain.RawHubRequestBody;
import com.insurancehub.gateway.domain.RawPolicyDetails;
import com.insurancehub.gateway.domain.Renewal;
import com.insurancehub.gateway.mapping.ExternalAmountConverter;
import com.insurancehub.gateway.mapping.ExternalDateConverter;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

// Programmatic group-based validation (jakarta.validation.Validator.validate(obj, group)) rather
// than @Valid-triggered validation, because which group applies depends on the HubServiceCode
// HubDispatcher already resolved - it isn't known until after JSON parsing. Builds the same
// "first 5 field names, no values" VALIDATION_FAILED shape already used by
// PolicyExceptionHandler/ClaimExceptionHandler. Validates header/policyDetails/claimDetails as
// three separate objects against the same group (rather than one cascaded @Valid call) so a
// field irrelevant to the resolved code, arriving blank, never gets checked - Bean Validation
// only evaluates a constraint whose declared groups intersect the requested one.
@Component
public class HubRequestValidator {

  private static final int MAX_FIELDS_REPORTED = 5;
  private static final BigDecimal ZERO = BigDecimal.ZERO;
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private final Validator validator;

  public HubRequestValidator(Validator validator) {
    this.validator = validator;
  }

  public void validate(RawHubRequestBody body, Class<?> group) {
    Set<String> fields = new LinkedHashSet<>();
    fields.addAll(fieldNames(validator.validate(body.header(), group)));
    if (body.policyDetails() != null) {
      fields.addAll(fieldNames(validator.validate(body.policyDetails(), group)));
    }
    if (body.claimDetails() != null) {
      fields.addAll(fieldNames(validator.validate(body.claimDetails(), group)));
    }
    fields.addAll(crossFieldViolations(body, group, fields));

    if (!fields.isEmpty()) {
      String joined = fields.stream().sorted().limit(MAX_FIELDS_REPORTED).collect(joining(", "));
      throw new HubBusinessException(
          VALIDATION_FAILED, VALIDATION_FAILED.errorDesc().replace("<field>", joined));
    }
  }

  private static Set<String> fieldNames(Set<? extends ConstraintViolation<?>> violations) {
    return violations.stream()
        .map(v -> v.getPropertyPath().toString())
        .collect(toCollection(LinkedHashSet::new));
  }

  // Cross-field/range checks Bean Validation's per-field constraints can't express cleanly on
  // their own: startDate<=expiryDate, dateOfLoss<=intimationDate, commissionPer 0-100. Each only
  // runs once its own fields are already known to be well-formed (not already in the violation
  // set), so this never re-parses a field already reported as invalid.
  private static Set<String> crossFieldViolations(
      RawHubRequestBody body, Class<?> group, Set<String> alreadyInvalid) {
    Set<String> extra = new LinkedHashSet<>();
    RawPolicyDetails policy = body.policyDetails();
    if (policy != null && (group == NewPolicy.class || group == Renewal.class)) {
      checkDateOrder(
          policy.startDate(),
          policy.expiryDate(),
          "startDate",
          "expiryDate",
          alreadyInvalid,
          extra);
      checkCommissionRange(policy.commissionPer(), alreadyInvalid, extra);
    }
    RawClaimDetails claim = body.claimDetails();
    if (claim != null && group == Claim.class) {
      checkDateOrder(
          claim.dateOfLoss(),
          claim.intimationDate(),
          "dateOfLoss",
          "intimationDate",
          alreadyInvalid,
          extra);
    }
    return extra;
  }

  private static void checkDateOrder(
      String earlierValue,
      String laterValue,
      String earlierField,
      String laterField,
      Set<String> alreadyInvalid,
      Set<String> extra) {
    if (alreadyInvalid.contains(earlierField) || alreadyInvalid.contains(laterField)) {
      return;
    }
    LocalDate earlier = ExternalDateConverter.parse(earlierValue);
    LocalDate later = ExternalDateConverter.parse(laterValue);
    if (earlier != null && later != null && earlier.isAfter(later)) {
      extra.add(earlierField);
      extra.add(laterField);
    }
  }

  private static void checkCommissionRange(
      String commissionPer, Set<String> alreadyInvalid, Set<String> extra) {
    if (alreadyInvalid.contains("commissionPer")) {
      return;
    }
    BigDecimal value = ExternalAmountConverter.parse(commissionPer);
    if (value != null && (value.compareTo(ZERO) < 0 || value.compareTo(HUNDRED) > 0)) {
      extra.add("commissionPer");
    }
  }
}
