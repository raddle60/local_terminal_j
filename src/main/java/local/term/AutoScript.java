package local.term;

import java.util.List;

/**
 * An ordered list of {@link Step}s that runs automatically when the shell
 * node is double-clicked. The {@link #timeoutMs} is applied per step (single
 * shell-level setting, not per-step overrideable in v1). Inter-step delay
 * is {@link #STEP_INTERVAL_MS} milliseconds.
 *
 * <p>{@link #enabled} decouples "user has configured a script" from
 * "script actually runs". When the user unchecks the auto-script
 * enable box in {@link ShellEditorDialog}, the previously entered
 * steps are preserved (so re-enabling restores them) but
 * {@link TerminalLauncher} skips constructing an
 * {@link AutoScriptRunner}.
 */
public record AutoScript(int timeoutMs, List<Step> steps, boolean enabled) {
  /** Default timeout when the user does not change it (10 seconds). */
  public static final int DEFAULT_TIMEOUT_MS = 10_000;

  /** Wait between adjacent steps (100 ms). */
  public static final int STEP_INTERVAL_MS = 100;

  public AutoScript {
    if (timeoutMs <= 0) {
      throw new IllegalArgumentException(
          "timeoutMs must be > 0, got " + timeoutMs);
    }
    // Null list is tolerated and treated as empty — parsing helper
    // parseSteps() in TagConfigStore historically relies on this.
    steps = steps == null ? List.of() : List.copyOf(steps);
  }

  /**
   * Backward-compatible constructor for callers that pre-date the
   * {@code enabled} flag — defaults {@link #enabled()} to {@code true},
   * matching the prior behaviour where any non-null AutoScript was
   * considered enabled.
   */
  public AutoScript(int timeoutMs, List<Step> steps) {
    this(timeoutMs, steps, true);
  }
}
