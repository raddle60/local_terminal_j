package local.term;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AutoScriptRunnerTest {

  static final class FakeSink implements AutoScriptRunner.Sink {
    final List<byte[]> writes = new ArrayList<>();
    final AtomicInteger closeCount = new AtomicInteger();
    /** When non-null, write() throws this instead of recording the bytes. */
    IOException writeFailure;
    @Override public void write(byte[] bytes) throws IOException {
      if (writeFailure != null) throw writeFailure;
      writes.add(bytes);
    }
    @Override public void close() { closeCount.incrementAndGet(); }
    @Override public boolean isClosed() { return closeCount.get() > 0; }
  }

  static final class FakeBuffer implements OutputBuffer {
    final List<String> lines = new ArrayList<>();
    final List<Listener> listeners = new ArrayList<>();
    void appendLine(String text) {
      lines.add(text);
      // Snapshot the listeners list — a callback may synchronously trigger
      // the runner to removeListener, which would otherwise CME the loop.
      for (Listener l : new ArrayList<>(listeners)) l.linesChanged(1);
    }
    @Override public void addListener(Listener l) { listeners.add(l); }
    @Override public void removeListener(Listener l) { listeners.remove(l); }
    @Override public int getLineCount() { return lines.size(); }
    @Override public String getLineText(int index) { return lines.get(index); }
  }

  private FakeSink sink;
  private FakeBuffer buffer;
  private static final int TEST_INTERVAL_MS = 20;

  @BeforeEach void setUp() { sink = new FakeSink(); buffer = new FakeBuffer(); }
  @AfterEach  void tearDown() { /* nothing — each test owns its session+runner */ }

  private AutoScriptRunner runner(AutoScript script) {
    AutoScriptRunner r = new AutoScriptRunner(script, sink, buffer, TEST_INTERVAL_MS);
    r.start();
    return r;
  }

  /** Like {@link #runner} but also wires a timeout callback and exposes it. */
  private AutoScriptRunner runnerWithTimeoutCb(AutoScript script,
      AtomicReference<AutoScriptRunner.TimeoutInfo> cbSink) {
    java.util.function.Consumer<AutoScriptRunner.TimeoutInfo> cb = cbSink::set;
    AutoScriptRunner r = new AutoScriptRunner(script, sink, buffer,
        TEST_INTERVAL_MS, "test", cb, null);
    r.start();
    return r;
  }

  private static void await(long ms) {
    try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
  }

  // ---------- tests ----------

  @Test
  void run_zeroSteps_completesImmediatelyWithoutWriting() {
    AutoScript s = new AutoScript(1000, List.of());
    AutoScriptRunner r = runner(s);
    await(200);
    assertTrue(sink.writes.isEmpty());
    assertEquals(0, sink.closeCount.get());
  }

  @Test
  void run_singleStepMatch_writesCommandAfterMatch() {
    AutoScript s = new AutoScript(1000,
        List.of(new Step("login:", "myuser")));
    AutoScriptRunner r = runner(s);
    await(50);   // let thread start, snapshot cursor
    buffer.appendLine("the login: prompt");
    await(150);  // let script finish
    assertEquals(1, sink.writes.size());
    assertEquals("myuser\r", new String(sink.writes.get(0)));
  }

  @Test
  void run_allStepsOrdered_writesEachInOrder() {
    AutoScript s = new AutoScript(2000, List.of(
        new Step("login:", "myuser"),
        new Step("Password*", "mypass"),
        new Step("Welcome*", "ls")));
    AutoScriptRunner r = runner(s);
    await(50); buffer.appendLine("login:");
    await(150); buffer.appendLine("Password for myuser:");
    await(150); buffer.appendLine("Welcome to bash");
    await(150);
    assertEquals(List.of("myuser\r", "mypass\r", "ls\r"),
        sink.writes.stream().map(String::new).toList());
    assertEquals(0, sink.closeCount.get());
  }

  @Test
  void run_emptyWaitPattern_step0_waitsForFirstShellOutput() {
    // Blank pattern at step 0 must NOT fire the moment the PTY exists:
    // JediTerm still has init sequences to send (ESC[1t, ESC[c, focus
    // events, win32-input, hide-cursor, position-cursor — hundreds of ms
    // on Windows), and PSReadLine would receive bytes mid-init and stick
    // the shell in `>> ` continuation mode. Wait for first non-empty
    // line instead.
    AutoScript s = new AutoScript(1000, List.of(new Step("", "echo hi")));
    AutoScriptRunner r = runner(s);
    await(80);
    assertEquals(0, sink.writes.size(),
        "runner must not send while the shell has produced no output yet");
    buffer.appendLine("PS D:\\tmp>");
    await(150);
    assertEquals(1, sink.writes.size());
    assertEquals("echo hi\r", new String(sink.writes.get(0)));
  }

  @Test
  void run_emptyWaitPattern_stepN_sendsAfterInterval() {
    // Blank pattern at step N>0 honors the inter-step interval; the
    // first step's wait-for-output gate doesn't apply.
    AutoScript s = new AutoScript(1000, List.of(
        new Step("login:", "myuser"),
        new Step("", "echo hi")));
    AutoScriptRunner r = runner(s);
    await(50); buffer.appendLine("login:");
    // Step 0 fires, then step 1's interval is TEST_INTERVAL_MS=20.
    // Wait long enough that step 1 has fired too — both writes must be
    // present and in order.
    await(200);
    assertEquals(2, sink.writes.size(),
        "step 1 must fire after step 0, gated by the inter-step interval");
    assertEquals("myuser\r", new String(sink.writes.get(0)));
    assertEquals("echo hi\r", new String(sink.writes.get(1)));
  }

  @Test
  void run_emptyWaitPattern_step0NoOutput_sendsAfterTimeout() {
    // If the shell never produces output (broken PTY, hung process), the
    // runner eventually falls through and sends anyway — better to attempt
    // the command than to silently do nothing.
    AutoScript s = new AutoScript(150, List.of(new Step("", "echo hi")));
    AutoScriptRunner r = runner(s);
    await(400);
    assertEquals(1, sink.writes.size(),
        "runner must eventually send even without shell output");
    assertEquals("echo hi\r", new String(sink.writes.get(0)));
  }

  @Test
  void run_timeoutAtStepK_invokesCallbackAndLeavesSinkOpen() {
    // Per product decision: on wait timeout the auto-script halts and
    // notifies the caller via a callback (so the UI can show a dialog).
    // The session itself stays open — the user can still type into the
    // terminal and decide whether to close the tab.
    AtomicReference<AutoScriptRunner.TimeoutInfo> cb = new AtomicReference<>();
    AutoScript s = new AutoScript(150, List.of(
        new Step("never:", "x")));
    AutoScriptRunner r = runnerWithTimeoutCb(s, cb);
    await(400);
    AutoScriptRunner.TimeoutInfo info = cb.get();
    assertNotNull(info, "timeout callback must fire");
    assertEquals(0, info.stepIdx());
    assertEquals("never:", info.waitPattern());
    assertEquals(150, info.timeoutMs());
    assertEquals(0, sink.closeCount.get(),
        "timeout must NOT close the sink — tab stays open");
    assertTrue(sink.writes.isEmpty(),
        "no command should have been sent before the timeout fired");
  }

  @Test
  void run_timeoutAtStepK_noCallback_doesNotCrash() {
    // A caller that didn't supply an onTimeout handler should still see the
    // script halt gracefully (log only, no exception).
    AutoScript s = new AutoScript(150, List.of(new Step("never:", "x")));
    AutoScriptRunner r = runner(s);  // no callback
    await(400);
    assertEquals(0, sink.closeCount.get(),
        "no onTimeout handler means no callback and no sink close");
  }

  @Test
  void run_existingOutputAtStart_isInScope() {
    // Pre-populate the buffer BEFORE start. The cursor at run() starts at 0
    // (cursor advances only past matches), so the wait sees them.
    buffer.appendLine("login:");
    AutoScript s = new AutoScript(500, List.of(
        new Step("login:", "myuser")));
    AutoScriptRunner r = runner(s);
    await(150);
    assertEquals(1, sink.writes.size());
    assertEquals("myuser\r", new String(sink.writes.get(0)));
  }

  @Test
  void run_ownEchoedCommandIsInScope() {
    AutoScript s = new AutoScript(2000, List.of(
        new Step("login:", "myuser"),
        new Step("echoed:", "next")));
    AutoScriptRunner r = runner(s);
    await(50); buffer.appendLine("login:");
    await(150); // runner writes "myuser"; simulate the shell echoing it
    buffer.appendLine("echoed: myuser was applied");
    await(150);
    assertEquals(2, sink.writes.size());
    assertEquals("next\r", new String(sink.writes.get(1)));
  }

  @Test
  void run_manuallyClosedSink_exitsCleanly() {
    AutoScript s = new AutoScript(5000, List.of(
        new Step("never:", "x")));
    AutoScriptRunner r = runner(s);
    await(50); sink.close();
    await(200);
    assertEquals(1, sink.closeCount.get());
  }

  @Test
  void run_commandIncludesNewline() {
    AutoScript s = new AutoScript(1000, List.of(
        new Step("login:", "myuser")));
    AutoScriptRunner r = runner(s);
    await(50); buffer.appendLine("login:");
    await(150);
    assertEquals(1, sink.writes.size());
    byte[] w = sink.writes.get(0);
    assertEquals("myuser\r", new String(w),
        "runner must append CR (\\r) to each command. CR is the universal "
            + "Enter terminator: Windows ConPTY + PowerShell PSReadLine map "
            + "CR to the Enter key, and the Linux terminal driver in canonical "
            + "mode treats CR as a line terminator. LF alone puts PowerShell "
            + "into the `>> ` continuation prompt and LF on Linux becomes a "
            + "literal newline between commands.");
  }

  // ---------- progress listener tests ----------

  /** Captures a chronological record of progress events. */
  private static final class RecordingProgress implements AutoScriptRunner.ProgressListener {
    final List<String> events = new CopyOnWriteArrayList<>();
    /** When true, each callback throws to verify the runner isolates the exception. */
    volatile boolean throwOnNext;
    @Override public void onStarted(int totalSteps, String tagName) {
      events.add("started:" + totalSteps + ":" + tagName);
      maybeThrow();
    }
    @Override public void onStepStarted(int stepIdx, Step step) {
      events.add("stepStarted:" + stepIdx + ":" + abbreviate(step.waitPattern())
          + "->" + abbreviate(step.command()));
      maybeThrow();
    }
    @Override public void onStepCompleted(int stepIdx, Step step) {
      events.add("stepCompleted:" + stepIdx);
      maybeThrow();
    }
    @Override public void onFinished(int totalSteps) {
      events.add("finished:" + totalSteps);
      maybeThrow();
    }
    @Override public void onFailed(int stepIdx, Step step,
                                   AutoScriptRunner.FailureKind kind, String reason) {
      events.add("failed:" + stepIdx + ":" + kind + ":" + abbreviate(reason));
      maybeThrow();
    }
    @Override public void onCancelled() {
      events.add("cancelled");
      maybeThrow();
    }
    private void maybeThrow() {
      if (throwOnNext) {
        throwOnNext = false;
        throw new RuntimeException("boom");
      }
    }
  }

  private static String abbreviate(String s) {
    if (s == null) return "(null)";
    return s.length() <= 40 ? s : s.substring(0, 37) + "...";
  }

  /** Build a runner that wires a RecordingProgress. */
  private AutoScriptRunner runnerWithProgress(AutoScript script,
                                              RecordingProgress progress) {
    AutoScriptRunner r = new AutoScriptRunner(script, sink, buffer,
        TEST_INTERVAL_MS, "test", null, progress);
    r.start();
    return r;
  }

  @Test
  void progressListener_stepEventsFireInOrder() {
    // 3-step script. We must trigger matches one at a time so the runner
    // doesn't fire everything on a single notification burst.
    AutoScript s = new AutoScript(2000, List.of(
        new Step("login:", "myuser"),
        new Step("Password*", "mypass"),
        new Step("Welcome*", "ls")));
    RecordingProgress p = new RecordingProgress();
    runnerWithProgress(s, p);

    await(80);   // let step 0 start, capture its stepStarted
    assertTrue(p.events.stream().anyMatch(e -> e.startsWith("started:3:test")),
        "onStarted must fire first: " + p.events);
    assertTrue(p.events.contains("stepStarted:0:login:->myuser"),
        "step 0 stepStarted: " + p.events);

    buffer.appendLine("login:");
    await(120);
    assertTrue(p.events.contains("stepCompleted:0"),
        "step 0 completed after match: " + p.events);
    assertTrue(p.events.contains("stepStarted:1:Password*->mypass"),
        "step 1 stepStarted: " + p.events);

    buffer.appendLine("Password for myuser:");
    await(120);
    assertTrue(p.events.contains("stepCompleted:1"),
        "step 1 completed: " + p.events);
    assertTrue(p.events.contains("stepStarted:2:Welcome*->ls"),
        "step 2 stepStarted: " + p.events);

    buffer.appendLine("Welcome to bash");
    await(150);
    assertTrue(p.events.contains("stepCompleted:2"),
        "step 2 completed: " + p.events);
    assertTrue(p.events.contains("finished:3"),
        "onFinished must fire after all steps complete: " + p.events);
    assertFalse(p.events.stream().anyMatch(e -> e.startsWith("failed:")),
        "no failed events expected on success path: " + p.events);
    assertFalse(p.events.contains("cancelled"),
        "no cancelled events expected on success path: " + p.events);
  }

  @Test
  void progressListener_failedEventOnTimeout() {
    AutoScript s = new AutoScript(150, List.of(new Step("never:", "x")));
    RecordingProgress p = new RecordingProgress();
    runnerWithProgress(s, p);
    await(400);
    assertTrue(p.events.stream().anyMatch(e -> e.startsWith("failed:0:")
            && e.contains("TIMEOUT") && e.contains("never:")),
        "expected TIMEOUT failure at step 0 with pattern 'never:' in reason: "
            + p.events);
    // No finished event after a halt.
    assertFalse(p.events.stream().anyMatch(e -> e.startsWith("finished:")),
        "halted script must not emit finished: " + p.events);
  }

  @Test
  void progressListener_writeErrorFiresFailedAndStops() {
    // 2-step script. The very first write (step 0 command "myuser") throws.
    // Expected: stepStarted fires for step 0, then onFailed(WRITE_ERROR) at
    // step 0, no onStepCompleted, no step 1 events.
    AutoScript s = new AutoScript(2000, List.of(
        new Step("login:", "myuser"),
        new Step("Password*", "mypass")));
    RecordingProgress p = new RecordingProgress();
    sink.writeFailure = new IOException("PTY closed");
    AutoScriptRunner r = runnerWithProgress(s, p);
    await(60);   // step 0 waiting
    buffer.appendLine("login:");   // triggers send → IOException
    await(200);
    assertTrue(p.events.contains("stepStarted:0:login:->myuser"),
        "step 0 must have started: " + p.events);
    assertTrue(p.events.stream().anyMatch(e -> e.startsWith("failed:0:")
            && e.contains("WRITE_ERROR")),
        "expected WRITE_ERROR failure at step 0: " + p.events);
    assertFalse(p.events.stream().anyMatch(e -> e.startsWith("stepCompleted:")),
        "no step should be marked completed when write fails: " + p.events);
    assertFalse(p.events.stream().anyMatch(e -> e.startsWith("stepStarted:1:")),
        "step 1 must not start after a write error: " + p.events);
  }

  @Test
  void progressListener_nullListener_doesNotThrow() {
    // Pass null as the ProgressListener — old call paths must still work.
    AutoScript s = new AutoScript(1000, List.of(new Step("login:", "myuser")));
    AutoScriptRunner r = new AutoScriptRunner(s, sink, buffer,
        TEST_INTERVAL_MS, "test", null, null);
    r.start();
    await(60); buffer.appendLine("login:");
    await(200);
    assertEquals(1, sink.writes.size(),
        "null listener must not break the normal send path");
  }

  @Test
  void progressListener_sinkCloseDuringWait_emitsCancelled() {
    // 3-step script. Step 1 waits forever; user closes the tab → sink.close()
    // while the latch is parked. Runner must notice via the periodic poll
    // and emit onCancelled — NOT onFailed(TIMEOUT).
    AutoScript s = new AutoScript(5000, List.of(
        new Step("login:", "myuser"),
        new Step("never:", "x"),
        new Step("ignored:", "z")));
    RecordingProgress p = new RecordingProgress();
    runnerWithProgress(s, p);
    await(60); buffer.appendLine("login:");     // step 0 completes
    await(150);                                 // step 1 starts waiting
    assertTrue(p.events.contains("stepStarted:1:never:->x"),
        "step 1 must have started waiting: " + p.events);
    sink.close();                               // tab/user closes the sink
    await(300);
    assertTrue(p.events.contains("cancelled"),
        "expected onCancelled when sink is closed mid-wait: " + p.events);
    assertFalse(p.events.stream().anyMatch(e -> e.startsWith("failed:")),
        "sink close must not be reported as a TIMEOUT failure: " + p.events);
    assertFalse(p.events.contains("stepStarted:2:ignored:->z"),
        "no further step may start after cancellation: " + p.events);
  }

  @Test
  void progressListener_listenerThrows_isIsolated() {
    // Listener that throws from every callback. Runner must log + continue,
    // still complete all steps and fire onFinished.
    AutoScript s = new AutoScript(2000, List.of(
        new Step("login:", "myuser"),
        new Step("Password*", "mypass")));
    RecordingProgress p = new RecordingProgress();
    p.throwOnNext = true;   // will throw on every call (re-armed by maybeThrow)
    runnerWithProgress(s, p);
    await(80);   // started + stepStarted 0 both throw → runner must not die
    buffer.appendLine("login:");
    await(150);
    buffer.appendLine("Password for myuser:");
    await(200);
    // Both writes still happened despite listener explosions.
    assertEquals(2, sink.writes.size(),
        "runner must continue past listener exceptions: " + sink.writes.size());
    assertTrue(p.events.contains("finished:2"),
        "onFinished must still fire after all listener exceptions: " + p.events);
  }
}
