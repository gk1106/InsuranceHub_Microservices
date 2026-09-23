package com.insurancehub.claims.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;

// Config-driven domain rule (SKILL.md package layout: "domain/ ... domain rules"), not a
// Spring bean itself - config.ClaimStatusConfig builds one from config.ClaimStatusProperties.
// One check covers both conditions service-design.md §3 describes ("an unknown status, or a
// transition out of a terminal state, -> INVALID_STATUS_TRANSITION"): an unrecognized target
// status is never in any source's allowed list either, so it fails the same way without a
// separate code path. Same-status transitions (e.g. UNDER_PROCESS -> UNDER_PROCESS) are not
// special-cased - they're allowed exactly when the configured transitions list says so
// (docs/open-questions.md Q3).
public class ClaimStatusPolicy {

  private final Set<String> terminal;
  private final Map<String, List<String>> transitions;

  public ClaimStatusPolicy(Set<String> terminal, Map<String, List<String>> transitions) {
    this.terminal = terminal;
    this.transitions = transitions;
  }

  public boolean isTransitionAllowed(String from, String to) {
    if (terminal.contains(from)) {
      return false;
    }
    return transitions.getOrDefault(from, List.of()).contains(to);
  }
}
