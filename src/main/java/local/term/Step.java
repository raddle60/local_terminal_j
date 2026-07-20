package local.term;

import java.util.Objects;

/**
 * One step of an {@link AutoScript}: a {@code waitPattern} (may be empty for
 * "fire immediately") and a {@code command} (may be empty for a no-op step).
 * Both fields reject null; emptiness is allowed and handled by
 * {@link AutoScriptRunner}.
 */
public record Step(String waitPattern, String command) {
  public Step {
    Objects.requireNonNull(waitPattern, "waitPattern");
    Objects.requireNonNull(command, "command");
  }
}
