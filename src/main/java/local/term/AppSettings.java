package local.term;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Application-level settings (separate from the tag tree config).
 * Persisted as JSON to {@code ~/.local-term-java/settings.json}.
 *
 * Holds the four font-slot overrides (terminal / CJK / symbol / emoji) plus
 * the terminal font size. A {@code null} font family means "auto-detect from
 * the CJK-capable font preference list". {@link #terminalFontSize()} falls
 * back to {@link #DEFAULT_FONT_SIZE} when unset.
 */
public class AppSettings {
  private static final Logger LOG = LoggerFactory.getLogger(AppSettings.class);
  private static final int CURRENT_VERSION = 2;

  /** Default font size when the field is missing from the persisted file. */
  public static final int DEFAULT_FONT_SIZE = 14;
  /** Default JSplitPane width for the left tree panel (px). */
  public static final int DEFAULT_TREE_PANEL_WIDTH = 260;
  /** Smallest tree-panel width we'll restore to; keeps the tree usable. */
  public static final int MIN_TREE_PANEL_WIDTH = 120;
  /** Largest tree-panel width we'll restore to; leaves room for terminals. */
  public static final int MAX_TREE_PANEL_WIDTH = 800;

  private final Path file;
  private final ObjectMapper mapper;

  private String terminalFontFamily;
  private String cjkFontFamily;
  private String symbolFontFamily;
  private String emojiFontFamily;
  /**
   * Terminal font size in points. {@code null} when not yet set; treated as
   * {@link #DEFAULT_FONT_SIZE}. Bumped live by Settings → applied to every
   * open session immediately, then persisted on OK.
   */
  private Integer terminalFontSize;
  /**
   * Width in pixels of the JSplitPane tree pane (left side). {@code null}
   * means "use {@link #DEFAULT_TREE_PANEL_WIDTH}". Persisted on every drag
   * of the divider so the user's last-chosen width survives across
   * restarts; restored before the JSplitPane shows so the tree expands
   * to the saved width immediately rather than to the constructor's
   * default of 260 px then snapping on the first paint.
   */
  private Integer treePanelWidth;

  public AppSettings(Path file) {
    this.file = file;
    this.mapper = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);
  }

  /** Default settings — auto-detect font. */
  public AppSettings() {
    this(PathsHolder.defaultPath());
  }

  public String terminalFontFamily() { return terminalFontFamily; }
  public void setTerminalFontFamily(String family) { this.terminalFontFamily = family; }

  /**
   * Terminal font size in points; {@link #DEFAULT_FONT_SIZE} when never set.
   * Always returns a positive value — the value passed in is validated in
   * the setter.
   */
  public int terminalFontSize() {
    return terminalFontSize == null ? DEFAULT_FONT_SIZE : terminalFontSize;
  }

  /**
   * Set the terminal font size in points. Values outside {@code [MIN, MAX]}
   * are clamped so a stray dialog input or corrupted settings file can't
   * produce a font size the renderer rejects. {@code null} restores the
   * default.
   */
  public void setTerminalFontSize(Integer size) {
    if (size == null) {
      this.terminalFontSize = null;
    } else {
      this.terminalFontSize = Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, size));
    }
  }

  /** Smallest size the dialog exposes (8 pt — readable but tiny). */
  public static final int MIN_FONT_SIZE = 8;
  /** Largest size the dialog exposes (32 pt — accessibility-friendly). */
  public static final int MAX_FONT_SIZE = 32;

  /** CJK (Chinese/Japanese/Korean) fallback font family; {@code null} = auto-detect. */
  public String cjkFontFamily() { return cjkFontFamily; }
  public void setCjkFontFamily(String family) { this.cjkFontFamily = family; }

  /** Symbol fallback font family (✔ ✗ arrows box-drawing math); {@code null} = auto-detect. */
  public String symbolFontFamily() { return symbolFontFamily; }
  public void setSymbolFontFamily(String family) { this.symbolFontFamily = family; }

  /** Emoji fallback font family; {@code null} = auto-detect. */
  public String emojiFontFamily() { return emojiFontFamily; }
  public void setEmojiFontFamily(String family) { this.emojiFontFamily = family; }

  /**
   * Tree panel JSplitPane width in pixels. Returns {@link #DEFAULT_TREE_PANEL_WIDTH}
   * when the field has never been persisted. The setter clamps to
   * [{@link #MIN_TREE_PANEL_WIDTH}, {@link #MAX_TREE_PANEL_WIDTH}] so a
   * corrupted or absurd value can't render the tree at 5 px or push the
   * terminal entirely off-screen on the next launch.
   */
  public int treePanelWidth() {
    return treePanelWidth == null ? DEFAULT_TREE_PANEL_WIDTH : treePanelWidth;
  }
  public void setTreePanelWidth(Integer width) {
    if (width == null) {
      this.treePanelWidth = null;
    } else {
      this.treePanelWidth = Math.max(MIN_TREE_PANEL_WIDTH,
          Math.min(MAX_TREE_PANEL_WIDTH, width));
    }
  }

  /**
   * Load from disk. If the file is missing or corrupt, returns an empty
   * settings object (auto-detect mode). Failures are logged, not thrown.
   *
   * <p>Missing fields (e.g. a v1 file that only has {@code terminalFontFamily})
   * decode to {@code null} = auto-detect, so older settings files upgrade
   * transparently.
   */
  public static AppSettings load(Path file) {
    AppSettings s = new AppSettings(file);
    if (!Files.exists(file)) return s;
    try {
      var node = s.mapper.readTree(file.toFile());
      JsonNodeHelper helper = new JsonNodeHelper(node);
      s.terminalFontFamily = helper.stringOrNull("terminalFontFamily");
      s.cjkFontFamily = helper.stringOrNull("cjkFontFamily");
      s.symbolFontFamily = helper.stringOrNull("symbolFontFamily");
      s.emojiFontFamily = helper.stringOrNull("emojiFontFamily");
      // terminalFontSize is int (or null) — the helper returns null for missing
      // / null nodes, which the setter accepts as "use default".
      Integer loadedSize = helper.intOrNull("terminalFontSize");
      s.setTerminalFontSize(loadedSize);
      s.setTreePanelWidth(helper.intOrNull("treePanelWidth"));
      return s;
    } catch (IOException e) {
      LOG.warn("Settings file unreadable; using defaults: {}", e.getMessage());
      return s;
    }
  }

  /** Save to disk atomically (write .tmp, then atomic rename). */
  public void save() throws IOException {
    Path parent = file.getParent();
    if (parent != null) Files.createDirectories(parent);
    Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
    SettingsDto dto = new SettingsDto(CURRENT_VERSION, terminalFontFamily,
        cjkFontFamily, symbolFontFamily, emojiFontFamily,
        // Always persist the resolved (clamped) value so the file always
        // contains a valid integer, even when the in-memory field is null.
        terminalFontSize(),
        // Same idea for the tree panel width — persist the clamped value
        // so the JSON always contains a valid width even after a Settings
        // round-trip. The DTO field is `int` (not Integer) precisely
        // because the persisted value must never be null.
        treePanelWidth());
    mapper.writeValue(tmp.toFile(), dto);
    Files.move(tmp, file,
        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  /** Lazy default path holder — avoids a top-level static Path computation. */
  private static final class PathsHolder {
    static Path defaultPath() {
      return java.nio.file.Paths.get(
          System.getProperty("user.home"), ".local-term-java", "settings.json");
    }
  }

  /** Tiny helper: pull a string or null from a JsonNode, suppressing NPEs. */
  private static final class JsonNodeHelper {
    private final com.fasterxml.jackson.databind.JsonNode node;
    JsonNodeHelper(com.fasterxml.jackson.databind.JsonNode node) { this.node = node; }
    String stringOrNull(String field) {
      var v = node.get(field);
      return v == null || v.isNull() ? null : v.asText();
    }
    /**
     * Pull an Integer or null from a JsonNode. Returns null when the field
     * is absent or null in JSON — the size setter treats null as "use
     * default". Non-numeric / fractional values round down via {@code asInt}.
     */
    Integer intOrNull(String field) {
      var v = node.get(field);
      return (v == null || v.isNull() || !v.isNumber()) ? null : v.asInt();
    }
  }

  /** Persisted DTO with a version field for forward compatibility. */
  public record SettingsDto(int version, String terminalFontFamily,
                            String cjkFontFamily, String symbolFontFamily,
                            String emojiFontFamily,
                            int terminalFontSize, int treePanelWidth) {}
}