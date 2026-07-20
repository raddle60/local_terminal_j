# Shell Node Auto-Script — Design Spec

**Date:** 2026-07-17
**Status:** Approved (brainstorming phase complete)
**Target:** `D:\eclipse-workspace\local_term_java`

## 1. Purpose

Extend the existing `TagNode.Shell` leaf node with an optional **auto-script**: an
ordered list of `(waitPattern, command)` pairs that are executed automatically the
moment the user double-clicks a shell node and the underlying PTY starts. Each
pair blocks until a line matching `waitPattern` appears in the terminal output
(glob-style wildcards: `*` and `?`), sends `command` when matched, and yields
100 ms before the next pair begins. If any step's wait times out, the entire
script is aborted and the terminal tab is closed.

This enables one-click terminal automation such as auto-login sequences, env
setup, or remote-host commands — without writing a separate script file.

## 2. Constraints & Decisions

| Decision | Value | Rationale |
|---|---|---|
| Trigger | At PTY launch, before the session is handed to the UI | Matches the "auto-run on open" use case |
| Wait-pattern syntax | Glob-style: `*` (any sequence) and `?` (single char) | Familiar to Windows users; available via JDK `getPathMatcher("glob:...")` |
| Match scope | Line-based, substring containment — pure-literal patterns are auto-wrapped with leading/trailing `*` at compile time so users get "contains" semantics for free, while patterns containing `*` or `?` remain anchored (full-line match) | "只要包含就行" — pattern found anywhere on the line matches |
| Match semantics | Monotonic cursor — each step consumes up to and including its matched line, next step scans beyond | Supports matching shell prompts that re-appear, and matching output that the script itself echoes |
| Timeout default | 10 000 ms (10 s), configurable per-shell | User-specified default |
| Per-step timeout override | None — single shell-level value | YAGNI; can add later without breaking schema |
| Inter-step interval | 100 ms fixed | Fixed by user spec; not configurable per step in v1 |
| Empty wait pattern | Step fires immediately (after the 100 ms inter-step interval) | Allows "pure delay" steps |
| Timeout behavior | Abort entire script, close session, log ERROR | User-selected (B) — strict |
| Backward compatibility | Existing config files load unchanged; absent `autoScript` field behaves like `null` | No forced migration |

## 3. Architecture

```
+--------------------------------------------------------+
| TagTreePanel (LEFT) | TerminalPanel (RIGHT)           |
|                     |  +-----------+                  |
| > Dev               |  |  Tab: cmd |                  |
|   * backend (leaf)  |  |   ▾ JediTermWidget          |
|     + auto-script ──┼──┼─▶ AutoScriptRunner          |
|                     |  |      ▲                      |
|                     |  |      │ reads                |
|                     |  |      ▼                      |
|                     |  |   TerminalTextBuffer         |
|                     |  +-----------+                  |
+--------------------------------------------------------+
```

Three units, each independently understandable:

| # | Component | Purpose | Key dependencies |
|---|---|---|---|
| 1 | `AutoScript` (record) | Value object: `timeoutMs` + ordered `List<Step>` | — |
| 2 | `Step` (record, top-level) | Value object: `waitPattern` + `command` | — |
| 3 | `GlobMatcher` (utility) | `static GlobMatcher compile(String)`; instance method `boolean matches(CharSequence)` | JDK `FileSystems.getDefault().getPathMatcher("glob:...")` |
| 4 | `AutoScriptRunner` | Runs the script: spawns a headless thread that loops over steps, watches the buffer via a `TextBufferChangesListener`, sends commands via `TtyConnector.write(...)`, and aborts (closes session) on timeout | `TerminalSession`, JediTerm `TerminalTextBuffer` |
| 5 | `TagNode.Shell` (modified) | Adds `autoScript: AutoScript` field (nullable) | `AutoScript` |
| 6 | `ShellEditorDialog` (modified) | Adds an "Auto Script" section with enable checkbox, timeout spinner, and steps table | `AutoScript`, `Step` |
| 7 | `TagConfigStore` (modified) | Reads / writes the optional `autoScript` field via Jackson | Jackson, `AutoScript`, `Step` |

`TerminalLauncher` gains a one-liner: if `shell.autoScript() != null`, construct
`new AutoScriptRunner(session, shell.autoScript())` and call `.start()` before
returning. All other call sites of `TerminalLauncher.launch(...)` are unaffected.

`TerminalSession` / `TerminalPanel` are unchanged — they see only that the
session is closed earlier than usual, and remove the tab as they already do.

## 4. Data Flow

### 4.1 Config schema (extended `config.json`)

```json
{
  "version": 2,
  "tags": [
    {
      "type": "shell",
      "id": "a1b2c3d4-0000-0000-0000-000000000003",
      "name": "PowerShell",
      "shellPath": "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
      "iconPath": null,
      "startPath": "C:\\Users\\<your-username>",
      "autoScript": {
        "timeoutMs": 10000,
        "steps": [
          { "waitPattern": "login:", "command": "myuser" },
          { "waitPattern": "Password*", "command": "mypass" },
          { "waitPattern": "*\\$ ", "command": "ls -la" },
          { "waitPattern": "", "command": "exit" }
        ]
      }
    }
  ]
}
```

- `autoScript` is optional. Omitting it → `null` → no auto-run.
- `waitPattern` is `String` (may be empty / blank → "send immediately").
- `command` is `String` (may be empty → no-op step, still consumes the inter-step interval).

### 4.2 TagNode.Shell extension

```java
public record Shell(UUID id, String name, String shellPath, String iconPath,
                    String startPath, AutoScript autoScript) implements TagNode {
  public Shell {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(shellPath, "shellPath");
    Objects.requireNonNull(startPath, "startPath");
    // iconPath, autoScript are intentionally nullable.
  }
  // ...
}
```

Constructors that omit `autoScript` (for use in existing tests) call
`this(id, name, shellPath, iconPath, startPath, null)`.

### 4.3 AutoScript / Step records

```java
public record AutoScript(int timeoutMs, List<Step> steps) {
  public static final int DEFAULT_TIMEOUT_MS = 10_000;

  public AutoScript {
    steps = steps == null ? List.of() : List.copyOf(steps);
  }

  public static final int STEP_INTERVAL_MS = 100;
}

public record Step(String waitPattern, String command) {
  public Step {
    Objects.requireNonNull(waitPattern, "waitPattern");
    Objects.requireNonNull(command, "command");
  }
}
```

### 4.4 Lifecycle: launch with auto-script

```
User double-clicks shell node N
  → TerminalPanel.openSession(N)
  → TerminalLauncher.launch(N)
       1. PtyProcessBuilder.start() → PtyProcess
       2. PtyProcessTtyConnector(...)
       3. JediTermWidget(...) and widget.start()
       4. Construct TerminalSession(session)
       5. If shell.autoScript != null:
              runner = new AutoScriptRunner(session, shell.autoScript)
              runner.start()
       6. Return LaunchResult.Success(session)
  → TerminalPanel adds tab, widget visible
  → AutoScriptRunner (background thread):
       loop steps:
         1. wait = step.waitPattern (blank → skip wait)
         2. if wait non-blank → block on listener until line containing
            a glob-match of wait appears OR timeoutMs elapses
              on timeout: log.error(...); session.close(); return
         3. write(step.command) to TtyConnector
         4. Thread.sleep(STEP_INTERVAL_MS)   // 100 ms
       end → runner exits, session continues normally
```

### 4.5 Output matching — cursor model

- At `run()` start, `cursorLine = session.getWidget().getTerminalTextBuffer().getLineCount()`.
- JediTerm's `TextBufferChangesListener.linesChanged(n)` is registered for
  script duration. On each call, the runner polls the buffer for all lines
  in `[cursorLine, currentLineCount)`.
- For each candidate line, `globMatcher.matches(line)` is checked.
- First match: `cursorLine = matchedLineIndex + 1`; unblocks the waiter via a
  `CountDownLatch`; loop continues.
- Polling cadence: the listener drives wake-ups, so there is no
  busy-wait. The runner also installs a one-shot `ScheduledExecutorService`
  timer at `run()` start that fires `latch.countDown()` after `timeoutMs`
  to break a stuck wait.
- After script ends, listener is removed; timer cancelled.

### 4.6 On timeout

```
runner thread: latch.await(timeoutMs) timed out
  → log.error("AutoScript timed out at step {i}: pattern '{pattern}' not seen within {timeoutMs}ms — closing tab")
  → session.close()
       → TerminalSession.close() → widget.close(), connector.close()
       → TerminalSession.onClosed handler (already registered by TerminalPanel)
       → TabPane removes tab
       → window title ball aggregate decremented
  → runner returns; thread exits
```

### 4.7 GlobMatcher

```java
public final class GlobMatcher {
  private final PathMatcher pathMatcher;

  private GlobMatcher(PathMatcher pm) { this.pathMatcher = pm; }

  public static GlobMatcher compile(String pattern) {
    Objects.requireNonNull(pattern, "pattern");
    // Auto-wrap literal patterns (no wildcard metacharacters) with
    // leading + trailing '*' so users get "contains" semantics by default.
    // Patterns already containing '*' or '?' remain anchored (full-line
    // match) per JDK glob behavior.
    boolean hasWildcard = pattern.indexOf('*') >= 0 || pattern.indexOf('?') >= 0;
    String glob = hasWildcard ? pattern : "*" + pattern + "*";
    return new GlobMatcher(
        FileSystems.getDefault().getPathMatcher("glob:" + glob));
  }

  public boolean matches(CharSequence line) {
    // The full line becomes the path name. JDK glob treats it as a single
    // segment when there are no path separators, which holds for typical
    // terminal output. Users should not include '/' or '\' in their wait
    // patterns; the runner does not preprocess separators.
    return pathMatcher.matches(Path.of(line.toString()));
  }
}
```

Literal patterns auto-wrap example:
- `welcome`      compiled to `*welcome*`    matches `Welcome to Linux` (case-sensitive)
- `Password*`    compiled as-is             matches `Password:` and `Password for user:`
- `?og`          compiled as-is             matches `dog` and `log` (not `plog`)
- empty string   compiled to `**`           matches every line (effectively "fire immediately on next output") — the runner treats blank `waitPattern` as a separate path and skips the wait entirely without invoking this matcher

## 5. Error Handling

| Scenario | Detection | Response |
|---|---|---|
| `autoScript.timeoutMs <= 0` | `AutoScript` constructor (compact) | Throw `IllegalArgumentException` — invalid shell config; UI prevents it |
| `autoScript.timeoutMs > 60 000` | `ShellEditorDialog.onOk()` validation | Clamp to 60 000, show inline error label |
| Empty `steps` list when checkbox is enabled | `ShellEditorDialog.onOk()` validation | Show inline error: "Add at least one step" |
| Existing config file without `autoScript` | Jackson: field missing on `TagNodeDto` | `parseNode` defaults `autoScript = null`; no other behavior change |
| `autoScript.waitPattern` is null (corrupt entry from manual file edit) | `Step` constructor | Throw `IllegalArgumentException`; the corrupt-config backup path in `TagConfigStore.load()` fires |
| TtyConnector write fails (PTY already dead) | IOException caught on `connector.write(...)` | Log WARN, abort script (treat like timeout) — closes session |
| Listener fires after script finishes | `linesChanged` arrives after `runner.done` flag set | Ignored; runner has already removed its listener |
| User closes the tab manually mid-script | `session.close()` invoked externally | `AutoScriptRunner` sees `session.isClosed()` true on next loop iteration, exits cleanly |

## 6. UI

`ShellEditorDialog` gains an **Auto Script** section below the existing fields.
Layout (top-to-bottom):

1. Existing fields unchanged: Name, Shell path, Icon path, Start path.
2. New `JCheckBox`: "Enable auto script" — when unchecked, all controls below are
   hidden and the saved `Shell.autoScript` is `null`.
3. New `JSpinner` (range 1 000 .. 60 000, step 1 000, default 10 000) labeled
   "Timeout (ms) per step".
4. New `JTable` with 2 columns (`Wait pattern`, `Command`). Buttons below:
   - "Add row"
   - "Delete selected"
   - "Move up"
   - "Move down"
5. Inline error label shared with existing fields (uses `errorLabel`).

On `OK`, the dialog constructs:
```java
AutoScript as = new AutoScript((int) spinner.getValue(), rowsToSteps(model));
return new TagNode.Shell(id, name, shell, icon, start, checkbox.isSelected() ? as : null);
```

On `populate(Shell s)`:
- `checkbox.setSelected(s.autoScript() != null)`
- If selected, fill spinner and rows.

## 7. Testing

### 7.1 Unit tests (JUnit 5)

**`GlobMatcherTest`**
- `star_matchesPrefixSuffixAndEmpty`
- `question_matchesExactlyOneChar_notZero_notMany`
- `mixedStarAndQuestion_combined`
- `literalOnly_autoWrappedToContains_matchesAnywhereOnLine`
- `patternAlreadyWithGlob_remainsAnchored_doesNotGetExtraStars`

**`AutoScriptRunnerTest`** — runs against fakes (`FakeTtyConnector`, `FakeTerminalTextBuffer`)
without spawning a real PTY.
- `run_allSteps_matchInOrder_writesEachCommandWithAtLeastInterval`
- `run_emptyWaitPattern_firesImmediatelyAfterInterval`
- `run_timeoutAtStepK_closesSession`
- `run_existingOutputAtStart_isInScope` (asserts a pattern matching the
  buffer state pre-script runs counts)
- `run_ownEchoedCommandIsInScope` (asserts a step waits for a line that
  the runner itself wrote two steps earlier)
- `run_zeroSteps_completesImmediately_withoutSending`
- `run_manuallyClosedSession_exitsCleanly`

### 7.2 Manual integration test (added to README)

1. Add a shell node with `shellPath = cmd.exe`, enable auto-script, add
   one step `(waitPattern = "Microsoft Windows", command = "" or "echo hi")`.
2. Double-click → tab opens; after the first banner line appears, the
   command fires (visible in the tab).
3. Add a deliberately wrong pattern (e.g. `xxx:`). Double-click → after
   10 s the tab closes itself and an `ERROR` line appears in the log file.
4. Restart the app — every configured shell node still loads with its
   script intact.

### 7.3 What we deliberately do NOT test

- Real PTY behavior of cmd/PowerShell/bash with auto-script (covered by
  manual test).
- Swing `ShellEditorDialog` UI rendering (no existing test harness, manual).

## 8. Out of Scope (v2 of this feature)

- Per-step timeout override
- Async / parallel pair branches
- Conditional steps (`if` last command exit code, etc.)
- Exporting / sharing auto-scripts between shell nodes
- Script-level stop button inside the tab

These can be added later without disturbing the component boundaries
defined here.

## 9. Repository Layout (delta from current)

```
src/main/java/local/term/
├── AutoScript.java             (new)
├── Step.java                   (new)
├── GlobMatcher.java            (new)
├── AutoScriptRunner.java       (new)
├── TagNode.java                (modified — Shell record signature)
├── ShellEditorDialog.java      (modified — new section)
└── TagConfigStore.java         (modified — read/write autoScript)

src/test/java/local/term/
├── GlobMatcherTest.java        (new)
└── AutoScriptRunnerTest.java   (new)
```

## 10. Open Questions

None. All decisions resolved during brainstorming phase.
