# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`local-term-java` is a Windows desktop terminal manager. The UI is Java Swing + FlatLaf. The right pane hosts real terminal sessions powered by **JediTerm 3.73** rendering and **pty4j 0.13.8** spawning shells through **Windows ConPTY**. The left pane is a JTree of user-defined shell entries, persisted as JSON at `%USERPROFILE%\.local-term-java\config.json`.

## Build & run

Prerequisites: JDK 17 on PATH, Maven 3.9+, and a JediTerm source checkout at the sibling path `..\jediterm` (JediTerm is consumed via `install-jediterm.bat`).

| Task | Command |
|---|---|
| Install JediTerm to local Maven repo (one-time) | `scripts\install-jediterm.bat` |
| Build (shaded jar + Windows app image via jpackage) | `mvn clean package` |
| Build without jpackage (skip native installer) | `mvn clean package -Djpackage.skip=true` |
| Run from shaded jar | `java -jar target\local-term-java-0.1.0-SNAPSHOT.jar` |
| Run via Maven exec (good for IDE-like feedback) | `mvn exec:java` |
| Run a single test class | `mvn test -Dtest=ClassName` |
| Run a single test method | `mvn test -Dtest=ClassName#methodName` |
| Re-sync bundled default shell SVGs | `scripts\convert-shell-icons.bat` (source: sibling `local_terminal` project) |

Build quirk: `maven-shade-plugin` runs in the `package` phase, then `maven-antrun-plugin` runs `jlink` to produce a slim `target/custom-jre/` and stages the fat jar into `target/jpackage-input/`, then `jpackage-maven-plugin` wraps that into `target/dist/`. If you add a new dependency that needs reflective JDK access, append its module name to `jdk.modules.extra` in `pom.xml`. The `FontUtils` class reaches into `sun.font.CompositeFont`, so Maven Surefire and `mvn exec:java` both already pass `--add-opens java.desktop/sun.font=ALL-UNNAMED`.

## Module / package layout

Everything is in the single package `local.term` (split only when a class exceeds ~300 lines).

## Big-picture architecture

Three top-level layers, each independently understandable:

```
+---------------------------------------------------------+
| MainFrame (JFrame, menu bar, JSplitPane layout)         |
+----------------+----------------------------------------+
| TagTreePanel   | TerminalPanel                          |
|  (LEFT)        |  (RIGHT)                               |
|                |                                        |
| JTree over     |  JTabbedPane of live TerminalSession   |
| TagTreeModel,  |   |-> JediTermWidget                   |
| TagConfigStore |   |-> PtyProcessTtyConnector           |
| (JSON @        |   |-> PtyProcess (ConPTY)              |
|  ~/.local-     |   |-> TerminalSession activity hooks   |
|  term-java/    |   |-> optional AutoScriptRunner        |
|  config.json)  |                                        |
+----------------+----------------------------------------+
```

`App` → installs FlatDarkLaf, builds `MainFrame` on the EDT. `MainFrame` owns one `TagConfigStore` (tree), one `AppSettings` (font slots), and a single shared `ShellIconResolver` (so tree icons and tab headers share the SVG / raster cache).

### Tree + persistence (left pane)

- `TagNode` is a `sealed interface` with two record kinds: `Folder` (organisational, has `expanded`) and `Shell` (leaf, has `shellPath`/`shellArgs`/`iconPath`/`startPath`/`autoScript`). UUID equality is the editing contract — id-based `equals`/`hashCode`.
- `TagTreeModel` is a `DefaultTreeModel` adapter. Mutators fire precise `treeNodesInserted` / `treeNodesChanged` / `treeNodesRemoved` events. `replaceRoot` (full reload) fires `nodeStructureChanged` and is reserved for disk reload only — it collapses selection and expansion, which is why initial load + drag-and-drop go through precise events.
- `TagConfigStore` writes JSON atomically (`.tmp` → rename). Corrupt files are renamed to `config.json.bak.<timestamp>` and the app starts empty. Every node carries a `type` discriminator (`"folder"` or `"shell"`); missing `type` is treated as corrupt. Schema `version` is currently 2.
- `TagTreePanel` is the user-facing tree. It wires: (1) right-click menu (Add folder/shell, Edit, Duplicate, Delete); (2) double-click on a `Shell` → fires `onLeafActivated` → `TerminalPanel::openSession`; (3) drag-and-drop via `TagTreeTransferHandler` (drops into folders reparent + auto-expand; sibling reorder; drop on ancestor is a no-op); (4) `TreeExpansionListener` persists the `expanded` flag back into the live node + saves to disk; (5) a structural-change listener re-applies persisted expansion when the model rebuilds.
- Top-level entries must be folders (enforced by the empty-area context menu only offering "Add top-level folder…").

### Terminal pipeline (right pane)

- `TagTreePanel` calls `TerminalLauncher.launch(tag, onTimeout)`. Returns a sealed `LaunchResult.Success(session)` / `LaunchResult.Failure(message)` — the caller shows a `JOptionPane` on Failure. **Returns failure rather than throws.**
- `TerminalLauncher` builds a `PtyProcess` via pty4j with `setUseWinConPty(true)`, wraps it in `PtyProcessTtyConnector` (a local subclass of `com.jediterm.terminal.ProcessTtyConnector` because JediTerm 3.73 dropped the upstream `PtyProcessTtyConnector`), creates a `CompositeFontJediTermWidget` with `DarkSettingsProvider`, then `widget.createTerminalSession(connector)` + `widget.start()` (both needed in 3.73).
- `DarkSettingsProvider` (extends `DefaultSettingsProvider`) overrides the dark palette (#1e1e1e bg / #d4d4d4 fg, VS-Code-Dark+ style), sets `copyOnSelect = true`, and registers **both** `Ctrl+C`/`Ctrl+V` and `Ctrl+Shift+C`/`Ctrl+Shift+V` as copy/paste accelerators (mirrors Windows Terminal behaviour: copy when text is selected, SIGINT when not).
- `TerminalSession` wraps the widget + connector with an `AtomicBoolean`-guarded `close()`. Activity hooks: `setOnOutput` (called on every new shell bytes batch) + `setOnHighlightCleared` (called on EDT after a 1-second debounce timer).
- `TerminalPanel` owns a `Map<UUID, TerminalSession>` and a `JTabbedPane`. It registers `setOnClosed` on every session — **that single callback is the cleanup point** for both user-close (× button) and EOF (shell exit), so tab removal and map cleanup happen consistently in either path. Custom `CloseableTabHeader` shows the shell icon, name, × button, and a 2 px orange accent strip at the top edge while the session is in its highlight window. An aggregate counter drives a `●` prefix on the window title.

### Auto-script runner (optional per-shell)

- `TagNode.Shell.autoScript` is nullable. When non-null, `TerminalLauncher` starts an `AutoScriptRunner` thread that watches `JediOutputBuffer` (a `TextBufferChangesListener` adapter) and writes via the connector's `TtyConnector.write`.
- Steps run sequentially. Each step waits for a line matching its `waitPattern` (glob via `GlobMatcher`, blank = fire as soon as possible). Step 0 with a blank pattern uses `waitForFirstShellOutput` to dodge JediTerm's init-sequence race; steps N>0 don't need that gate.
- Per-step timeout is the shell-level `timeoutMs` (default 10 s). On timeout the script halts, an ERROR is logged, and the runner invokes the `onTimeout` consumer — `TerminalPanel` shows a `JOptionPane` and **leaves the tab open** so the user can inspect output.
- Commands are sent as `command + "\r"`. Bare CR is the universal Enter terminator for ConPTY and Linux PTY alike; LF would drop PowerShell into `>> ` continuation mode.

### Fonts

- `DarkSettingsProvider.getTerminalFont()` walks a preference list (`Microsoft YaHei Mono` → `Sarasa Mono SC` → `Cascadia Mono` → `Consolas` → `Courier New` → `Monospaced`) and respects a user override stored in `AppSettings.terminalFontFamily`.
- `CompositeFontJediTermWidget` builds a per-character composite font (primary + CJK fallback + Symbol fallback + Emoji fallback) so non-ASCII runs never tofu. `FontUtils` is the auto-detection engine; `DarkSettingsProvider` exposes four override slots (primary / CJK / Symbol / Emoji) that are null when the user picked "(Auto)" in the Settings dialog.
- The composite-font trick reaches into `sun.font.CompositeFont` via reflection — that's why `--add-opens java.desktop/sun.font=ALL-UNNAMED` is wired into both `pom.xml`'s surefire and exec plugins.

### Shell icons

- `ShellIconResolver` is the only place that knows about default icons vs. user-supplied ones. For null `iconPath`, it looks up an SVG from `src/main/resources/shell-icons/` matched by `ShellNameExtractor` (bash / cmd / dash / fish / ksh / nushell / powershell / pwsh / sh / tcsh / xsh / zsh, plus a generic `default.svg`). The matching SVG is rasterised at the L&F's folder-icon size on demand via Apache Batik — no intermediate 32×32 raster that would have to be downscaled.
- For non-null `iconPath`, `IconLoader` dispatches by extension: raster via `ImageIO`, ICO via Commons Imaging, SVG via Batik. Failure (unsupported format / missing file) logs a warning and falls back to the default-by-shellPath icon.
- `ShellIconResolver` is shared by `MainFrame` between `TagTreePanel` and `TerminalPanel` so the cache is single and tree icons match tab headers pixel-for-pixel.

### Settings

`SettingsDialog` exposes four font slots (primary + CJK + Symbol + Emoji) plus an "Auto" option for each. Each saves a `null` for auto-detect. `AppSettings` persists these to `~/.local-term-java/settings.json` atomically; `MainFrame` re-reads them at construction and re-applies them in `openSettings`.

## Testing

24 unit test classes under `src/test/java/local/term/`. Most components have a matching test (e.g. `TagNodeTest`, `TagTreeModelTest`, `TagConfigStoreTest`, `TagTreePanelDnDWiringTest`, `AutoScriptRunnerTest`, `ShellIconResolverTest`, `FontUtilsTest`, `CompositeFontPanelTest`). During iteration, compile + package with `mvn -DskipTests package` — do not run the full suite on every edit.

## Debugging notes

- "Package not found" for `org.jetbrains.jediterm:*` → re-run `scripts\install-jediterm.bat`.
- "Pty4J native load failed" → ensure the 64-bit JDK matches the pty4j native DLL bundled in the jar.
- `AWTError: Assistive Technology not found` at startup on Windows → `jdk.accessibility` is already in `jdk.modules.extra`; if you start a fresh slim JRE, keep it there (some Windows installs ship a stale `~/.accessibility.properties`).
- Auto-script timeout leaves the tab open (intentional) — look for `ERROR ... AutoScript timed out` in the log; the session remains live for inspection.

## Further reading

- `README.md` — user-facing setup + manual integration tests
- `docs/superpowers/specs/2026-07-13-java-terminal-manager-design.md` — original architecture
- `docs/superpowers/specs/2026-07-15-tag-tree-expansion-drag-default-icons-design.md` — tree features
- `docs/superpowers/specs/2026-07-17-shell-auto-script-design.md` — auto-script feature
