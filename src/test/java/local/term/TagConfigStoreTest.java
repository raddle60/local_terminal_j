package local.term;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TagConfigStoreTest {
  private Path tempDir;
  private Path configFile;
  private FileChannel heldChannel;

  @BeforeEach
  void setUp() throws IOException {
    tempDir = Files.createTempDirectory("tag-config-test-");
    configFile = tempDir.resolve("config.json");
  }

  @AfterEach
  void tearDown() throws IOException {
    if (heldChannel != null && heldChannel.isOpen()) {
      try { heldChannel.close(); } catch (IOException ignored) {}
    }
    Files.walk(tempDir).sorted((a, b) -> b.getNameCount() - a.getNameCount())
        .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
  }

  /**
   * Sample tree of shape:
   * root
   * └─ folder
   *    └─ leaf (shell)
   */
  private TagNode sampleTree() {
    TagNode.Shell leaf = new TagNode.Shell(UUID.randomUUID(), "leaf",
        "C:\\Windows\\System32\\cmd.exe", null, "C:\\");
    TagNode.Folder folder = new TagNode.Folder(UUID.randomUUID(), "folder",
        List.of(leaf));
    return new TagNode.Folder(new UUID(0L, 0L), "root", List.of(folder));
  }

  @Test
  void save_thenLoad_roundTripPreservesTreeExactly() throws IOException {
    TagConfigStore store = new TagConfigStore(configFile);
    TagNode tree = sampleTree();

    store.save(tree);
    TagNode loaded = store.load();

    assertEquals(tree, loaded);
  }

  @Test
  void load_missingFile_returnsEmptyRoot() {
    TagConfigStore store = new TagConfigStore(configFile);

    TagNode loaded = store.load();

    assertNotNull(loaded);
    assertTrue(loaded instanceof TagNode.Folder);
    assertEquals(0, loaded.children().size());
  }

  @Test
  void load_missingFile_createsEmptyConfigFile() {
    TagConfigStore store = new TagConfigStore(configFile);
    store.load();

    assertTrue(Files.exists(configFile));
  }

  @Test
  void load_corruptFile_createsBackupAndReturnsEmptyRoot() throws IOException {
    Files.writeString(configFile, "this is not { valid json");
    TagConfigStore store = new TagConfigStore(configFile);

    TagNode loaded = store.load();

    assertNotNull(loaded);
    assertEquals(0, loaded.children().size());
    long backupCount = Files.list(tempDir)
        .filter(p -> p.getFileName().toString().startsWith("config.json.bak."))
        .count();
    assertEquals(1, backupCount);
  }

  @Test
  void save_isAtomic_noPartialFileOnFailure() throws IOException {
    TagConfigStore store = new TagConfigStore(configFile);
    TagNode tree = sampleTree();

    // Pre-populate the target file with known content.
    String original = "{\"version\":1,\"tags\":[]}";
    Files.writeString(configFile, original);

    // Hold the target file open so Files.move with ATOMIC_MOVE cannot replace it.
    // On Windows, MoveFileEx with MOV_REPLACE_EXISTING fails if the destination
    // is open, which forces the atomic rename in save() to fail *after* the
    // .tmp file has been written but *before* the target is replaced.
    heldChannel = FileChannel.open(configFile, StandardOpenOption.WRITE);

    // The atomic move must fail because the target file is held open.
    assertThrows(IOException.class, () -> store.save(tree));

    // The .tmp file should exist (write to it succeeded), but the original
    // target file must be untouched — proving the save was atomic: either the
    // old content or the new content is present, never a mix or partial write.
    Path tmp = configFile.resolveSibling(configFile.getFileName() + ".tmp");
    assertTrue(Files.exists(tmp), "expected .tmp file to exist after failed atomic move");
    assertEquals(original, Files.readString(configFile),
        "original target file must be unchanged after failed atomic save");
  }

  /**
   * Files written before the folder/shell split have no {@code type}
   * discriminator. We no longer infer the kind from structural fields —
   * a missing {@code type} is treated as corrupt: the file is renamed
   * with a {@code .bak.&lt;ts&gt;} suffix and the user gets a fresh empty root.
   */
  @Test
  void load_legacyJsonWithoutType_backsUpAndStartsEmpty() throws IOException {
    String legacy = "{\n"
        + "  \"version\": 1,\n"
        + "  \"tags\": [\n"
        + "    {\n"
        + "      \"id\": \"00000000-0000-0000-0000-000000000001\",\n"
        + "      \"name\": \"old-folder\",\n"
        + "      \"shellPath\": \"\",\n"
        + "      \"iconPath\": null,\n"
        + "      \"startPath\": \"\",\n"
        + "      \"children\": [\n"
        + "        {\n"
        + "          \"id\": \"00000000-0000-0000-0000-000000000002\",\n"
        + "          \"name\": \"old-shell\",\n"
        + "          \"shellPath\": \"cmd.exe\",\n"
        + "          \"iconPath\": null,\n"
        + "          \"startPath\": \"C:\\\\\",\n"
        + "          \"children\": []\n"
        + "        }\n"
        + "      ]\n"
        + "    }\n"
        + "  ]\n"
        + "}\n";
    Files.writeString(configFile, legacy);
    TagConfigStore store = new TagConfigStore(configFile);

    TagNode loaded = store.load();

    assertTrue(loaded instanceof TagNode.Folder);
    assertEquals(0, loaded.children().size(),
        "missing type → corrupt → empty root, no inference");
    long backupCount = Files.list(tempDir)
        .filter(p -> p.getFileName().toString().startsWith("config.json.bak."))
        .count();
    assertEquals(1, backupCount);
  }

  /**
   * Wrong-value {@code type} is also corrupt — only {@code "folder"} and
   * {@code "shell"} are accepted. Verifies we don't silently coerce.
   */
  @Test
  void load_unknownTypeValue_backsUpAndStartsEmpty() throws IOException {
    String bad = "{\n"
        + "  \"version\": 1,\n"
        + "  \"tags\": [\n"
        + "    {\n"
        + "      \"type\": \"link\",\n"
        + "      \"id\": \"00000000-0000-0000-0000-000000000001\",\n"
        + "      \"name\": \"bad\",\n"
        + "      \"shellPath\": \"\",\n"
        + "      \"iconPath\": null,\n"
        + "      \"startPath\": \"\",\n"
        + "      \"children\": []\n"
        + "    }\n"
        + "  ]\n"
        + "}\n";
    Files.writeString(configFile, bad);
    TagConfigStore store = new TagConfigStore(configFile);

    TagNode loaded = store.load();

    assertEquals(0, loaded.children().size());
  }

  /**
   * Round-trip check that {@code type} is actually written on disk. Catches
   * regressions where the TagNode model's {@code type()} is omitted from
   * the DTO.
   */
  @Test
  void save_writesTypeDiscriminatorForEveryNode() throws IOException {
    TagConfigStore store = new TagConfigStore(configFile);
    TagNode.Shell leaf = new TagNode.Shell(UUID.randomUUID(), "leaf",
        "cmd.exe", null, "C:\\");
    TagNode.Folder folder = new TagNode.Folder(UUID.randomUUID(), "folder",
        List.of(leaf));
    TagNode root = new TagNode.Folder(new UUID(0L, 0L), "root", List.of(folder));

    store.save(root);
    String json = Files.readString(configFile);

    assertTrue(json.contains("\"type\" : \"folder\""),
        "folder nodes must serialise with type=folder; got:\n" + json);
    assertTrue(json.contains("\"type\" : \"shell\""),
        "shell nodes must serialise with type=shell; got:\n" + json);
  }

  @org.junit.jupiter.api.Test
  void load_v1File_defaultsAllFoldersToExpanded() throws Exception {
    // Hand-craft a v1-shaped JSON (no "type" discriminator issues; type is
    // present, "expanded" is absent) and confirm parseNode defaults to true.
    String v1Json = """
        {
          "version": 1,
          "tags": [
            {
              "id": "00000000-0000-0000-0000-000000000001",
              "type": "folder",
              "name": "Top",
              "children": []
            }
          ]
        }
        """;
    java.nio.file.Path tmp = java.nio.file.Files.createTempFile("v1config", ".json");
    java.nio.file.Files.writeString(tmp, v1Json);
    try {
      TagConfigStore store = new TagConfigStore(tmp);
      TagNode root = store.load();
      TagNode.Folder top = (TagNode.Folder) root.children().get(0);
      assertTrue(top.expanded(), "v1 file must default expanded=true on load");
    } finally {
      java.nio.file.Files.deleteIfExists(tmp);
    }
  }

  @org.junit.jupiter.api.Test
  void load_v2File_preservesExpandedFlag() throws Exception {
    String v2Json = """
        {
          "version": 2,
          "tags": [
            {
              "id": "00000000-0000-0000-0000-000000000001",
              "type": "folder",
              "name": "Top",
              "expanded": false,
              "children": []
            }
          ]
        }
        """;
    java.nio.file.Path tmp = java.nio.file.Files.createTempFile("v2config", ".json");
    java.nio.file.Files.writeString(tmp, v2Json);
    try {
      TagConfigStore store = new TagConfigStore(tmp);
      TagNode root = store.load();
      TagNode.Folder top = (TagNode.Folder) root.children().get(0);
      assertFalse(top.expanded(), "v2 file must preserve expanded=false on load");
    } finally {
      java.nio.file.Files.deleteIfExists(tmp);
    }
  }

  @org.junit.jupiter.api.Test
  void save_v2File_alwaysWritesExpandedField() throws Exception {
    // Build a tree with a folder (expanded=false) containing a shell,
    // round-trip through save+load, and verify the on-disk JSON includes
    // "expanded" on every node — even the shell, where it's always false.
    UUID folderId = new UUID(0L, 1L);
    UUID shellId = new UUID(0L, 2L);
    TagNode.Folder folder = new TagNode.Folder(
        folderId, "F", List.of(), false);
    TagNode.Shell shell = new TagNode.Shell(
        shellId, "S", "C:\\bin\\cmd.exe", null, "C:\\");
    TagNode.Folder root = new TagNode.Folder(
        new UUID(0L, 0L), "root", List.of(folder.withChildren(List.of(shell))));

    java.nio.file.Path tmp = java.nio.file.Files.createTempFile("rt", ".json");
    try {
      TagConfigStore store = new TagConfigStore(tmp);
      store.save(root);
      String json = java.nio.file.Files.readString(tmp);
      assertTrue(json.contains("\"expanded\" : false"),
          "shell must write expanded=false: " + json);
      assertTrue(json.contains("\"version\" : 2"),
          "save must write version=2: " + json);
      // Round-trip preserves the folder's expansion flag.
      TagNode reloaded = store.load();
      TagNode.Folder reloadedFolder =
          (TagNode.Folder) reloaded.children().get(0);
      assertFalse(reloadedFolder.expanded(),
          "round-trip must preserve expanded=false on folder");
    } finally {
      java.nio.file.Files.deleteIfExists(tmp);
    }
  }

  @org.junit.jupiter.api.Test
  void roundTrip_preservesAutoScript() throws Exception {
    java.nio.file.Path tmp = java.nio.file.Files.createTempFile("rt-as", ".json");
    try {
      UUID shellId = new UUID(0L, 99L);
      AutoScript script = new AutoScript(7500, java.util.List.of(
          new Step("login:", "myuser"),
          new Step("Password*", "mypass")));
      TagNode.Shell shell = new TagNode.Shell(shellId, "S", "cmd.exe", null, null, "C:\\", script);
      TagNode root = new TagNode.Folder(new UUID(0L, 0L), "root",
          java.util.List.of(new TagNode.Folder(new UUID(0L, 1L), "F",
              java.util.List.of(shell))));

      TagConfigStore store = new TagConfigStore(tmp);
      store.save(root);
      TagNode reloaded = store.load();

      TagNode.Folder reloadedFolder = (TagNode.Folder) reloaded.children().get(0);
      TagNode.Shell reloadedShell = (TagNode.Shell) reloadedFolder.children().get(0);
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

  @org.junit.jupiter.api.Test
  void roundTrip_preservesDisabledAutoScript() throws Exception {
    // The save branch: user has steps but unchecked "enable" — script
    // persists with steps preserved and enabled=false so re-opening the
    // editor shows the rows again, ready to be re-enabled.
    java.nio.file.Path tmp = java.nio.file.Files.createTempFile("rt-disabled", ".json");
    try {
      UUID shellId = new UUID(0L, 100L);
      AutoScript script = new AutoScript(8000, java.util.List.of(
          new Step("login:", "u"),
          new Step("Password:", "p")), false);
      TagNode.Shell shell = new TagNode.Shell(shellId, "S", "cmd.exe", null, null, "C:\\", script);
      TagNode root = new TagNode.Folder(new UUID(0L, 0L), "root",
          java.util.List.of(shell));

      TagConfigStore store = new TagConfigStore(tmp);
      store.save(root);
      TagNode reloaded = store.load();
      TagNode.Shell reloadedShell = (TagNode.Shell) reloaded.children().get(0);

      assertNotNull(reloadedShell.autoScript(),
          "disabled script must still persist (non-null so steps survive)");
      assertFalse(reloadedShell.autoScript().enabled(),
          "enabled flag must round-trip as false");
      assertEquals(2, reloadedShell.autoScript().steps().size(),
          "steps must be preserved when the script is disabled");
    } finally {
      java.nio.file.Files.deleteIfExists(tmp);
    }
  }

  @org.junit.jupiter.api.Test
  void load_v2FileWithAutoScriptMissingEnabled_defaultsToTrue() throws Exception {
    // Backward compatibility: configs written before the `enabled` field
    // existed must keep running their scripts. The parser defaults the
    // missing flag to true, matching the prior semantics where any
    // non-null AutoScript was treated as enabled.
    String json = """
        {
          "version": 2,
          "tags": [
            {
              "type": "shell",
              "id": "00000000-0000-0000-0000-000000000002",
              "name": "legacy-enabled",
              "shellPath": "cmd.exe",
              "startPath": "C:\\\\",
              "expanded": false,
              "children": [],
              "autoScript": {
                "timeoutMs": 5000,
                "steps": [
                  {"waitPattern": "login:", "command": "u"}
                ]
              }
            }
          ]
        }
        """;
    java.nio.file.Path tmp = java.nio.file.Files.createTempFile("legacy-enabled", ".json");
    java.nio.file.Files.writeString(tmp, json);
    try {
      TagNode root = new TagConfigStore(tmp).load();
      TagNode.Shell shell = (TagNode.Shell) root.children().get(0);
      assertNotNull(shell.autoScript());
      assertTrue(shell.autoScript().enabled(),
          "missing enabled in v2 autoScript → defaults true (no behaviour change)");
      assertEquals(1, shell.autoScript().steps().size());
    } finally {
      java.nio.file.Files.deleteIfExists(tmp);
    }
  }

  @Test
  void load_v2FileWithoutShellArgsField_yieldsNull() throws Exception {
    // Existing v2 config files predate shellArgs. Their shells must load
    // with shellArgs=null so launch behaves the same as before.
    String json = """
        {
          "version": 2,
          "tags": [
            {
              "type": "shell",
              "id": "00000000-0000-0000-0000-000000000010",
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
    java.nio.file.Path tmp = java.nio.file.Files.createTempFile("legacy-args", ".json");
    java.nio.file.Files.writeString(tmp, json);
    try {
      TagNode root = new TagConfigStore(tmp).load();
      TagNode.Shell shell = (TagNode.Shell) root.children().get(0);
      assertNull(shell.shellArgs(),
          "missing shellArgs in v2 file → null (no regression for old configs)");
    } finally {
      java.nio.file.Files.deleteIfExists(tmp);
    }
  }
}
