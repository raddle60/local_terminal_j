# Tag Tree — Expansion Persistence, Drag-and-Drop, Default Icons

**Date:** 2026-07-15
**Status:** Approved (brainstorming phase complete)
**Target:** `D:\eclipse-workspace\local_term_java`
**Supersedes:** Section 10 "Drag-and-drop reordering of tree nodes" item in
`docs/superpowers/specs/2026-07-13-java-terminal-manager-design.md` (this
feature is now in scope).

## 1. Purpose

Three improvements to the left-hand tag tree in the existing Java Swing
terminal manager:

1. **Persistent expansion state.** Each folder remembers whether it was
   expanded or collapsed when the user last saw it; the state survives app
   restarts.
2. **Drag-and-drop reordering and reparenting.** Both folder and shell
   nodes can be dragged within the same parent (reorder as siblings) or
   dropped onto another folder (move to become its child). Standard
   Windows-Explorer-style gesture: drop onto a folder to move in, drop
   above/below a node to reorder.
3. **Default shell icons.** When a shell node has no `iconPath` set, the
   tree shows a default icon matched from the shell's `shellPath`
   (e.g. `bash` → bash icon, `cmd` → cmd icon, `wsl` → bash icon). The
   user can still override the default by setting `iconPath` to any
   common image file (PNG, JPEG, BMP, GIF, ICO, SVG).

## 2. Constraints & Decisions

| Decision | Value | Rationale |
|---|---|---|
| Expansion state location | `expanded` field on `TagNode.Folder` record, persisted in `config.json` | Per-folder data; lives with the node; one source of truth. |
| Default for new folders | `expanded = true` | Users see their content immediately; matches current behavior. |
| Default for folders loaded from legacy v1 configs | `expanded = true` | Backwards-compatible; no regression for existing users. |
| Config schema version | Bump from 1 → 2 (additive) | Existing v1 files load unchanged, then write back as v2 on first save. |
| DnD framework | Swing `TransferHandler` + custom `DataFlavor` | Idiomatic; reuses existing event/save infrastructure; ~150 lines. |
| Drop positions supported | Into a folder, above/below any node (siblings) | User-selected. Standard Windows Explorer model. |
| Invalid drop behavior | Silent no-op; standard OS cursor flip during drag | User-selected. No error dialogs. |
| Auto-expand side-effect | When a node is dropped into a collapsed folder, that folder is expanded and the new state is persisted | Makes the dropped node visible immediately; persistence piggy-backs on the `TreeExpansionListener`. |
| Default icon assets | 12 SVGs (64×64 viewBox) copied from the sibling project's renderer assets, committed to `src/main/resources/shell-icons/` | Vector source — `ShellIconResolver` rasterises the matching SVG at the L&F's folder-icon size (typically 16×16) via Apache Batik on every cache miss, so the result is crisp on every L&F without the "decode-at-32-then-downscale" quality loss. |
| User-supplied icon formats | PNG, JPEG, BMP, GIF (via `ImageIO`), ICO (via Commons Imaging), SVG (via Apache Batik) | User requested multi-format support. |
| User-supplied icon on failure | Log warning, fall back to the default-by-shellPath icon (or `default.svg`) | Same UX as if no `iconPath` were set; never blocks the tree. |
| Icon library for SVG | `batik-rasterizer` + `batik-svggen` from Apache XML Graphics | User-selected. Standard SVG renderer for Java. |
| Icon library for ICO | `commons-imaging` from Apache (1.0.0-alpha6) | Mature, focused, ~250KB. |
| `TagConfigStore.ROOT_ID` visibility | `public` | The transfer handler and resolver need to check against the synthetic root. |
| Renderer change | `FolderIconCellRenderer` constructor takes a `ShellIconResolver`; existing no-arg constructor removed | Cleaner; tests pass an explicit resolver. |
| Equality semantics | `TagNode.Folder` equality stays id-based; `expanded` does not participate | Same contract as today; two folders with the same id are the same node regardless of expansion. |

## 3. Architecture

The three new features slot into the existing ten-component architecture
without changing any existing component's responsibility.

### 3.1 New / changed components

| # | Component | Change | Purpose | Key dependencies |
|---|---|---|---|---|
| 11 | `ShellIconResolver` | new | Maps a `TagNode.Shell` → an `ImageIcon`. For null `iconPath`, looks up a default SVG from the bundled classpath assets by shell-name match and rasterises it at the resolver's configured target size (passed in by the renderer) so the on-screen result is already at the L&F folder-icon size. For non-null `iconPath`, loads that file (PNG/JPEG/BMP/GIF/ICO/SVG) via the icon loader; SVGs are rendered at the same target size. | `IconLoader`, `ShellNameExtractor` |
| 12 | `IconLoader` | new | Loads an `Image` from a `Path` or URL. Dispatches by extension: raster via `ImageIO`, ICO via Commons Imaging, SVG via Batik. SVG loading has a size-aware overload (`loadSvg(URL, int)`, `loadSvg(Path, int)`) used by the resolver to avoid the "decode-large-then-downscale" quality loss. Caches by `Path` (bounded `LinkedHashMap`, 64 entries). Returns `null` on unsupported format; throws `IOException` on I/O failure. | `ImageIO`, Commons Imaging, Batik |
| 13 | `ShellNameExtractor` | new | Pure function `String extract(String shellPath)` returning the basename of the matching default icon, or `null`. Order matches the sibling TypeScript project's `getShellIcon`/`extractShellName` so the two apps stay visually consistent. | — |
| 14 | `TagTreeTransferHandler` | new (~150 lines) | `TransferHandler` installed on the `JTree` in `TagTreePanel`. Exports a UUID via custom `DataFlavor`; imports by resolving `JTree.DropLocation` (path + child index) to "into folder" / "above/below sibling" semantics, then routes through the existing `applyWithPreciseUiUpdate` helper. | `TagTreeModel`, `TagTreePanel`, `TagConfigStore` |
| 15 | `TagConfigStore` | modified | Adds `expanded` to `TagNodeDto`; bumps `CURRENT_VERSION` to 2; on load, missing `expanded` on a v1 file defaults to `true`. | Jackson |
| 16 | `TagNode.Folder` | modified | One new field: `boolean expanded` (default `true`). New method `withExpanded(boolean)`. | — |
| 17 | `TagTreeModel` | modified | New mutator `moveNode(UUID sourceId, UUID newParentId, int insertIndex)` that performs the structural move and fires precise `treeNodesRemoved` + `treeNodesInserted` events. The userObject for expansion is mutated in-place by the `TreeExpansionListener`; the model exposes no `setExpanded` mutator because no caller needs it (YAGNI). | — |
| 18 | `TagTreePanel` | modified | Constructor: install DnD on the `JTree`, install the `TreeExpansionListener` for persistence, walk the model after load to apply expansion to the JTree. Wires `ShellIconResolver` into the renderer. | all of the above |
| 19 | `FolderIconCellRenderer` | modified | Constructor takes a `ShellIconResolver`. Adds a branch for `TagNode.Shell` that sets the icon from the resolver. Existing folder behaviour unchanged. | `ShellIconResolver` |

**What does NOT change:** `MainFrame`, `App`, `TerminalPanel`, `TerminalLauncher`,
`TerminalSession`, the dialogs, the `TagNode.Shell` record (no `expanded`),
the renderer pattern (still `DefaultTreeCellRenderer` subclasses).

### 3.2 Component boundaries

- `ShellNameExtractor` is pure → trivially unit-testable, shareable with the
  TS project later.
- `IconLoader` is a thin dispatch layer → easy to mock for renderer tests;
  cache logic isolated.
- `ShellIconResolver` is the only place that knows about "default" icons vs.
  user-supplied ones → renderer stays dumb.
- `TagTreeTransferHandler` knows about DnD plumbing only; the actual
  mutation goes through the same `TagTreeModel` + `store.save(...)` path as
  every other UI mutation, so disk persistence, UI event firing, and
  selection preservation are reused.
- The `TreeExpansionListener` in `TagTreePanel` is the single source of
  truth for "user expanded this folder" → persistence piggy-back for the
  DnD auto-expand side-effect.

## 4. Data Model

### 4.1 `TagNode.Folder`

```java
record Folder(UUID id, String name, List<TagNode> children, boolean expanded)
    implements TagNode {
  public Folder {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(name, "name");
    children = children == null ? List.of() : List.copyOf(children);
  }

  @Override public String type() { return TYPE_FOLDER; }
  @Override public boolean isFolder() { return true; }

  // existing addOrReplaceChild, removeChild, replaceChild, withChildren — unchanged

  /** Returns a new Folder with the expansion flag set; everything else identical. */
  public Folder withExpanded(boolean newExpanded) {
    return newExpanded == expanded ? this : new Folder(id, name, children, newExpanded);
  }
}
```

- New folders from `addChildFolder` / `addTopLevelFolder` default to
  `expanded = true`.
- `TagNode.Shell` is unchanged.
- `TagNode.equalsById` / `hashCodeById` keep the id-based contract; two
  folders with the same id but different `expanded` are still "the same
  node" for editing purposes.

### 4.2 On-disk schema (version 2)

```json
{
  "version": 2,
  "tags": [
    {
      "id": "5b1c2a3d-0000-0000-0000-000000000001",
      "type": "folder",
      "name": "Backend",
      "expanded": true,
      "children": [
        {
          "id": "7e8f9a0b-0000-0000-0000-000000000002",
          "type": "shell",
          "name": "Tests",
          "shellPath": "C:\\Windows\\System32\\cmd.exe",
          "iconPath": null,
          "startPath": "D:\\projects\\backend\\tests",
          "expanded": false,
          "children": []
        }
      ]
    }
  ]
}
```

`expanded` is always written on every node (including shells, where it is
always `false` and never read). One flat DTO shape is simpler than splitting
folder/shell DTOs.

```java
public record TagNodeDto(String type, UUID id, String name, String shellPath,
                         String iconPath, String startPath, boolean expanded,
                         List<TagNodeDto> children) {}
```

### 4.3 Migration from version 1

- `parseNode` reads `expanded` defensively:
  `n.path("expanded").asBoolean(true)`. Missing field → `true`. Existing v1
  files load unchanged; no backup needed.
- First save of a v1-loaded file writes `version: 2` with `expanded: true`
  everywhere. No user-visible behaviour change.
- `CURRENT_VERSION` constant in `TagConfigStore` bumped from `1` to `2`.

## 5. Data Flow

### 5.1 TagTreeModel mutators

The model exposes one new mutator, `moveNode`. Expansion state is mutated
in-place by the `TreeExpansionListener` (Section 5.4); no model method for
it is needed.

**`moveNode(UUID sourceId, UUID newParentId, int insertIndex)`:**

```java
public void moveNode(UUID sourceId, UUID newParentId, int insertIndex) {
  DefaultMutableTreeNode source = findMutable(sourceId);
  DefaultMutableTreeNode newParent = findMutable(newParentId);
  if (source == null || newParent == null) return;
  if (!(newParent.getUserObject() instanceof TagNode.Folder)) return;
  // The caller (TagTreeTransferHandler) has already validated the move
  // is legal (no self-drop, no cycle, etc.); we just do it.
  // 1. Build the next TagNode tree, persist, then mutate Swing.
  // 2. Detach source from old parent in the Swing tree; rebuild that parent.
  // 3. Insert source into new parent at insertIndex in the Swing tree.
  // 4. fireTreeNodesRemoved on old parent path.
  // 5. fireTreeNodesInserted on new parent path.
  // 6. Caller (handler) sets tree.setSelectionPath on the new path.
}
```

Selection preservation: the precise remove+insert pair keeps the JTree's
scroll/expansion state; the handler explicitly re-sets
`tree.setSelectionPath` on the new path of the moved node.

### 5.2 Drag-and-drop flow

```
User starts drag on node N
  → JTree fires DragGestureRecognized
  → TagTreeTransferHandler.createTransferable returns
    StringSelection(N.id.toString())
  → Swing shows drag image, manages the OS-level gesture

User drags over target T
  → JTree updates the drop-line indicator (above / below / into)
  → TransferSupport.getDropLocation resolves to (path, childIndex):
       -1 = onto the node at path
        0..n = between children of the node at path
  → TagTreeTransferHandler.canImport inspects the drop location and
    returns false for invalid geometries (synthetic root, onto a Shell
    as "into", same source as target, folder into its own descendant).
    JTree flips the cursor to "not allowed" for these, BEFORE the user
    releases — so the user gets feedback during the drag, not just on
    release.

User releases
  → TagTreeTransferHandler.importData:
      1. Parse sourceId from the StringSelection payload.
      2. Resolve newParentId and insertIndex from getDropLocation.
      3. Validate: no self-drop, no cycle (folder into its own descendant),
         no drop on synthetic root.
      4. If newParent is a collapsed folder, ensureExpanded(newParent)
         (mutate userObject, tree.expandPath — TreeExpansionListener
         persists the new state).
      5. Compute the next root, save via store.save(...).
      6. On success: model.moveNode(sourceId, newParentId, insertIndex).
         On failure: error dialog (existing helper), tree unchanged.
```

### 5.3 Drop validation rules

| Source | Target | Result |
|---|---|---|
| Any | Synthetic root (id == `TagConfigStore.ROOT_ID`) | Reject (root is not user-editable) |
| Any | Itself | Reject |
| Folder | One of its own descendants | Reject (prevents cycle) |
| Any node | Onto a Shell (`childIndex == -1` and target is a Shell) | Reject (shells are leaves) |
| Any node | Onto the same parent at the same index | Reject (no-op) |
| Any node | Onto a Folder (`childIndex == -1`) | Move INTO that folder; append at end of children |
| Any node | Between siblings (`childIndex` 0..n) | Insert as sibling at that index |

All rejections are silent no-ops; the OS-level cursor has already
flipped to "not allowed" during the drag.

### 5.4 Expansion persistence flow

```
App starts
  → TagConfigStore.load returns root TagNode (folders carry expanded flag)
  → TagTreeModel built from root
  → TagTreePanel constructor walks the model and calls
    tree.expandPath / tree.collapsePath per folder.expanded():

```java
private void applyExpansionFromModel(DefaultMutableTreeNode node, TreePath path) {
  if (node.getUserObject() instanceof TagNode.Folder f) {
    if (f.expanded() && node.getChildCount() > 0) {
      tree.expandPath(path);
    } else if (!f.expanded()) {
      tree.collapsePath(path);
    }
  }
  for (int i = 0; i < node.getChildCount(); i++) {
    DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
    applyExpansionFromModel(child, path.pathByAddingChild(child));
  }
}
```

  → User sees the tree exactly as they left it

User clicks ▶ handle to collapse folder F
  → JTree fires TreeExpansionEvent (collapsed)
  → TagTreePanel.TreeExpansionListener collapses:
      1. Update userObject to f.withExpanded(false).
      2. Compute next root, store.save(...).
      3. No treeNodesChanged event — the JTree already repainted itself.

User clicks ▶ handle to expand folder F
  → Symmetric: userObject updated, persisted, no event fired.

User drops a node into a collapsed folder F
  → TagTreeTransferHandler.importData calls ensureExpanded(F)
      1. Update F's userObject to withExpanded(true).
      2. tree.expandPath(F's path) — JTree repaints.
      3. JTree fires TreeExpansionEvent → TreeExpansionListener runs.
      4. Listener sees F is already withExpanded(true) in the userObject
         and the next root computes to the same root — idempotent save.
  → Then model.moveNode fires the structural events.
```

The `TreeExpansionListener` is the single source of truth for
"user expanded this folder → persist it" — the DnD auto-expand helper
just produces the right Swing events; the listener handles persistence.

### 5.5 Icon resolution flow

```
TagTreePanel builds a FolderIconCellRenderer with a ShellIconResolver
configured with the L&F's folder-icon side (typically 16 on Windows /
Metal / Nimbus). JTree asks the renderer for a Shell row.
  → resolver.getIcon(shell):
      1. If shell.iconPath() != null and non-blank:
           path = Path.of(shell.iconPath())
           if path ends in .svg:
             try loader.loadSvg(path, targetSize)
           else:
             try loader.load(path)
           on success: return new ImageIcon(img)
           on failure: log warning, fall through to default
      2. name = ShellNameExtractor.extract(shell.shellPath())
      3. resourceName = name == null ? "default" : name
      4. return defaultIconFor(resourceName):
           url = class.getResource("/shell-icons/" + resourceName + ".svg")
           if url == null: return new ImageIcon()  // empty, never null
           try img = loader.loadSvg(url, targetSize)
           catch IOException: return new ImageIcon()  // empty, never null
           return new ImageIcon(img), cached by (resourceName)
```

The resolver is constructed with `targetSize = FolderIconCellRenderer.defaultTargetSize()`,
which is the L&F's folder-icon side length. Default SVGs are rasterised
at that size, so no downscale happens in the renderer. User-supplied
raster icons (PNG, JPEG, ICO) are decoded at native size and the
renderer's existing per-instance LRU still scales them on demand.

The `ShellNameExtractor` substring rules, in order:

1. `bash` → `bash`
2. `zsh` → `zsh`
3. `fish` → `fish`
4. `powershell` or `pwsh` → `powershell`
5. `cmd` → `cmd`
6. `dash` → `dash`
7. `ksh` → `ksh`
8. `tcsh` → `tcsh`
9. `xonsh` → `xonsh`
10. `nushell` or `nu` → `nushell`
11. `wsl` or `ubuntu` → `bash`
12. `sh` → `sh`
13. otherwise → `null` → resolver uses `default.svg`

## 6. Error Handling

| Scenario | Detection | Response |
|---|---|---|
| DnD: drop on invalid target (root, shell-as-into, self, own descendant) | `importData` computes next root, sees no change | Silent no-op. Tree unchanged. Cursor was "not allowed" during drag. |
| DnD: drop during `store.save` failure | `applyWithPreciseUiUpdate` catches `IOException` | Existing error dialog. In-memory state unchanged. |
| DnD: drop where `newParentId` doesn't exist (stale UUID) | `findMutable` returns null | Silent no-op. |
| User-supplied `iconPath` file missing | `IconLoader.load` throws `IOException` | Log warning, fall back to default-by-shellPath icon. No dialog. |
| User-supplied `iconPath` unsupported format (e.g. `.tif`) | `IconLoader.load` returns null | Same — fall back to default. |
| Default icon missing from classpath (resource stripped) | `getResource(...)` returns null | Return empty `ImageIcon` (not null). Tree still renders. |
| Corrupt config file (existing behaviour) | `TagConfigStore.load` catches existing way | Backup + start empty. No regression. |
| Legacy v1 file with no `expanded` field | `parseNode` defaults to `true` | Tree opens with everything expanded — same as today's behaviour. |
| `Shell` node in JSON has `expanded: true` | `parseNode` reads it but `Shell` ignores it | No effect. Defensive only. |
| PNG of a default icon is corrupt / not actually PNG | `ImageIO.read` returns null | Resolver returns empty `ImageIcon`. Logged once. |
| Icon cache exceeds 64 entries | `LinkedHashMap.removeEldestEntry` | Oldest entry evicted. Tree never blocks. |

## 7. Testing

### 7.1 New / extended unit tests

**`ShellNameExtractorTest`** (table-driven, 12 cases):
- `"C:\\Windows\\System32\\cmd.exe"` → `"cmd"`
- `"C:\\Program Files\\PowerShell\\7\\pwsh.exe"` → `"powershell"` (matches `pwsh` rule first)
- `"C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"` → `"powershell"`
- `"C:\\Program Files\\Git\\bin\\bash.exe"` → `"bash"`
- `"C:\\Program Files\\Git\\usr\\bin\\sh.exe"` → `"sh"` (no `bash` substring; `sh` rule last)
- `"C:\\Program Files\\WSL\\wsl.exe"` → `"bash"` (matches `wsl` rule)
- `"C:\\Program Files\\Ubuntu\\ubuntu.exe"` → `"bash"` (matches `ubuntu` rule)
- `"C:\\Program Files\\fish\\fish.exe"` → `"fish"`
- `"C:\\nushell\\nu.exe"` → `"nushell"` (matches `nu` rule)
- `"C:\\xonsh\\xonsh.exe"` → `"xonsh"`
- `"D:\\tools\\custom-shell.exe"` → `null`
- `null` → `null`

**`IconLoaderTest`**:
- `load_rasterPng_returnsImage`
- `load_rasterJpeg_returnsImage`
- `load_ico_returnsImage` (uses a tiny test fixture in `src/test/resources/fixtures/`)
- `load_svg_returnsImage` (Batik render)
- `load_missingFile_throwsIOException`
- `load_unsupportedFormat_returnsNull` (e.g. `.txt`)
- `load_samePathTwice_returnsSameCachedInstance` (cache hit assertion)
- `load_moreThan64Paths_evictsEldest` (cache eviction)

**`ShellIconResolverTest`**:
- `getIcon_userIconPathSet_loadsThatFile`
- `getIcon_userIconPathSetButFileMissing_fallsBackToDefault`
- `getIcon_userIconPathNull_usesShellPathMatch`
- `getIcon_unknownShell_returnsDefaultIcon`
- `getIcon_defaultIconMissing_returnsEmptyImageIcon`

**`TagNodeTest`** (extend existing):
- `folder_equalityIsIdBased_ignoringExpanded` — two folders with same id,
  different `expanded`, are equal.

**`TagConfigStoreTest`** (extend existing):
- `load_v1File_defaultsAllFoldersToExpanded`
- `load_v2File_preservesExpandedFlag` (round-trip)
- `save_v2File_alwaysWritesExpandedField` (incl. `false` for shells)

**`TagTreeModelTest`** (extend existing):
- `moveNode_withinSameFolder_insertsAtIndex`
- `moveNode_acrossFolders_firesRemoveAndInsert`
- `moveNode_sameIdAndParentAndIndex_isNoOp`

**`FolderIconCellRendererTest`** (extend existing):
- `shellRenderer_usesShellIconResolver` — construct a resolver with a
  fake `IconLoader`, assert the returned icon matches.

### 7.2 What we deliberately do NOT test

- `TagTreeTransferHandler` end-to-end via JUnit — Swing DnD gesture
  timing is notoriously hard to drive from JUnit; validation
  predicates are unit-tested at the model level, the wiring is
  covered by manual integration.
- Batik's SVG parsing (library responsibility).
- Commons Imaging's ICO parsing (library responsibility).
- `ImageIO` format plugins (JDK's responsibility).
- OS-level cursor rendering (manual integration only).

### 7.3 Manual integration test (additions to README)

After the existing test plan (steps 1–5), add:

6. Click a folder's ▶ handle to collapse it. Restart the app → folder is
   still collapsed.
7. Click again to expand. Restart → folder is still expanded.
8. Drag a Shell into a sibling Folder → Shell appears as a child of
   that folder; the destination folder is auto-expanded.
9. Drag a Shell above another Shell in the same folder → reorders as
   siblings.
10. Drag a Shell into one of its own ancestors (i.e. its containing
    folder or any higher folder) → no-op; cursor says "not allowed"
    during drag.
11. Drag a Shell onto a Shell → no-op.
12. Add a new Shell with no `iconPath` and
    `shellPath = C:\Program Files\Git\bin\bash.exe` → bash icon
    appears automatically.
13. Edit that Shell and set `iconPath` to an arbitrary PNG, JPEG, ICO,
    and SVG file (one at a time) → custom icon replaces the default.
    Set it to a non-existent path → falls back to the default icon
    with a warning in the log.

## 8. Out of Scope

- Multi-selection drag (Shift/Ctrl+click to drag several nodes at once).
- Cross-window drag (dragging into another `local-term-java` window).
- External-app drops (dragging a file from Explorer onto the tree).
- Custom shell-name rules (user-defined mappings beyond the substring
  list above).
- HiDPI variants of the default icons beyond the single vector SVG
  (rasterised at the L&F's folder-icon size at lookup time).
- Drag-and-drop keyboard alternatives (Alt+Arrow to move up/down).
- Animated feedback during drag (e.g. ghost outline).
- Custom image format support beyond PNG/JPEG/BMP/GIF/ICO/SVG.

## 9. Maven Setup

### 9.1 `pom.xml` additions

```xml
<dependency>
  <groupId>org.apache.commons</groupId>
  <artifactId>commons-imaging</artifactId>
  <version>1.0.0-alpha6</version>
</dependency>
<dependency>
  <groupId>org.apache.xmlgraphics</groupId>
  <artifactId>batik-svggen</artifactId>
  <version>1.18</version>
</dependency>
<dependency>
  <groupId>org.apache.xmlgraphics</groupId>
  <artifactId>batik-rasterizer</artifactId>
  <version>1.18</version>
</dependency>
```

(If individual module wiring gets fiddly, fall back to
`batik-all` 1.18.)

### 9.2 New resources

`src/main/resources/shell-icons/` — 12 SVGs (64×64 viewBox) plus a
generic `default.svg`, committed to the repo and synced from
`D:\eclipse-workspace\local_terminal\src\renderer\assets\shell-icons\`:

```
bash.svg    cmd.svg    dash.svg   fish.svg   ksh.svg    nushell.svg
powershell.svg pwsh.svg sh.svg     tcsh.svg   xonsh.svg  zsh.svg
default.svg
```

### 9.3 Icon sync script

`scripts/convert-shell-icons.bat` — one-shot `xcopy` that syncs the 12
SVGs from the sibling project into `src/main/resources/shell-icons/`.
Not part of `mvn package`; run only after a sibling project change.
Documented in README.

## 10. Documentation Updates

- **README.md** — add 5 lines describing the icon asset location and
  supported user-supplied formats.
- **README.md** — add manual integration test steps 6–13.
- **`docs/superpowers/specs/2026-07-13-java-terminal-manager-design.md`** —
  Section 10 ("Out of Scope v1"): remove the
  "Drag-and-drop reordering of tree nodes" item (now in scope); add a
  short pointer to this spec.

## 11. Repository Layout (post-change)

```
D:\eclipse-workspace\local_term_java\
├── pom.xml                                  (+ 3 deps)
├── README.md                                (updated)
├── scripts\
│   ├── install-jediterm.bat
│   └── convert-icons.bat                    (new)
├── docs\
│   └── superpowers\
│       └── specs\
│           ├── 2026-07-13-java-terminal-manager-design.md
│           └── 2026-07-15-tag-tree-expansion-drag-default-icons-design.md   (this file)
└── src\
    ├── main\
    │   ├── java\local\term\
    │   │   ├── App.java
    │   │   ├── MainFrame.java
    │   │   ├── TagNode.java                  (+ expanded on Folder)
    │   │   ├── TagTreeModel.java             (+ setExpanded, moveNode)
    │   │   ├── TagConfigStore.java           (bump version, +expanded on DTO)
    │   │   ├── TagTreePanel.java             (wires DnD, expansion listener, resolver)
    │   │   ├── FolderIconCellRenderer.java   (uses ShellIconResolver)
    │   │   ├── ShellIconResolver.java        (new)
    │   │   ├── IconLoader.java               (new)
    │   │   ├── ShellNameExtractor.java       (new)
    │   │   ├── TagTreeTransferHandler.java   (new)
    │   │   ├── FolderEditorDialog.java
    │   │   ├── ShellEditorDialog.java
    │   │   ├── TerminalLauncher.java
    │   │   ├── TerminalSession.java
    │   │   ├── TerminalPanel.java
    │   │   └── AppSettings.java + others…
    │   └── resources\
    │       └── shell-icons\                  (new — 12 SVGs + default.svg)
    └── test\
        ├── java\local\term\
        │   ├── ShellNameExtractorTest.java   (new)
        │   ├── IconLoaderTest.java           (new)
        │   ├── ShellIconResolverTest.java    (new)
        │   ├── TagNodeTest.java              (extended)
        │   ├── TagConfigStoreTest.java       (extended)
        │   ├── TagTreeModelTest.java         (extended)
        │   ├── TagTreePanelTest.java         (extended — renderer)
        │   └── …existing tests
        └── resources\
            └── fixtures\                     (new — tiny .ico, .svg for IconLoaderTest)
```

## 12. Open Questions

None. All design decisions resolved during brainstorming phase.
