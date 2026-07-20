package local.term;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AppSettingsTest {

  @Test
  void load_missingFile_returnsDefaults(@TempDir Path tmp) {
    AppSettings s = AppSettings.load(tmp.resolve("nope.json"));
    assertNull(s.terminalFontFamily());
  }

  @Test
  void save_thenLoad_preservesFont(@TempDir Path tmp) throws IOException {
    Path file = tmp.resolve("settings.json");
    AppSettings s = AppSettings.load(file);
    s.setTerminalFontFamily("Microsoft YaHei");
    s.save();

    AppSettings reloaded = AppSettings.load(file);
    assertEquals("Microsoft YaHei", reloaded.terminalFontFamily());
  }

  @Test
  void load_corruptJson_returnsDefaults(@TempDir Path tmp) throws IOException {
    Path file = tmp.resolve("settings.json");
    Files.writeString(file, "{ this is not json");
    AppSettings s = AppSettings.load(file);
    assertNull(s.terminalFontFamily());
  }

  @Test
  void save_createsParentDir(@TempDir Path tmp) throws IOException {
    Path nested = tmp.resolve("a/b/c/settings.json");
    AppSettings s = AppSettings.load(nested);
    s.setTerminalFontFamily("Consolas");
    s.save();
    assertTrue(Files.exists(nested));
  }

  @Test
  void save_thenLoad_preservesAllFourFontSlots(@TempDir Path tmp) throws IOException {
    Path file = tmp.resolve("settings.json");
    AppSettings s = AppSettings.load(file);
    s.setTerminalFontFamily("Consolas");
    s.setCjkFontFamily("Microsoft YaHei Mono");
    s.setSymbolFontFamily("Segoe UI Symbol");
    s.setEmojiFontFamily("Segoe UI Emoji");
    s.save();

    AppSettings reloaded = AppSettings.load(file);
    assertEquals("Consolas", reloaded.terminalFontFamily());
    assertEquals("Microsoft YaHei Mono", reloaded.cjkFontFamily());
    assertEquals("Segoe UI Symbol", reloaded.symbolFontFamily());
    assertEquals("Segoe UI Emoji", reloaded.emojiFontFamily());
  }

  @Test
  void load_v1FileWithOnlyTerminalFont_decodesNewFieldsAsNull(@TempDir Path tmp) throws IOException {
    // Older v1 settings files only have terminalFontFamily; the new slots
    // should decode as null (= auto-detect) so legacy settings upgrade
    // transparently.
    Path file = tmp.resolve("settings.json");
    Files.writeString(file, "{\"version\":1,\"terminalFontFamily\":\"Consolas\"}");
    AppSettings s = AppSettings.load(file);
    assertEquals("Consolas", s.terminalFontFamily());
    assertNull(s.cjkFontFamily());
    assertNull(s.symbolFontFamily());
    assertNull(s.emojiFontFamily());
  }

  @Test
  void load_partialSettingsFile_decodesMissingAsNull(@TempDir Path tmp) throws IOException {
    // Only CJK set, rest missing — should decode CJK + primary, the rest null.
    Path file = tmp.resolve("settings.json");
    Files.writeString(file,
        "{\"version\":2,\"cjkFontFamily\":\"Microsoft YaHei Mono\"}");
    AppSettings s = AppSettings.load(file);
    assertNull(s.terminalFontFamily());
    assertEquals("Microsoft YaHei Mono", s.cjkFontFamily());
    assertNull(s.symbolFontFamily());
    assertNull(s.emojiFontFamily());
  }

  @Test
  void terminalFontSize_defaultsWhenUnset(@TempDir Path tmp) {
    AppSettings s = new AppSettings(tmp.resolve("settings.json"));
    assertEquals(AppSettings.DEFAULT_FONT_SIZE, s.terminalFontSize());
  }

  @Test
  void setTerminalFontSize_clampsBelowMinimum(@TempDir Path tmp) {
    AppSettings s = new AppSettings(tmp.resolve("settings.json"));
    s.setTerminalFontSize(2);
    assertEquals(AppSettings.MIN_FONT_SIZE, s.terminalFontSize());
  }

  @Test
  void setTerminalFontSize_clampsAboveMaximum(@TempDir Path tmp) {
    AppSettings s = new AppSettings(tmp.resolve("settings.json"));
    s.setTerminalFontSize(100);
    assertEquals(AppSettings.MAX_FONT_SIZE, s.terminalFontSize());
  }

  @Test
  void setTerminalFontSize_nullRestoresDefault(@TempDir Path tmp) {
    AppSettings s = new AppSettings(tmp.resolve("settings.json"));
    s.setTerminalFontSize(20);
    s.setTerminalFontSize(null);
    assertEquals(AppSettings.DEFAULT_FONT_SIZE, s.terminalFontSize());
  }

  @Test
  void save_thenLoad_preservesFontSize(@TempDir Path tmp) throws IOException {
    Path file = tmp.resolve("settings.json");
    AppSettings s = AppSettings.load(file);
    s.setTerminalFontSize(18);
    s.save();

    AppSettings reloaded = AppSettings.load(file);
    assertEquals(18, reloaded.terminalFontSize());
  }

  @Test
  void load_v1FileWithoutFontSize_usesDefault(@TempDir Path tmp) throws IOException {
    // Older v1/v2 settings files don't have terminalFontSize — should
    // decode to DEFAULT_FONT_SIZE so legacy settings upgrade transparently.
    Path file = tmp.resolve("settings.json");
    Files.writeString(file, "{\"version\":1,\"terminalFontFamily\":\"Consolas\"}");
    AppSettings s = AppSettings.load(file);
    assertEquals(AppSettings.DEFAULT_FONT_SIZE, s.terminalFontSize());
  }

  @Test
  void save_alwaysPersistsFontSizeEvenWhenNeverSet(@TempDir Path tmp) throws IOException {
    // setTerminalFontSize never called — save should still write the
    // resolved default rather than skipping the field, so reload reads
    // the same value.
    Path file = tmp.resolve("settings.json");
    AppSettings s = AppSettings.load(file);
    s.save();

    AppSettings reloaded = AppSettings.load(file);
    assertEquals(AppSettings.DEFAULT_FONT_SIZE, reloaded.terminalFontSize());
  }

  // -----------------------------------------------------------------
  // Tree panel width persistence
  //
  // MainFrame records every JSplitPane drag (when the tree is visible) to
  // AppSettings.treePanelWidth, then restores it on the next launch via
  // the persisted value. The following pins the public contract that
  // MainFrame depends on: round-trip, default fallback, clamp bounds,
  // null restore.
  // -----------------------------------------------------------------

  @Test
  void treePanelWidth_defaultsWhenUnset(@TempDir Path tmp) {
    AppSettings s = new AppSettings(tmp.resolve("settings.json"));
    assertEquals(AppSettings.DEFAULT_TREE_PANEL_WIDTH, s.treePanelWidth(),
        "Unset width must read back as the documented default");
  }

  @Test
  void treePanelWidth_clampsBelowMinimum(@TempDir Path tmp) {
    // A corrupted file with width=5 (or a future user-driven setter that
    // drops below the floor) must NOT collapse the tree to a useless sliver
    // on the next launch — the setter clamps so MainFrame always sees a
    // usable value.
    AppSettings s = new AppSettings(tmp.resolve("settings.json"));
    s.setTreePanelWidth(5);
    assertEquals(AppSettings.MIN_TREE_PANEL_WIDTH, s.treePanelWidth());
  }

  @Test
  void treePanelWidth_clampsAboveMaximum(@TempDir Path tmp) {
    // Same idea on the high end — a giant persisted width must not eat
    // the whole frame and push the terminal entirely off-screen.
    AppSettings s = new AppSettings(tmp.resolve("settings.json"));
    s.setTreePanelWidth(10_000);
    assertEquals(AppSettings.MAX_TREE_PANEL_WIDTH, s.treePanelWidth());
  }

  @Test
  void treePanelWidth_nullRestoresDefault(@TempDir Path tmp) {
    AppSettings s = new AppSettings(tmp.resolve("settings.json"));
    s.setTreePanelWidth(420);
    s.setTreePanelWidth(null);
    assertEquals(AppSettings.DEFAULT_TREE_PANEL_WIDTH, s.treePanelWidth());
  }

  @Test
  void save_thenLoad_preservesTreePanelWidth(@TempDir Path tmp) throws IOException {
    // The drag-driven path in MainFrame writes 420 here; we verify the
    // file produced by save() reloads to the same value so the user's
    // last-chosen divider position survives restarts.
    Path file = tmp.resolve("settings.json");
    AppSettings s = AppSettings.load(file);
    s.setTreePanelWidth(420);
    s.save();

    AppSettings reloaded = AppSettings.load(file);
    assertEquals(420, reloaded.treePanelWidth());
  }

  @Test
  void load_v1FileWithoutTreePanelWidth_usesDefault(@TempDir Path tmp) throws IOException {
    // Pre-existing settings.json files written before the treePanelWidth
    // feature don't have the field. They must decode to the default
    // rather than throwing or reading zero — otherwise upgrading users
    // would lose their tree entirely on the next launch.
    Path file = tmp.resolve("settings.json");
    Files.writeString(file, "{\"version\":1,\"terminalFontFamily\":\"Consolas\"}");
    AppSettings s = AppSettings.load(file);
    assertEquals(AppSettings.DEFAULT_TREE_PANEL_WIDTH, s.treePanelWidth());
  }
}