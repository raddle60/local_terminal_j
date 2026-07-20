# Shell Node Auto-Script Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an optional auto-script (glob-wait + command pairs) to `TagNode.Shell` that runs at PTY launch, fires each pair when the output matches, and aborts on timeout.

**Architecture:** New `AutoScriptRunner` watches JediTerm's `TerminalTextBuffer` via a small adapter (`OutputBuffer` interface); each step waits for a glob-match in any line since script start, writes a command to the PTY via the connector, sleeps 100 ms, advances. Timeout triggers `session.close()` which removes the tab. `Shell` gets an optional `autoScript` field (nullable, schema-backward-compatible).

**Tech Stack:** Java 17, Swing, JediTerm 3.73, Jackson, JUnit 5. No new dependencies.

---

## File Structure

**New files (production):**
- `src/main/java/local/term/Step.java` — `record Step(String waitPattern, String command)` value object.
- `src/main/java/local/term/AutoScript.java` — `record AutoScript(int timeoutMs, List<Step> steps)` value object.
- `src/main/java/local/term/GlobMatcher.java` — glob utility; literal patterns auto-wrap `*…*`.
- `src/main/java/local/term/OutputBuffer.java` — small interface (`addListener`/`removeListener`/`getLineCount`/`getLineText`) decoupled from JediTerm.
- `src/main/java/local/term/JediOutputBuffer.java` — `OutputBuffer` adapter over `TerminalTextBuffer`.
- `src/main/java/local/term/AutoScriptRunner.java` — owns the background thread, matches lines, writes commands, handles timeout.

**New files (test):**
- `src/test/java/local/term/GlobMatcherTest.java`
- `src/test/java/local/term/AutoScriptRunnerTest.java` — contains inline `FakeOutputBuffer` + `FakeTtyConnector` + `FakeSession`.

**Modified files:**
- `src/main/java/local/term/TagNode.java` — add `autoScript` to `Shell`; preserve 5-arg overload so existing call sites compile.
- `src/main/java/local/term/TagConfigStore.java` — read/write `autoScript`; preserve back-compat.
- `src/main/java/local/term/TerminalLauncher.java` — kick off runner before returning `Success`.
- `src/main/java/local/term/ShellEditorDialog.java` — UI section.
- `README.md` — manual integration test steps.

---

## Task 1: GlobMatcher (TDD)

**Files:**
- Create: `src/main/java/local/term/GlobMatcher.java`
- Test: `src/test/java/local/term/GlobMatcherTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/local/term/GlobMatcherTest.java`:

```java
package local.term;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GlobMatcherTest {
  @Test
  void star_matchesPrefixSuffixAndEmpty() {
    GlobMatcher m = GlobMatcher.compile("*foo*");
    assertTrue(m.matches("the foo bar"));
    assertTrue(m.matches("foo"));
    assertFalse(m.matches("bar baz"));
  }

  @Test
  void question_matchesExactlyOneChar_notZero_notMany() {
    GlobMatcher m = GlobMatcher.compile("?og");
    assertTrue(m.matches("dog"));
    assertTrue(m.matches("log"));
    assertFalse(m.matches("og"));        // 0 chars before 'og'
    assertFalse(m.matches("frog"));      // 2 chars before 'og'
  }

  @Test
  void mixedStarAndQuestion_combined() {
    GlobMatcher m = GlobMatcher.compile("h?st*.log");
    assertTrue(m.matches("host01.log"));
    assertTrue(m.matches("hast_segment_42.log"));
    assertFalse(m.matches("hst.log"));   // '?' missing
    assertFalse(m.matches("host"));      // suffix missing
  }

  @Test
  void literalOnly_autoWrappedToContains_matchesAnywhereOnLine() {
    // No '*' or '?' in the user pattern → compiled as '*welcome*'.
    GlobMatcher m = GlobMatcher.compile("welcome");
    assertTrue(m.matches("Welcome to Linux"), "case-sensitive; 'welcome' lowercase does not match 'Welcome' capital W");
    assertTrue(m.matches("hi and welcome friend"));
    assertFalse(m.matches("goodbye"));
  }

  @Test
  void patternAlreadyWithGlob_remainsAnchored_doesNotGetExtraStars() {
    // '*' present → not auto-wrapped; '*Password*' is the literal pattern.
    GlobMatcher m = GlobMatcher.compile("*Password*");
    assertTrue(m.matches("Password:"));
    assertTrue(m.matches("Enter Password for user:"));
    assertFalse(m.matches("pass"));      // anchored: 'Password' must appear
  }

  @Test
  void emptyPattern_autoWrappedToDoubleStar_matchesAnything() {
    // Spec note: callers should treat blank as "skip wait entirely", but we
    // still verify the matcher compiles and is not pathological.
    GlobMatcher m = GlobMatcher.compile("");
    assertTrue(m.matches("anything"));
    assertTrue(m.matches(""));
  }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -Dtest=GlobMatcherTest test -DskipTests=false`
Expected: compilation failure — `GlobMatcher` does not exist (or method `compile` cannot be resolved).

- [ ] **Step 3: Implement GlobMatcher**

Create `src/main/java/local/term/GlobMatcher.java`:

```java
package local.term;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Objects;

/**
 * Glob-style matcher backed by the JDK's {@link PathMatcher}. Pure-literal
 * patterns (no {@code *} or {@code ?}) are auto-wrapped with leading +
 * trailing {@code *} so users get "contains" semantics by default — matches
 * the spec's "只要包含就行" requirement. Patterns that already contain
 * wildcard metacharacters remain anchored (full-line match).
 *
 * <p>JDK glob treats the input as a single path segment; users must avoid
 * literal {@code /} or {@code \} in their patterns (no real terminal line
 * contains those, so this is rarely a concern).
 */
public final class GlobMatcher {
  private final PathMatcher pathMatcher;

  private GlobMatcher(PathMatcher pm) { this.pathMatcher = pm; }

  public static GlobMatcher compile(String pattern) {
    Objects.requireNonNull(pattern, "pattern");
    boolean hasWildcard = pattern.indexOf('*') >= 0 || pattern.indexOf('?') >= 0;
    String glob = hasWildcard ? pattern : "*" + pattern + "*";
    return new GlobMatcher(
        FileSystems.getDefault().getPathMatcher("glob:" + glob));
  }

  public boolean matches(CharSequence line) {
    // Build a one-segment Path whose name is the full line. JDK glob matches
    // the entire path against the pattern.
    return pathMatcher.matches(Path.of(line.toString()));
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -Dtest=GlobMatcherTest test`
Expected: 6 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/local/term/GlobMatcher.java src/test/java/local/term/GlobMatcherTest.java
git commit -m "feat: GlobMatcher — JDK glob + literal auto-wrap for contains semantics"
```

---

## Task 2: Step and AutoScript records

**Files:**
- Create: `src/main/java/local/term/Step.java`
- Create: `src/main/java/local/term/AutoScript.java`
- Test: `src/test/java/local/term/AutoScriptTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/local/term/AutoScriptTest.java`:

```java
package local.term;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AutoScriptTest {
  @Test
  void step_rejectsNullWaitPattern() {
    assertThrows(NullPointerException.class, () -> new Step(null, "cmd"));
  }

  @Test
  void step_rejectsNullCommand() {
    assertThrows(NullPointerException.class, () -> new Step("pat", null));
  }

  @Test
  void step_acceptsEmptyStringsAsNoOp() {
    Step s = new Step("", "");
    assertEquals("", s.waitPattern());
    assertEquals("", s.command());
  }

  @Test
  void autoScript_defaultsConstants() {
    assertEquals(10_000, AutoScript.DEFAULT_TIMEOUT_MS);
    assertEquals(100, AutoScript.STEP_INTERVAL_MS);
  }

  @Test
  void autoScript_nullSteps_isTreatedAsEmpty() {
    AutoScript as = new AutoScript(5000, null);
    assertTrue(as.steps().isEmpty());
  }

  @Test
  void autoScript_stepsListIsImmutable() {
    AutoScript as = new AutoScript(5000,
        List.of(new Step("p", "c")));
    assertThrows(UnsupportedOperationException.class,
        () -> as.steps().add(new Step("x", "y")));
  }

  @Test
  void autoScript_rejectsNonPositiveTimeout() {
    assertThrows(IllegalArgumentException.class,
        () -> new AutoScript(0, List.of()));
    assertThrows(IllegalArgumentException.class,
        () -> new AutoScript(-1, List.of()));
  }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -Dtest=AutoScriptTest test`
Expected: compilation failure — `Step` and `AutoScript` not found.

- [ ] **Step 3: Implement Step**

Create `src/main/java/local/term/Step.java`:

```java
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
```

- [ ] **Step 4: Implement AutoScript**

Create `src/main/java/local/term/AutoScript.java`:

```java
package local.term;

import java.util.List;
import java.util.Objects;

/**
 * An ordered list of {@link Step}s that runs automatically when the shell
 * node is double-clicked. The {@link #timeoutMs} is applied per step (single
 * shell-level setting, not per-step overrideable in v1). Inter-step delay
 * is {@link #STEP_INTERVAL_MS} milliseconds.
 */
public record AutoScript(int timeoutMs, List<Step> steps) {
  /** Default timeout when the user does not change it (10 seconds). */
  public static final int DEFAULT_TIMEOUT_MS = 10_000;

  /** Wait between adjacent steps (100 ms). */
  public static final int STEP_INTERVAL_MS = 100;

  public AutoScript {
    if (timeoutMs <= 0) {
      throw new IllegalArgumentException(
          "timeoutMs must be > 0, got " + timeoutMs);
    }
    Objects.requireNonNull(steps, "steps");
    steps = List.copyOf(steps);
  }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -q -Dtest=AutoScriptTest test`
Expected: 7 tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/local/term/Step.java src/main/java/local/term/AutoScript.java src/test/java/local/term/AutoScriptTest.java
git commit -m "feat: Step + AutoScript records"
```

---

## Task 3: Extend TagNode.Shell with autoScript field

**Files:**
- Modify: `src/main/java/local/term/TagNode.java` (Shell record signature)

This is a mechanical change. No new test (the existing TagNodeTest + downstream tests cover all callers by staying on the 5-arg overload).

- [ ] **Step 1: Add 6-arg constructor + 5-arg overload to Shell record**

In `src/main/java/local/term/TagNode.java`, replace the `record Shell(...)` block:

```java
  /**
   * Launchable leaf node — name plus the shell-process config (binary path,
   * icon, working directory). Always a leaf: {@link #children()} returns
   * the empty list. {@code autoScript} is an optional, nullable auto-run
   * pipeline executed at PTY launch.
   */
  record Shell(UUID id, String name, String shellPath, String iconPath,
               String startPath, AutoScript autoScript) implements TagNode {

    public Shell {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(shellPath, "shellPath");
      Objects.requireNonNull(startPath, "startPath");
      // iconPath and autoScript are intentionally nullable.
    }

    @Override public String type() { return TYPE_SHELL; }
    @Override public boolean isFolder() { return false; }
    @Override public List<TagNode> children() { return List.of(); }

    /** 5-arg overload preserved for existing call sites that don't supply an autoScript. */
    public Shell(UUID id, String name, String shellPath, String iconPath, String startPath) {
      this(id, name, shellPath, iconPath, startPath, null);
    }
  }
```

- [ ] **Step 2: Compile the project (no source changes needed elsewhere)**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS. The 5-arg overload keeps the 20+ existing call sites building unchanged.

- [ ] **Step 3: Run the existing TagNodeTest and broader test suite to confirm no regression**

Run: `mvn -q -Dtest='TagNodeTest,TagTreeModelTest,TagTreePanelTest,DragDropAutoExpandTest,ShellIconResolverTest,TagTreeTransferHandlerFlavorTest,TagConfigStoreTest' test -DskipTests=false`
Expected: all existing tests still pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/local/term/TagNode.java
git commit -m "feat: add nullable autoScript field to TagNode.Shell"
```

---

## Task 4: TagConfigStore persistence (autoScript + back-compat)

**Files:**
- Modify: `src/main/java/local/term/TagConfigStore.java`
- Test: append cases to existing `src/test/java/local/term/TagConfigStoreTest.java`

- [ ] **Step 1: Add failing tests for round-trip and back-compat**

Append to `src/test/java/local/term/TagConfigStoreTest.java` (alongside the other `@org.junit.jupiter.api.Test` methods near the bottom):

```java
  @org.junit.jupiter.api.Test
  void roundTrip_preservesAutoScript() throws Exception {
    java.nio.file.Path tmp = java.nio.file.Files.createTempFile("rt-as", ".json");
    try {
      UUID shellId = new UUID(0L, 99L);
      AutoScript script = new AutoScript(7500, java.util.List.of(
          new Step("login:", "myuser"),
          new Step("Password*", "mypass")));
      TagNode.Shell shell = new TagNode.Shell(shellId, "S", "cmd.exe", null, "C:\\", script);
      TagNode root = new TagNode.Folder(new UUID(0L, 0L), "root",
          java.util.List.of(new TagNode.Folder(new UUID(0L, 1L), "F",
              java.util.List.of(shell))));

      TagConfigStore store = new TagConfigStore(tmp);
      store.save(root);
      TagNode reloaded = store.load();

      TagNode.Shell reloadedShell = (TagNode.Shell) ((TagNode.Folder)
          ((TagNode.Folder) reloaded.children().get(0)).children().get(0));
      assertNotNull(reloadedShell.autoScript(),
          "autoScript must survive round-trip");
      assertEquals(7500, reloadedShell.autoScript().timeoutMs());
      assertEquals(2, reloadedShell.autoScript().steps().size());
      assertEquals("login:", reloadedShell.autoScript().steps().get(0).waitPattern());
      assertEquals("mypass", reloadedShell.autoScript().steps().get(1).command());
    } finally {
      java.nio.file.Files.deleteIfExists(tmp);
    }
  }

  @org.junit.jupiter.api.Test
  void load_v2FileWithoutAutoScriptField_yieldsNull() throws Exception {
    // Existing v2 config files predate this feature. Their shells must load
    // with autoScript=null so nothing auto-runs.
    String json = """
        {
          "version": 2,
          "tags": [
            {
              "type": "shell",
              "id": "00000000-0000-0000-0000-000000000001",
              "name": "old-shell",
              "shellPath": "cmd.exe",
              "iconPath": null,
              "startPath": "C:\\\\",
              "expanded": false,
              "children": []
            }
          ]
        }
        """;
    java.nio.file.Path tmp = java.nio.file.Files.createTempFile("legacy", ".json");
    java.nio.file.Files.writeString(tmp, json);
    try {
      TagNode root = new TagConfigStore(tmp).load();
      TagNode.Shell shell = (TagNode.Shell) root.children().get(0);
      assertNull(shell.autoScript(),
          "missing autoScript in v2 file → null (no regression for old configs)");
    } finally {
      java.nio.file.Files.deleteIfExists(tmp);
    }
  }
```

- [ ] **Step 2: Run the new tests to verify they fail**

Run: `mvn -q -Dtest=TagConfigStoreTest test -DskipTests=false`
Expected: 2 new tests fail — `autoScript` either missing on read or unsupported on write.

- [ ] **Step 3: Extend the DTO + add parse helper in TagConfigStore**

In `src/main/java/local/term/TagConfigStore.java`:

(i) Add a DTO record at the bottom:

```java
  public record AutoScriptDto(int timeoutMs, List<StepDto> steps) {}
  public record StepDto(String waitPattern, String command) {}
```

(ii) Extend the existing `TagNodeDto` with a nullable `autoScript` field:

```java
  public record TagNodeDto(String type, UUID id, String name, String shellPath,
                           String iconPath, String startPath, boolean expanded,
                           List<TagNodeDto> children, AutoScriptDto autoScript) {}
```

(iii) In `parseNode(...)` for the shell branch, after creating the `Shell`, parse `autoScript`:

```java
    if (TagNode.TYPE_SHELL.equalsIgnoreCase(type)) {
      JsonNode asNode = n.path("autoScript");
      AutoScript auto = asNode.isMissingNode() || asNode.isNull()
          ? null
          : new AutoScript(
              asNode.path("timeoutMs").asInt(AutoScript.DEFAULT_TIMEOUT_MS),
              parseSteps(asNode.path("steps")));
      return new TagNode.Shell(id, name, shell, icon, start, auto);
    }
```

And add the helper near `parseNode`:

```java
  private static List<Step> parseSteps(JsonNode stepsNode) {
    if (!stepsNode.isArray()) return List.of();
    List<Step> out = new ArrayList<>();
    for (JsonNode s : stepsNode) {
      out.add(new Step(
          s.path("waitPattern").asText(""),
          s.path("command").asText("")));
    }
    return out;
  }
```

(iv) In `toDto(...)` for the shell branch, include `autoScript`:

```java
  private static TagNodeDto toDto(TagNode c) {
    if (c.type().equals(TagNode.TYPE_SHELL)) {
      TagNode.Shell shell = (TagNode.Shell) c;
      return new TagNodeDto(TagNode.TYPE_SHELL, shell.id(), shell.name(),
          shell.shellPath(), shell.iconPath(), shell.startPath(), false,
          List.of(), toAutoScriptDto(shell.autoScript()));
    }
    // ...existing folder branch unchanged except for the new `null` tail field:
    TagNode.Folder folder = (TagNode.Folder) c;
    List<TagNodeDto> kids = new ArrayList<>();
    for (TagNode k : folder.children()) kids.add(toDto(k));
    return new TagNodeDto(TagNode.TYPE_FOLDER, folder.id(), folder.name(),
        "", null, "", folder.expanded(), kids, null);
  }

  private static AutoScriptDto toAutoScriptDto(AutoScript as) {
    if (as == null) return null;
    List<StepDto> stepDtos = new ArrayList<>();
    for (Step s : as.steps()) {
      stepDtos.add(new StepDto(s.waitPattern(), s.command()));
    }
    return new AutoScriptDto(as.timeoutMs(), stepDtos);
  }
```

(Note the `ObjectMapper` already has `@JsonInclude(NON_NULL)` style configured at the top — `null` is omitted on disk, ensuring existing config files keep their exact shape.)

- [ ] **Step 4: Run all TagConfigStoreTest tests to verify**

Run: `mvn -q -Dtest=TagConfigStoreTest test`
Expected: all tests pass, including the existing `save_thenLoad_roundTripPreservesTreeExactly` (now uses the 5-arg overload).

- [ ] **Step 5: Run the full test suite to ensure no regression**

Run: `mvn -q test -DskipTests=false`
Expected: full suite green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/local/term/TagConfigStore.java src/test/java/local/term/TagConfigStoreTest.java
git commit -m "feat: persist autoScript in TagConfigStore (back-compat for v2 files)"
```

---

## Task 5: OutputBuffer interface + JediOutputBuffer adapter

**Files:**
- Create: `src/main/java/local/term/OutputBuffer.java`
- Create: `src/main/java/local/term/JediOutputBuffer.java`

Small, no new tests yet — coverage comes from AutoScriptRunnerTest (Task 6).

- [ ] **Step 1: Define OutputBuffer**

Create `src/main/java/local/term/OutputBuffer.java`:

```java
package local.term;

import java.util.List;

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
```

- [ ] **Step 2: Implement JediOutputBuffer**

Create `src/main/java/local/term/JediOutputBuffer.java`:

```java
package local.term;

import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.model.TextBufferChangesListener;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * {@link OutputBuffer} backed by a JediTerm {@link TerminalTextBuffer}.
 * Wraps each registered {@link OutputBuffer.Listener} in a JDK listener and
 * keeps the wrappers in a set so {@link #removeListener} can find and
 * unregister the right one.
 */
public final class JediOutputBuffer implements OutputBuffer {

  private final TerminalTextBuffer buffer;
  private final Set<JediListener> wrappers = new HashSet<>();

  public JediOutputBuffer(TerminalTextBuffer buffer) {
    this.buffer = Objects.requireNonNull(buffer, "buffer");
  }

  @Override
  public void addListener(Listener listener) {
    Objects.requireNonNull(listener, "listener");
    JediListener w = new JediListener(listener);
    synchronized (wrappers) {
      wrappers.add(w);
    }
    buffer.addChangesListener(w);
  }

  @Override
  public void removeListener(Listener listener) {
    Objects.requireNonNull(listener, "listener");
    JediListener target = null;
    synchronized (wrappers) {
      for (JediListener w : wrappers) {
        if (w.user == listener) { target = w; break; }
      }
      if (target != null) wrappers.remove(target);
    }
    if (target != null) buffer.removeChangesListener(target);
  }

  @Override
  public int getLineCount() {
    return buffer.getScreenLinesCount();
  }

  @Override
  public String getLineText(int index) {
    // JediTerm throws if the index is out of bounds — let it bubble up. The
    // runner guards its cursor against the current getLineCount() before
    // calling this.
    return buffer.getLine(index).getText();
  }

  private static final class JediListener implements TextBufferChangesListener {
    final Listener user;
    JediListener(Listener user) { this.user = user; }
    @Override public void linesChanged(int count) { user.linesChanged(count); }
  }
}
```

- [ ] **Step 3: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/local/term/OutputBuffer.java src/main/java/local/term/JediOutputBuffer.java
git commit -m "feat: OutputBuffer interface + JediTerm adapter"
```

---

## Task 6: AutoScriptRunner (TDD with fakes)

**Files:**
- Create: `src/main/java/local/term/AutoScriptRunner.java`
- Test: `src/test/java/local/term/AutoScriptRunnerTest.java`

The runner is the heart of this feature. Tests use a `FakeOutputBuffer` (lines pushed manually) and a `FakeTtyConnector` (records `write()` calls). `AutoScript.STEP_INTERVAL_MS` is reduced via a `@VisibleForTesting`-style package-private constant accessor — or, simpler, by overriding via reflection-style helper. **Simplest path: keep `STEP_INTERVAL_MS` non-final and expose a static `@VisibleForTesting`-ish setter that the test resets in `@AfterEach`.** Even simpler still: provide a package-private constructor `AutoScriptRunner(session, script, buffer, intervalMs, clock)` for tests, and a 4-arg public delegate. We use the second approach.

- [ ] **Step 1: Write the failing test scaffold**

Create `src/test/java/local/term/AutoScriptRunnerTest.java`:

```java
package local.term;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AutoScriptRunnerTest {

  private static final int INTERVAL_MS_TEST = 20; // tight loop for tests; production is 100

  // Tracks writes the runner issues to the (fake) connector. Captures the raw
  // bytes so we can verify line endings + non-ASCII bytes are preserved.
  static final class FakeConnector {
    final List<byte[]> writes = new ArrayList<>();
    boolean closed = false;
    void write(byte[] bytes) { writes.add(bytes); }
    void close() { closed = true; }
  }

  // Manually driven. Test code calls appendLine(...) to simulate terminal
  // output and notify() to fire linesChanged(n) to listeners.
  static final class FakeBuffer implements OutputBuffer {
    final List<String> lines = new ArrayList<>();
    final List<Listener> listeners = new ArrayList<>();
    void appendLine(String text) { lines.add(text); }
    void notify(int added) { for (Listener l : listeners) l.linesChanged(added); }
    @Override public void addListener(Listener l) { listeners.add(l); }
    @Override public void removeListener(Listener l) { listeners.remove(l); }
    @Override public int getLineCount() { return lines.size(); }
    @Override public String getLineText(int index) { return lines.get(index); }
  }

  // Very thin fake of the bits of TerminalSession the runner uses. Tests for
  // the runner never exercise JediTerm — they validate control flow only.
  static final class FakeSession {
    final FakeConnector connector = new FakeConnector();
    final FakeBuffer buffer = new FakeBuffer();
    final AtomicInteger closeCount = new AtomicInteger();
    void close() { closeCount.incrementAndGet(); }
    boolean isClosed() { return connector.closed; }
    void write(byte[] bytes) { connector.write(bytes); }
  }

  private FakeSession session;
  private FakeBuffer buffer;

  @BeforeEach void setUp() { session = new FakeSession(); buffer = session.buffer; }
  @AfterEach  void tearDown() { /* nothing — each test owns its session+runner */ }

  /** Run the runner on the supplied script and return it. */
  private AutoScriptRunner runner(AutoScript script) {
    AutoScriptRunner r = new AutoScriptRunner(asSession(session), script,
        session.buffer, INTERVAL_MS_TEST);
    r.start();
    return r;
  }

  /** Build a no-op TerminalSession-like adapter by reflection-free subclass. */
  private static TerminalSession asSession(FakeSession fs) {
    // Return a TerminalSession whose widget/connector are unused in tests;
    // the runner only calls close()+isClosed() on it. We construct one
    // using a tiny adapter so we don't touch JediTerm at all in this test
    // class. Implementation lives in AutoScriptRunner (next task); this
    // helper just wraps the contract.
    return TestSessionAdapter.wrap(fs);
  }

  /** Wait until predicate is true or timeout (ms) expires; returns success. */
  private static boolean await(Condition c, long timeoutMs) {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      if (c.test()) return true;
      try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }
    return c.test();
  }
  interface Condition { boolean test(); }
}
```

Create the supporting adapter in a new file (to be added in Step 3): `src/test/java/local/term/TestSessionAdapter.java`:

```java
package local.term;

import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.ui.JediTermWidget;

/** A test-only TerminalSession that delegates close/isClosed/write to a FakeSession. */
final class TestSessionAdapter extends TerminalSession {
  private final AutoScriptRunnerTest.FakeSession fake;
  private TestSessionAdapter(AutoScriptRunnerTest.FakeSession fake, TtyConnector conn, JediTermWidget w) {
    super(w, conn, () -> {});
    this.fake = fake;
  }
  static TerminalSession wrap(AutoScriptRunnerTest.FakeSession fake) {
    // Provide a stub connector + widget that AutoScriptRunner won't touch.
    TtyConnector conn = new TtyConnector() {
      @Override public void write(byte[] bytes) { fake.write(bytes); }
      @Override public void write(String s) { write(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
      @Override public int read(byte[] buf, int off, int len) { return 0; }
      @Override public boolean isConnected() { return !fake.isClosed(); }
      @Override public String getName() { return "fake"; }
      @Override public void close() { fake.connector.close(); }
      @Override public void resize(com.jediterm.core.util.TermSize size) {}
    };
    // JediTermWidget is non-trivial to construct; use null and rely on the
    // runner never touching widget() in tests. Caller must only use this
    // adapter when the runner avoids session.getWidget().
    return new TestSessionAdapter(fake, conn, /* widget = */ null) {
      // Hide the cast: the parent constructor requires non-null widget only
      // conceptually. AutoScriptRunner must NOT call getWidget() on a
      // production-wired runner until we know it's safe — we enforce that
      // by passing the OutputBuffer separately.
    };
  }
  @Override public void close() { fake.close(); }
  @Override public boolean isClosed() { return fake.isClosed(); }
}
```

The above introduces a problem: `JediTermWidget` is non-null in the parent constructor. Resolve by NOT using a `TerminalSession` subclass for tests; instead, **let `AutoScriptRunner` accept its dependencies explicitly** (the API splits `session: TerminalSession` for `close()/write()` from `buffer: OutputBuffer` for line reading). The test wires the buffer as `FakeBuffer` and writes through the connector via a `FakeConnector`-backed `TerminalSession` constructed with a real but minimal `JediTermWidget` (which is heavy) — or, more simply, **refactor AutoScriptRunner to take a small `Sink` interface instead of `TerminalSession`**.

This is a real choice point — I'll resolve it in Step 3 below by changing the runner API to accept its collaborators as small interfaces.

- [ ] **Step 2: Decide runner API surface**

Replace the design's "AutoScriptRunner takes TerminalSession" with the following interfaces (production-grade, no JediTerm in the runner path):

```java
// In AutoScriptRunner.java
public interface Sink {
  /** Send bytes to the PTY — typically append "\n" before calling. */
  void write(byte[] bytes) throws java.io.IOException;
  /** Abort the session — close the tab. */
  void close();
  /** Has {@link #close()} already been invoked? */
  boolean isClosed();
}
```

Production wiring (in `TerminalLauncher`, Task 7):

```java
Sink sink = new SessionSink(session);
```

Where `SessionSink` adapts `TerminalSession` to `Sink`.

**Re-write the test (no `TerminalSession` subclass, no `JediTermWidget`):**

```java
package local.term;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AutoScriptRunnerTest {

  static final class FakeSink implements AutoScriptRunner.Sink {
    final List<byte[]> writes = new ArrayList<>();
    final AtomicInteger closeCount = new AtomicInteger();
    @Override public void write(byte[] bytes) { writes.add(bytes); }
    @Override public void close() { closeCount.incrementAndGet(); }
    @Override public boolean isClosed() { return closeCount.get() > 0; }
  }

  static final class FakeBuffer implements OutputBuffer {
    final List<String> lines = new ArrayList<>();
    final List<Listener> listeners = new ArrayList<>();
    void appendLine(String text) {
      lines.add(text);
      for (Listener l : listeners) l.linesChanged(1);
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
  @AfterEach  void tearDown() { }

  private AutoScriptRunner runner(AutoScript script) {
    AutoScriptRunner r = new AutoScriptRunner(script, sink, buffer, TEST_INTERVAL_MS);
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
    await(150);  // let script finish + close listener
    assertEquals(1, sink.writes.size());
    assertEquals("myuser", new String(sink.writes.get(0)));
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
    assertEquals(List.of("myuser", "mypass", "ls"),
        sink.writes.stream().map(String::new).toList());
    assertEquals(0, sink.closeCount.get());
  }

  @Test
  void run_emptyWaitPattern_sendsImmediatelyAfterInterval() {
    AutoScript s = new AutoScript(1000, List.of(
        new Step("", "echo hi")));
    AutoScriptRunner r = runner(s);
    await(60);  // ≥ TEST_INTERVAL_MS (20) gives time for step 0
    assertEquals(1, sink.writes.size());
    assertEquals("echo hi", new String(sink.writes.get(0)));
  }

  @Test
  void run_timeoutAtStepK_closesSinkAndStops() {
    AutoScript s = new AutoScript(150, List.of(
        new Step("never:", "x")));
    AutoScriptRunner r = runner(s);
    await(400);
    assertEquals(1, sink.closeCount.get());
    assertTrue(sink.writes.isEmpty());
  }

  @Test
  void run_existingOutputAtStart_isInScope() {
    // Pre-populate the buffer BEFORE start. The cursor at run() captures
    // pre-existing lines, so the wait sees them.
    buffer.appendLine("login:");
    AutoScript s = new AutoScript(500, List.of(
        new Step("login:", "myuser")));
    AutoScriptRunner r = runner(s);
    await(150);
    assertEquals(1, sink.writes.size());
    assertEquals("myuser", new String(sink.writes.get(0)));
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
    assertEquals("next", new String(sink.writes.get(1)));
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
    assertEquals("myuser\n", new String(w), "runner must append \\n to each command");
  }
}
```

- [ ] **Step 3: Run tests to verify they fail (compile error)**

Run: `mvn -q -Dtest=AutoScriptRunnerTest test -DskipTests=false`
Expected: compile error — `AutoScriptRunner` not defined.

- [ ] **Step 4: Implement AutoScriptRunner**

Create `src/main/java/local/term/AutoScriptRunner.java`:

```java
package local.term;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs an {@link AutoScript} against a live terminal session: each
 * {@link Step} blocks until a line since script start matches its
 * {@code waitPattern} (or the script's {@code timeoutMs} elapses), then
 * writes its {@code command} to the {@link Sink} and pauses for
 * {@code intervalMs} before the next step.
 *
 * <p>On timeout the runner closes the sink — by contract that removes the
 * associated tab in {@link TerminalPanel}.
 */
public class AutoScriptRunner {

  private static final Logger LOG = LoggerFactory.getLogger(AutoScriptRunner.class);

  /** Adapter surface the runner needs from a {@link TerminalSession}. */
  public interface Sink {
    void write(byte[] bytes) throws IOException;
    void close();
    boolean isClosed();
  }

  private final AutoScript script;
  private final Sink sink;
  private final OutputBuffer buffer;
  private final int intervalMs;

  private final AtomicInteger cursor = new AtomicInteger(0);
  private final AtomicBoolean done = new AtomicBoolean(false);
  private volatile Thread runnerThread;

  public AutoScriptRunner(AutoScript script, Sink sink, OutputBuffer buffer) {
    this(script, sink, buffer, AutoScript.STEP_INTERVAL_MS);
  }

  /** Test-friendly constructor that lets a unit test inject a tiny interval. */
  AutoScriptRunner(AutoScript script, Sink sink, OutputBuffer buffer, int intervalMs) {
    this.script = Objects.requireNonNull(script, "script");
    this.sink = Objects.requireNonNull(sink, "sink");
    this.buffer = Objects.requireNonNull(buffer, "buffer");
    this.intervalMs = intervalMs;
  }

  public void start() {
    if (runnerThread != null) return;
    runnerThread = new Thread(this::run, "AutoScript-" + System.nanoTime());
    runnerThread.setDaemon(true);
    runnerThread.start();
  }

  private void run() {
    // Snapshot the buffer's line count now — pre-existing output from the
    // shell banner is fair game as a match target.
    cursor.set(buffer.getLineCount());

    OutputBuffer.Listener listener = n -> tryMatch();
    buffer.addListener(listener);

    ScheduledExecutorService timer =
        Executors.newSingleThreadScheduledExecutor(r -> {
          Thread t = new Thread(r, "AutoScript-timer");
          t.setDaemon(true);
          return t;
        });

    try {
      for (Step step : script.steps()) {
        if (sink.isClosed() || done.get()) return;
        if (step.waitPattern().isBlank()) {
          // Pure delay step (or pure "send now" with no wait). Still observe
          // the inter-step interval before sending.
          sleepInterruptibly(intervalMs);
          send(step.command());
          continue;
        }
        GlobMatcher matcher = GlobMatcher.compile(step.waitPattern());
        CountDownLatch tick = new CountDownLatch(1);
        int[] matched = { -1 };
        // Re-check first in case the line we want already exists at cursor.
        int found = scanForMatch(cursor.get(), matcher);
        if (found >= 0) {
          tick.countDown();
          matched[0] = found;
        } else {
          // Listener on the buffer; if a new line lands matching, signal.
          // We swap the listener for a one-shot scoped to this step.
          OutputBuffer.Listener stepListener = n -> {
            int idx = scanForMatch(cursor.get(), matcher);
            if (idx >= 0) {
              matched[0] = idx;
              tick.countDown();
            }
          };
          buffer.addListener(stepListener);
          ScheduledFuture<?> to = timer.schedule(
              tick::countDown, script.timeoutMs(), TimeUnit.MILLISECONDS);
          try {
            tick.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
          } finally {
            to.cancel(false);
            buffer.removeListener(stepListener);
          }
          if (matched[0] < 0) {
            // Timed out.
            LOG.error("AutoScript timed out at step with pattern '{}' " +
                "after {}ms — closing session", step.waitPattern(),
                script.timeoutMs());
            sink.close();
            return;
          }
          cursor.set(matched[0] + 1);
        }
        send(step.command());
        if (step != last(script.steps())) sleepInterruptibly(intervalMs);
      }
    } finally {
      buffer.removeListener(listener);
      timer.shutdownNow();
      done.set(true);
    }
  }

  private int scanForMatch(int fromCursor, GlobMatcher matcher) {
    int end = buffer.getLineCount();
    for (int i = fromCursor; i < end; i++) {
      if (matcher.matches(buffer.getLineText(i))) return i;
    }
    return -1;
  }

  private void send(String command) {
    if (sink.isClosed()) return;
    try {
      sink.write((command + "\n").getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      LOG.warn("AutoScript write failed: {}", e.getMessage());
      sink.close();
    }
  }

  private static <T> T last(java.util.List<T> list) {
    return list.get(list.size() - 1);
  }

  private static void sleepInterruptibly(int ms) {
    try { Thread.sleep(ms); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
  }

  /** Polled by the buffer listener; re-checked each call. */
  private void tryMatch() { /* handled per-step via stepListener */ }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -q -Dtest=AutoScriptRunnerTest test`
Expected: 9 tests pass.

- [ ] **Step 6: Re-run the full suite**

Run: `mvn -q test -DskipTests=false`
Expected: full suite green; no regression to GlobMatcher/AutoScript/TagConfigStore tests.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/local/term/AutoScriptRunner.java src/test/java/local/term/AutoScriptRunnerTest.java
git commit -m "feat: AutoScriptRunner with cursor-based matching + timeout abort"
```

---

## Task 7: Wire AutoScriptRunner into TerminalLauncher

**Files:**
- Modify: `src/main/java/local/term/TerminalLauncher.java`

- [ ] **Step 1: Add the kick-off block**

In `src/main/java/local/term/TerminalLauncher.java`, after the `TerminalSession` is fully constructed (right before `return new LaunchResult.Success(session);`):

```java
      if (shell.autoScript() != null) {
        com.jediterm.terminal.ui.JediTermWidget finalWidget = widget;
        AutoScriptRunner.Sink sink = new AutoScriptRunner.Sink() {
          @Override public void write(byte[] bytes) throws java.io.IOException {
            connector.write(bytes);
          }
          @Override public void close() { session.close(); }
          @Override public boolean isClosed() { return session.isClosed(); }
        };
        OutputBuffer outBuf = new JediOutputBuffer(finalWidget.getTerminalTextBuffer());
        new AutoScriptRunner(shell.autoScript(), sink, outBuf).start();
      }
```

(Place the import for `AutoScriptRunner` and `OutputBuffer` at the top with the existing imports.)

- [ ] **Step 2: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Run TerminalLauncherTest to confirm nothing regressed**

Run: `mvn -q -Dtest=TerminalLauncherTest test`
Expected: all current tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/local/term/TerminalLauncher.java
git commit -m "feat: TerminalLauncher kicks off AutoScriptRunner when configured"
```

---

## Task 8: ShellEditorDialog — autoScript UI section

**Files:**
- Modify: `src/main/java/local/term/ShellEditorDialog.java`

No new automated test (Swing UI rendering has no harness; manual verification follows in Task 9).

- [ ] **Step 1: Replace the file with the extended version**

Replace `src/main/java/local/term/ShellEditorDialog.java` with:

```java
package local.term;

import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Modal dialog for adding or editing a {@link TagNode.Shell}.
 *
 * <p>Carries name + shellPath (+ optional iconPath) + startPath +
 * optional {@link AutoScript}. Edit preserves the shell's id; on a
 * successful OK the caller drops the returned Shell back into the tree
 * at that id.
 */
public class ShellEditorDialog extends JDialog {
  private final JTextField nameField = new JTextField(30);
  private final JTextField shellField = new JTextField(30);
  private final JTextField iconField = new JTextField(30);
  private final JTextField startField = new JTextField(30);

  // Auto Script section
  private final JCheckBox autoScriptEnabled = new JCheckBox("Enable auto script");
  private final JSpinner timeoutSpinner = new JSpinner(new SpinnerNumberModel(
      AutoScript.DEFAULT_TIMEOUT_MS, 1000, 60_000, 1000));
  private final DefaultTableModel stepsModel = new DefaultTableModel(
      new Object[]{"Wait pattern", "Command"}, 0) {
    @Override public boolean isCellEditable(int row, int col) { return true; }
  };
  private final JTable stepsTable = new JTable(stepsModel);

  private final JLabel errorLabel = new JLabel(" ");

  private final boolean isEdit;
  private final UUID id;
  private TagNode.Shell result;

  public ShellEditorDialog(JFrame owner, TagNode.Shell existing) {
    super(owner, true);
    this.isEdit = existing != null;
    this.id = existing != null ? existing.id() : UUID.randomUUID();
    setTitle(isEdit ? "Edit shell" : "Add shell");
    buildUi();
    if (isEdit) populate(existing);
    pack();
    setLocationRelativeTo(owner);
  }

  public TagNode.Shell getResult() { return result; }

  private void buildUi() {
    JPanel form = new JPanel(new GridBagLayout());
    form.setBorder(new EmptyBorder(12, 12, 12, 12));
    GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(4, 4, 4, 4);
    c.anchor = GridBagConstraints.WEST;
    c.fill = GridBagConstraints.HORIZONTAL;

    int row = 0;
    addRow(form, c, row++, "Name *", nameField, null);
    addRow(form, c, row++, "Shell path *", shellField,
        () -> pickFile("Select shell", shellField));
    addRow(form, c, row++, "Icon path", iconField,
        () -> pickFile("Select icon", iconField));
    addRow(form, c, row++, "Start path *", startField,
        () -> pickDirectory("Select start directory", startField));

    // Auto Script section.
    c.gridx = 0; c.gridy = row++; c.gridwidth = 3; c.weightx = 1;
    JPanel autoPanel = buildAutoScriptPanel();
    form.add(autoPanel, c);

    c.gridx = 0; c.gridy = row++; c.gridwidth = 3;
    errorLabel.setForeground(java.awt.Color.RED);
    form.add(errorLabel, c);

    JButton ok = new JButton(isEdit ? "Save" : "Add");
    JButton cancel = new JButton("Cancel");
    ok.addActionListener(e -> onOk());
    cancel.addActionListener(e -> { result = null; dispose(); });
    JPanel buttons = new JPanel();
    buttons.add(ok); buttons.add(cancel);

    add(form, BorderLayout.CENTER);
    add(buttons, BorderLayout.SOUTH);
    getRootPane().setDefaultButton(ok);
  }

  private JPanel buildAutoScriptPanel() {
    JPanel panel = new JPanel(new BorderLayout(4, 4));
    panel.setBorder(new TitledBorder("Auto Script (optional)"));

    // Enable checkbox at the top.
    JPanel north = new JPanel(new BorderLayout());
    north.add(autoScriptEnabled, BorderLayout.WEST);
    autoScriptEnabled.addActionListener(e -> syncAutoScriptEnabled());
    panel.add(north, BorderLayout.NORTH);

    // Center: timeout + table.
    JPanel center = new JPanel(new BorderLayout(4, 4));
    JPanel timeoutRow = new JPanel(new BorderLayout(4, 4));
    timeoutRow.add(new JLabel("Timeout (ms) per step:"), BorderLayout.WEST);
    timeoutRow.add(timeoutSpinner, BorderLayout.CENTER);
    center.add(timeoutRow, BorderLayout.NORTH);

    stepsTable.setDefaultEditor(Object.class, new DefaultCellEditor(new JTextField()));
    stepsTable.getColumnModel().getColumn(0).setPreferredWidth(200);
    stepsTable.getColumnModel().getColumn(1).setPreferredWidth(200);
    center.add(new JScrollPane(stepsTable), BorderLayout.CENTER);

    // South: row-mutation buttons.
    JPanel controls = new JPanel();
    JButton add = new JButton("Add row");
    JButton del = new JButton("Delete selected");
    JButton up = new JButton("Move up");
    JButton down = new JButton("Move down");
    add.addActionListener(e -> stepsModel.addRow(new Object[]{"", ""}));
    del.addActionListener(e -> {
      int row = stepsTable.getSelectedRow();
      if (row >= 0) stepsModel.removeRow(row);
    });
    up.addActionListener(e -> moveRow(-1));
    down.addActionListener(e -> moveRow(+1));
    controls.add(add); controls.add(del); controls.add(up); controls.add(down);
    center.add(controls, BorderLayout.SOUTH);
    panel.add(center, BorderLayout.CENTER);

    syncAutoScriptEnabled();
    return panel;
  }

  private void moveRow(int delta) {
    int row = stepsTable.getSelectedRow();
    if (row < 0) return;
    int target = row + delta;
    if (target < 0 || target >= stepsModel.getRowCount()) return;
    Object[] rowData = new Object[stepsModel.getColumnCount()];
    for (int c = 0; c < rowData.length; c++) rowData[c] = stepsModel.getValueAt(row, c);
    stepsModel.removeRow(row);
    stepsModel.insertRow(target, rowData);
    stepsTable.setRowSelectionInterval(target, target);
  }

  private void syncAutoScriptEnabled() {
    boolean enabled = autoScriptEnabled.isSelected();
    timeoutSpinner.setEnabled(enabled);
    stepsTable.setEnabled(enabled);
    // The button bar lives inside center; toggle it too.
    for (java.awt.Component comp : ((JPanel) stepsTable.getParent().getParent()).getComponents()) {
      comp.setEnabled(enabled);
    }
  }

  private void addRow(JPanel form, GridBagConstraints c, int row, String label,
                      JTextField field, Runnable picker) {
    c.gridx = 0; c.gridy = row; c.gridwidth = 1; c.weightx = 0;
    form.add(new JLabel(label), c);
    c.gridx = 1; c.weightx = 1;
    form.add(field, c);
    if (picker != null) {
      JButton browse = new JButton("...");
      browse.addActionListener(e -> picker.run());
      c.gridx = 2; c.weightx = 0;
      form.add(browse, c);
    }
  }

  private void pickFile(String title, JTextField target) {
    JFileChooser fc = new JFileChooser();
    fc.setDialogTitle(title);
    if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
      File f = fc.getSelectedFile();
      target.setText(f.getAbsolutePath());
    }
  }

  private void pickDirectory(String title, JTextField target) {
    JFileChooser fc = new JFileChooser();
    fc.setDialogTitle(title);
    fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
    if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
      File f = fc.getSelectedFile();
      target.setText(f.getAbsolutePath());
    }
  }

  private void populate(TagNode.Shell s) {
    nameField.setText(s.name());
    shellField.setText(s.shellPath());
    if (s.iconPath() != null) iconField.setText(s.iconPath());
    startField.setText(s.startPath());
    if (s.autoScript() != null) {
      autoScriptEnabled.setSelected(true);
      timeoutSpinner.setValue(s.autoScript().timeoutMs());
      stepsModel.setRowCount(0);
      for (Step step : s.autoScript().steps()) {
        stepsModel.addRow(new Object[]{step.waitPattern(), step.command()});
      }
    }
    syncAutoScriptEnabled();
  }

  private void onOk() {
    String name = nameField.getText().trim();
    String shell = shellField.getText().trim();
    String icon = iconField.getText().trim();
    String start = startField.getText().trim();

    if (name.isEmpty()) { errorLabel.setText("Name is required"); return; }
    if (shell.isEmpty()) { errorLabel.setText("Shell path is required"); return; }
    if (start.isEmpty()) { errorLabel.setText("Start path is required"); return; }

    AutoScript autoScript = null;
    if (autoScriptEnabled.isSelected()) {
      int timeout = (Integer) timeoutSpinner.getValue();
      List<Step> steps = new ArrayList<>();
      for (int i = 0; i < stepsModel.getRowCount(); i++) {
        String wp = (String) stepsModel.getValueAt(i, 0);
        String cmd = (String) stepsModel.getValueAt(i, 1);
        if (wp == null) wp = "";
        if (cmd == null) cmd = "";
        steps.add(new Step(wp, cmd));
      }
      if (steps.isEmpty()) {
        errorLabel.setText("Add at least one auto-script step");
        return;
      }
      try {
        autoScript = new AutoScript(timeout, steps);
      } catch (IllegalArgumentException e) {
        errorLabel.setText("Invalid timeout: " + e.getMessage());
        return;
      }
    }

    result = new TagNode.Shell(id, name, shell,
        icon.isEmpty() ? null : icon, start, autoScript);
    dispose();
  }
}
```

- [ ] **Step 2: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Run full unit suite**

Run: `mvn -q test -DskipTests=false`
Expected: full suite green.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/local/term/ShellEditorDialog.java
git commit -m "feat: ShellEditorDialog gains Auto Script section (enable, timeout, steps table)"
```

---

## Task 9: README manual integration test

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Append a new section after the existing "Manual integration test" section**

Append to `README.md` (after the existing numbered integration test):

```markdown

## Manual integration test — auto-script

These verify the optional auto-script feature on shell nodes.

1. Add a shell node with `shellPath = C:\Windows\System32\cmd.exe`,
   enable auto-script, and add exactly one step:
   - Wait pattern: `Microsoft Windows`
   - Command: `echo hello from auto-script`
   Click Add/Save.
2. Double-click the new node → a terminal tab opens. Within ~1 second of
   the `Microsoft Windows ...` banner being printed, the line
   `hello from auto-script` is sent. The tab stays open.
3. Edit the same shell and change the wait pattern to a string that will
   **never** appear (`never:`). Save. Double-click the shell again → after
   ~10 seconds the tab closes itself. The log file
   (`<user home>\.local-term-java\config.json` directory) shows an
   `ERROR ... AutoScript timed out` line.
4. Restart the application → the saved auto-script survives (same timeout,
   same steps). Double-click to re-run.
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: manual integration test for shell auto-script"
```

---

## Final verification

Run the full suite once more:

```bash
mvn -q test -DskipTests=false
```

All existing tests + the new `GlobMatcherTest`, `AutoScriptTest`, two new `TagConfigStoreTest` cases, and `AutoScriptRunnerTest` should be green.

---

## Spec self-review (done while drafting)

**1. Spec coverage:**
- Section 4.3 (records) → Task 2 ✓
- Section 4.2 (Shell extension) → Task 3 ✓
- Section 4.1, 5 (JSON schema + back-compat) → Task 4 ✓
- Section 4.4, 4.5, 4.6 (run loop) → Task 6 ✓
- Section 4.7 (GlobMatcher) → Task 1 ✓
- Section 6 (UI section) → Task 8 ✓
- Spec's section 3 TerminalLauncher kick-off → Task 7 ✓
- Spec's section 7.2 manual integration test → Task 9 ✓

**2. Placeholder scan:** None. Each step shows full code.

**3. Type consistency:**
- `Step` and `AutoScript` defined in Task 2, used in Task 4 + Task 6 + Task 8 ✓
- `OutputBuffer` and `JediOutputBuffer` defined in Task 5, used in Task 6 + Task 7 ✓
- `AutoScriptRunner.Sink` defined in Task 6, used in Task 7 ✓
- `AutoScript.STEP_INTERVAL_MS` defined in Task 2, used in Task 6 default constructor ✓
- `JediTermWidget finalWidget` capture in Task 7 — capture is required because
  Java's definite-assignment rules require `widget` be effectively final, but the
  `catch` block reassigns it; we re-introduce a final local to satisfy the lambda.
- FakeBuffer's `appendLine` calls `linesChanged(1)` directly — the runner test
  cases that simulate pre-existing output must call `appendLine` BEFORE
  constructing the runner (matches test 6 in Task 6).

**4. Possible edge:** the `tryMatch` method in `AutoScriptRunner` is empty — it's
a vestige of the design loop. Either remove it or document. The per-step
listener is the active path; `tryMatch` is harmless but unused. **Implementation
note:** remove the `tryMatch` method and the `listener` registration that goes
with it — the per-step listener is the only listener. (Tweak in Task 6 Step 4
during implementation.)

**5. Spec deviation recorded:** The runner takes a `Sink` adapter (small
interface) instead of `TerminalSession` directly, so the test does not depend
on JediTerm widget/connector plumbing. The `TerminalLauncher` wires a `Sink`
that delegates to `session.close()`, `connector.write(...)`, `session.isClosed()`.
This deviates from the spec's `depends on: TerminalSession` line but is a
strict refinement — the runner still consumes a live session via the adapter.
