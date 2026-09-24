package com.insurancehub.gateway.mapping;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// A blank value always passes - pair with @NotBlank in the same group for presence. Catches the
// case @Pattern's format-only regex can't: 30/02/2024 matches ^\d{2}/\d{2}/\d{4}$ but isn't a
// real date.
@Documented
@Constraint(validatedBy = ValidExternalDateValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidExternalDate {

  String message() default "must be a real dd/MM/yyyy date";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
