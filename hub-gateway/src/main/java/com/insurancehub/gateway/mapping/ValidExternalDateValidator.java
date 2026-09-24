package com.insurancehub.gateway.mapping;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.format.DateTimeParseException;

public class ValidExternalDateValidator implements ConstraintValidator<ValidExternalDate, String> {

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    if (value == null || value.isBlank()) {
      return true;
    }
    try {
      ExternalDateConverter.parse(value);
      return true;
    } catch (DateTimeParseException e) {
      return false;
    }
  }
}
