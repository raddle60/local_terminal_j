package local.term;

/**
 * Read-only view of a terminal text buffer used by {@link AutoScriptRunner}
 * to detect pattern matches. Decoupled from JediTerm so the runner can be
 * exercised by lightweight fakes.
 */
public interface OutputBuffer {

  /** Push-style notification when new lines arrive. The count is informational;
   *  callers usually scan all lines since a stored cursor index. */
  interface Listener {
    /** Invoked from the PTY reader thread with the number of lines added.
     *  Implementations must be thread-safe — moving to a single thread is
     *  the consumer's responsibility. */
    void linesChanged(int count);
  }

  void addListener(Listener listener);
  void removeListener(Listener listener);

  /** Total lines currently in the buffer (screen lines only — JediTerm's
   *  history lines are not exposed here). */
  int getLineCount();

  /** Plain-text content of line {@code index}; trailing whitespace may be
   *  present (JediTerm does not strip trailing spaces). */
  String getLineText(int index);
}
