package com.insurancehub.gateway.domain;

// Bean Validation group marker for code 01 (api-contract.md §3's own group name). Lives in
// domain/, not api/, even though it's only ever used to annotate api/ request DTOs -
// HubServiceCode (also domain/) needs to reference it, and domain must never depend on api
// (LayeredArchitectureTest: "Api mayNotBeAccessedByAnyLayer"). Empty on purpose - groups are
// pure markers, never behavior.
public interface NewPolicy {}
