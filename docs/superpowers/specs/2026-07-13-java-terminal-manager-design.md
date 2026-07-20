# Java Terminal Manager — Design Spec

**Date:** 2026-07-13
**Status:** Approved (brainstorming phase complete)
**Target:** `D:\eclipse-workspace\local_term_java`

## 1. Purpose

A Windows desktop terminal manager built in Java. The app presents a tree of user-defined "tags" on the left (each tag = a saved shell configuration), and on the right a tabbed pane of live terminal sessions. Double-clicking a tag opens a new terminal running that tag's configured shell, starting in that tag's configured working directory.

## 2. Constraints & Decisions

| Decision | Value | Rationale |
|---|---|---|
| JDK | 17 (LTS), installed by the user (no pinned path in spec) | JediTerm 3.73 targets JVM 11+; user confirmed newer JDK over the originally-mentioned JDK 1.8.0_152. The Maven toolchain can be pointed at the user's installed JDK via `JAVA_HOME` (or an IDE run-config). |
| Platform | Windows only | User explicitly requested ConPTY |
| Terminal transport | Local PTY via pty4j + ConPTY (`setUseWinConPty(true)`) | Matches user request; ConPTY available on Windows 10+ |
| SSH support | None | Out of scope for v1 |
| Config storage | JSON via Jackson, at `%USERPROFILE%\.local-term-java\config.json` | User-selected |
| Tree CRUD | Full CRUD via right-click menu (Add / Edit / Delete / Move Up/Down) | User-selected |
| Session persistence | None — closing app kills all open terminals | User-selected (clean close) |
| Build system | Maven (this project); Gradle only used once to install JediTerm to local Maven repo | JediTerm's own build is Gradle |
| UI toolkit | Java Swing | JediTerm's `ui` module is Swing-based |

## 3. Architecture

```
+--------------------------------------------------------+
| MainFrame (JFrame)                                      |
+--------------------------------------------------------+
| MenuBar: [File] [Help]                                  |
+--------------------------------------------------------+
| [ TagTreePanel (LEFT)  | TerminalPanel (RIGHT)         ]|
| [                      | [Tab1][Tab2][+]              ]|
| [ > Dev                |---------------------------    ]|
| [   > Backend          |                            ]|
| [     cmd (leaf)       |     JediTermWidget         ]|
| [     powershell       |     (terminal display)     ]|
| [ > Ops                |                            ]|
+----------------------+---------------------------------+
```

- **UI:** Java Swing (`JFrame`, `JSplitPane`, `JTree`, `JTabbedPane`).
- **Terminal widget:** `JediTermWidget` from JediTerm `ui` module.
- **PTY:** `PtyProcessBuilder` from pty4j with `setUseWinConPty(true)`.
- **Persistence:** JSON via Jackson.

**Process model:** Each open tab owns one `PtyProcess` + one `PtyProcessTtyConnector` + one `JediTermWidget` + one `TerminalStarter` thread. Tab close → `session.close()` → process destroyed. App exit → close all tabs → all processes killed.

## 4. Components

Ten single-purpose units, each independently understandable and testable.

| # | Component | Purpose | Key Dependencies |
|---|---|---|---|
| 1 | `App` | Entry point. Builds `MainFrame` on EDT. | — |
| 2 | `MainFrame` | `JFrame` with menu bar and `JSplitPane` layout. Owns the two main panels. Wires `TagTreePanel`'s `leafActivated` callback to `TerminalPanel.openSession(...)`. | `TagTreePanel`, `TerminalPanel`, `TagConfigStore` |
| 3 | `TagNode` | Immutable data record: `id` (UUID), `name`, `shellPath`, `iconPath` (nullable), `startPath`, `children`. Methods: `withChildren(...)`, `findById(...)`, `addOrReplaceChild(...)`, `removeChild(...)`. | — |
| 4 | `TagTreeModel` | `DefaultTreeModel` adapter exposing a `TagNode` tree to Swing. Maps Swing tree events back to `TagNode` mutations; fires `nodeStructureChanged` after each mutation. | `TagNode` |
| 5 | `TagConfigStore` | Load/save root `TagNode` to/from JSON file. Atomic write (write `.tmp` → rename). On corrupt-file: rename to `config.json.bak.<timestamp>`, start empty, log warning. | Jackson, `TagNode` |
| 6 | `TagTreePanel` | Left panel. Holds `JTree` + custom renderer + right-click menu (Add Child / Edit / Delete / Move Up / Move Down). Double-click on a leaf → fires `onLeafActivated(TagNode)` listener. On any tree mutation → calls `store.save(root)`. | `TagNode`, `TagTreeModel`, `TagEditorDialog`, `TagConfigStore` |
| 7 | `TagEditorDialog` | Modal `JDialog` with form fields (name, shellPath, iconPath, startPath) + file pickers for shellPath/iconPath/startPath. Validates required fields (name, shellPath, startPath). Returns updated `TagNode` or `null` on cancel. | `TagNode` |
| 8 | `TerminalLauncher` | Static factory: `LaunchResult launch(TagNode node)`. Creates `PtyProcess` → wraps in `PtyProcessTtyConnector` → creates `JediTermWidget` → starts `TerminalStarter`. Returns `Result<Success, Failure(String)>`. Catches spawn exceptions and returns them as `Failure`. | pty4j, JediTerm |
| 9 | `TerminalSession` | Holds `JediTermWidget` + `TtyConnector` + `TerminalStarter`. `close()` shuts down the process and fires `onClosed` listener. | JediTerm |
| 10 | `TerminalPanel` | Right panel: `JTabbedPane` + session map. Methods: `openSession(TagNode)` (calls launcher, adds tab or shows error dialog), `closeSession(int index)`, `closeAll()`. Subscribes to `TerminalSession.onClosed` to remove dead tabs. | `TerminalLauncher`, `TerminalSession`, `TagNode` |

**Package layout:** single package `local.term` for v1. Sub-packages only if a component exceeds ~300 lines.

## 5. Data Flow

### 5.1 Config schema (`%USERPROFILE%\.local-term-java\config.json`)

```json
{
  "version": 1,
  "tags": [
    {
      "id": "5b1c2a3d-0000-0000-0000-000000000001",
      "name": "Backend",
      "shellPath": "C:\\Windows\\System32\\cmd.exe",
      "iconPath": "C:\\icons\\cmd.ico",
      "startPath": "D:\\projects\\backend",
      "children": [
        {
          "id": "7e8f9a0b-0000-0000-0000-000000000002",
          "name": "Tests",
          "shellPath": "C:\\Windows\\System32\\cmd.exe",
          "iconPath": null,
          "startPath": "D:\\projects\\backend\\tests",
          "children": []
        }
      ]
    },
    {
      "id": "a1b2c3d4-0000-0000-0000-000000000003",
      "name": "PowerShell",
      "shellPath": "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
      "iconPath": null,
      "startPath": "C:\\Users\\<your-username>",
      "children": []
    }
  ]
}
```

- `iconPath` is optional; UI falls back to a default icon when null.
- Only leaf nodes (no children) are launchable as terminals. Non-leaf nodes act as folders.
- UUID `id` per node — survives rename, future-proofs for session persistence.

### 5.2 Tree mutation flow

```
User right-clicks node N
  → TagTreePanel shows context menu
  → User picks action (Add Child / Edit / Delete / Move)
  → If Edit/Add: TagEditorDialog opens with current values (or blank for Add)
  → User edits, clicks OK
  → dialog returns new TagNode (or null on cancel)
  → TagTreeModel mutates the root via TagNode methods
  → TagTreeModel.nodeStructureChanged(...)
  → TagTreePanel calls TagConfigStore.save(root)
```

### 5.3 Terminal launch flow (double-click leaf)

```
User double-clicks leaf node N
  → TagTreePanel fires onLeafActivated(N)
  → MainFrame routes to TerminalPanel.openSession(N)
  → TerminalPanel calls TerminalLauncher.launch(N):
       1. PtyProcessBuilder()
            .setCommand(splitCommand(N.shellPath))   // see "Command parsing" below
            .setDirectory(N.startPath)
            .setEnvironment(envWithTERM("xterm-256color"))
            .setUseWinConPty(true)
            .setInitialColumns(120).setInitialRows(30)
            .start()
       2. new PtyProcessTtyConnector(process, UTF_8, command)
       3. new JediTermWidget(settingsProvider)
       4. widget.createTerminalSession(terminalStarter(connector))
       5. terminalStarter.start()  // background thread
  → Returns LaunchResult
  → If Success: TerminalPanel adds tab titled N.name, renders widget, focuses tab
  → If Failure: TerminalPanel shows JOptionPane error dialog, does NOT open tab

**Command parsing:** `splitCommand(shellPath)` is a simple whitespace tokenizer with no quote-handling in v1. A path with spaces (e.g. `C:\Program Files\PowerShell\...`) must be the entire shellPath field — users wanting extra flags must use `8.3` short paths (e.g. `C:\PROGRA~1\...`) or move the binary. Documented in README.
```

### 5.4 Tab close flow

```
User clicks tab's × button
  → TerminalPanel.removeTabAt(index)
  → session.close()
       → terminalStarter.close()
       → ttyConnector.close()  // kills PtyProcess
  → tab removed from JTabbedPane
```

### 5.5 App shutdown flow

```
WindowListener.windowClosing
  → if TerminalPanel has any open tabs:
       confirm dialog: "Close N terminals?"
       Yes → TerminalPanel.closeAll() → MainFrame.dispose() → System.exit(0)
       No  → cancel close
  → else: MainFrame.dispose() → System.exit(0)
```

## 6. Maven Setup

### 6.1 `pom.xml`

```xml
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>local.term</groupId>
  <artifactId>local-term-java</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>jar</packaging>

  <properties>
    <maven.compiler.source>17</maven.compiler.source>
    <maven.compiler.target>17</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>

    <jediterm.version>3.73</jediterm.version>
    <pty4j.version>0.13.8</pty4j.version>
    <jackson.version>2.17.2</jackson.version>
    <slf4j.version>2.0.13</slf4j.version>
    <junit.version>5.10.2</junit.version>
  </properties>

  <dependencies>
    <!-- JediTerm (installed locally via gradle publishToMavenLocal) -->
    <dependency>
      <groupId>org.jetbrains.jediterm</groupId>
      <artifactId>jediterm-core</artifactId>
      <version>${jediterm.version}</version>
    </dependency>
    <dependency>
      <groupId>org.jetbrains.jediterm</groupId>
      <artifactId>jediterm-ui</artifactId>
      <version>${jediterm.version}</version>
    </dependency>

    <!-- pty4j with ConPTY support (bundles native DLLs) -->
    <dependency>
      <groupId>org.jetbrains.pty4j</groupId>
      <artifactId>pty4j</artifactId>
      <version>${pty4j.version}</version>
    </dependency>

    <!-- JSON persistence -->
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
      <version>${jackson.version}</version>
    </dependency>

    <!-- Logging -->
    <dependency>
      <groupId>org.slf4j</groupId>
      <artifactId>slf4j-api</artifactId>
      <version>${slf4j.version}</version>
    </dependency>
    <dependency>
      <groupId>org.slf4j</groupId>
      <artifactId>slf4j-simple</artifactId>
      <version>${slf4j.version}</version>
    </dependency>

    <!-- Tests -->
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>${junit.version}</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.13.0</version>
      </plugin>

      <plugin>
        <groupId>org.codehaus.mojo</groupId>
        <artifactId>exec-maven-plugin</artifactId>
        <version>3.2.0</version>
        <configuration>
          <mainClass>local.term.App</mainClass>
        </configuration>
      </plugin>

      <plugin>
        <artifactId>maven-shade-plugin</artifactId>
        <version>3.5.3</version>
        <executions>
          <execution>
            <phase>package</phase>
            <goals><goal>shade</goal></goals>
            <configuration>
              <createDependencyReducedPom>false</createDependencyReducedPom>
              <transformers>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                  <mainClass>local.term.App</mainClass>
                </transformer>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
              </transformers>
              <filters>
                <filter>
                  <artifact>*:*</artifact>
                  <excludes>
                    <exclude>META-INF/*.SF</exclude>
                    <exclude>META-INF/*.DSA</exclude>
                    <exclude>META-INF/*.RSA</exclude>
                  </excludes>
                </filter>
              </filters>
            </configuration>
          </execution>
        </executions>
      </plugin>

      <plugin>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.2.5</version>
      </plugin>
    </plugins>
  </build>
</project>
```

### 6.2 One-time JediTerm install script (`scripts/install-jediterm.bat`)

```bat
@echo off
setlocal
echo Installing JediTerm from ..\jediterm to local Maven repo...
pushd ..\jediterm
call gradlew.bat publishToMavenLocal -x test
popd
if errorlevel 1 (
  echo Failed to install JediTerm.
  exit /b 1
)
echo Done. You can now build this project with: mvn clean package
```

Invocation: `scripts\install-jediterm.bat`. Re-run if `jediterm.version` changes in `pom.xml`.

### 6.3 Daily build/run

```
mvn clean package         # builds target/local-term-java-0.1.0-SNAPSHOT.jar (fat jar)
java -jar target/local-term-java-0.1.0-SNAPSHOT.jar
```

## 7. Error Handling

| Scenario | Detection | Response |
|---|---|---|
| JediTerm not in local Maven repo | Maven build fails on `mvn package` | README documents: "Run `scripts\install-jediterm.bat` first." |
| Config file missing on first run | `Files.notExists(path)` | Create file with empty root, continue silently |
| Config file corrupt | Jackson throws `JsonProcessingException` | Rename to `config.json.bak.<timestamp>`, start with empty root, show one-time warning dialog |
| Config save fails (disk full, perms) | `IOException` in atomic write | Show error dialog: "Could not save config: <reason>"; keep in-memory state, retry on next mutation |
| Tree editor: required field blank | `TagEditorDialog.validate()` | Keep dialog open, highlight missing field in red, show inline error |
| Shell binary doesn't exist | `PtyProcessBuilder.start()` throws | Caught in `TerminalLauncher.launch()`, returned as `Failure`; `TerminalPanel.openSession()` shows error dialog, does NOT open tab |
| `startPath` doesn't exist | Caught by PtyProcess | Same — error dialog naming the missing path |
| Process crashes mid-session | JediTerm's `TtyConnector` reports EOF | Session fires `onClosed`; `TerminalPanel` removes tab, shows status bar message: "Terminal 'X' exited (code N)" |
| User closes app with N tabs open | `WindowClosing` | Confirm dialog: "Close N terminals?" — Yes/No. Yes → close all → exit. No → cancel close |

### `LaunchResult` sealed interface

```java
public sealed interface LaunchResult {
  record Success(TerminalSession session) implements LaunchResult {}
  record Failure(String message) implements LaunchResult {}
}
```

`TerminalPanel.openSession(TagNode)` switches on the result: `Success` → add tab; `Failure(message)` → `JOptionPane.showMessageDialog(...)`.

## 8. Testing

### 8.1 Unit tests (JUnit 5)

**`TagNodeTest`**
- `addOrReplaceChild` — adds when id is new, replaces when id exists, preserves other children unchanged
- `removeChild` — returns `true` and removes when present, `false` when absent
- `findById` — finds in nested tree, returns `null` when absent
- `equals`/`hashCode` based on `id`

**`TagConfigStoreTest`**
- `save_then_load_roundTrip_preservesTreeStructureExactly`
- `load_missingFile_returnsEmptyRoot`
- `load_corruptFile_createsBackupAndReturnsEmptyRoot`
- `save_isAtomic` — verify no partial file left behind when simulated IOException occurs mid-write

**`TerminalLauncherTest`** — only validation/parsing layer (not actual pty):
- `splitCommand("C:\\Windows\\System32\\cmd.exe")` → `["C:\\Windows\\System32\\cmd.exe"]` (single-element array, no whitespace)
- `splitCommand("cmd.exe /k echo hi")` → `["cmd.exe", "/k", "echo", "hi"]`
- `resolveEnvironment` includes `TERM=xterm-256color`

### 8.2 Manual integration test (documented in README)

1. Run app on a fresh machine → tree panel appears empty (no sample nodes shipped in v1).
2. Right-click root → Add Child → fill in `cmd.exe` shellPath and a working startPath → OK → node appears.
3. Double-click node → terminal tab opens, shell prompt visible.
4. Type `dir` → output renders correctly.
5. Close tab → terminal closes cleanly (verify no orphaned process in Task Manager).
6. Restart app → node still in tree (config persisted).

### 8.3 What we deliberately do NOT test

- JediTerm's terminal emulation (JetBrains' responsibility).
- pty4j / ConPTY (library responsibility).
- Swing layout / rendering (manual verification).

## 9. Logging

- `slf4j-simple` at `INFO` by default; override via `-Dorg.slf4j.simpleLogger.defaultLogLevel=debug`.
- Key events logged: config save/load, terminal launch (command + cwd), process exit codes, errors.
- No `logback.xml` or `simplelogger.properties` is shipped — `slf4j-simple` defaults are sufficient for v1.

## 10. Out of Scope (v1)

- SSH / remote terminals
- Session persistence across app restarts
- Cross-platform support (Linux/macOS)
- Terminal split panes within a tab
- Custom terminal color schemes / font settings UI
- Multi-window support

These can be added in later versions without disturbing the core component boundaries.

DnD reordering and reparenting is now in scope — see
`docs/superpowers/specs/2026-07-15-tag-tree-expansion-drag-default-icons-design.md`.

## 11. Repository Layout

```
D:\eclipse-workspace\local_term_java\
├── pom.xml
├── README.md
├── scripts\
│   └── install-jediterm.bat
├── docs\
│   └── superpowers\
│       └── specs\
│           └── 2026-07-13-java-terminal-manager-design.md   (this file)
└── src\
    ├── main\
    │   ├── java\
    │   │   └── local\term\
    │   │       ├── App.java
    │   │       ├── MainFrame.java
    │   │       ├── TagNode.java
    │   │       ├── TagTreeModel.java
    │   │       ├── TagConfigStore.java
    │   │       ├── TagTreePanel.java
    │   │       ├── TagEditorDialog.java
    │   │       ├── TerminalLauncher.java
    │   │       ├── TerminalSession.java
    │   │       └── TerminalPanel.java
    │   └── resources\                       (empty in v1; slf4j-simple needs no config)
    └── test\
        └── java\
            └── local\term\
                ├── TagNodeTest.java
                ├── TagConfigStoreTest.java
                └── TerminalLauncherTest.java
```

## 12. Open Questions

None. All design decisions resolved during brainstorming phase.</groupId>