package local.term;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.event.ChangeEvent;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Modal settings dialog. Lets the user pick four font slots that drive the
 * terminal fallback chain:
 *
 * <ul>
 *   <li><b>Terminal</b> — the primary/Latin font used for ASCII cells.</li>
 *   <li><b>CJK</b> — the font used to render Chinese / Japanese / Korean.</li>
 *   <li><b>Symbol</b> — the font used for box-drawing, arrows, math,
 *       dingbats like ✔ ✗. Text-presentation symbol fonts are preferred here
 *       because they render these glyphs close to one cell wide, avoiding
 *       the opaque background overflow that truncated ✔ in the original
 *       chain.</li>
 *   <li><b>Emoji</b> — the font used for color emoji (🚀 ✨ ⭐).</li>
 * </ul>
 *
 * <p>Each slot has an "(Auto)" option meaning "let the runtime pick the
 * best installed font for this category". Users who configure NONE of
 * the four slots therefore get the original auto-detected chain.
 *
 * <p>Each slot's combo only lists installed fonts that support the slot's
 * category (e.g. the CJK combo shows fonts where {@code canDisplayUpTo("中")
 * == -1}), so a user can't pick a Latin-only font for the CJK slot and
 * silently get tofu.
 *
 * <p>Each row has a {@link CompositeFontLabel} preview rendered with the
 * same per-run font selection the terminal pane uses, so the user sees
 * what each slot's choice will look like at runtime.
 */
public class SettingsDialog extends JDialog {
  private static final int PREVIEW_SIZE = 14;

  /** Sentinel "auto-detect" entry prepended to each combo. */
  private static final String AUTO_LABEL = "(Auto)";

  private final JComboBox<String> primaryCombo;
  private final JComboBox<String> cjkCombo;
  private final JComboBox<String> symbolCombo;
  private final JComboBox<String> emojiCombo;
  /**
   * Font size spinner. Changes are committed live (see {@link #sizeListener})
   * so the terminal pane resizes the moment the user adjusts the value, no
   * OK press required.
   */
  private final JSpinner sizeSpinner;

  private final CompositeFontLabel primarySample;
  private final CompositeFontLabel cjkSample;
  private final CompositeFontLabel symbolSample;
  private final CompositeFontLabel emojiSample;

  private boolean okPressed;
  /** Last size we already fired via the live listener; used to dedupe. */
  private int lastFiredSize = -1;
  /**
   * Live callback fired when the user adjusts the size spinner. Set by
   * the caller (MainFrame) so font changes apply to every open terminal
   * session immediately, with persistence deferred to the OK path.
   */
  private IntConsumer onSizeChanged;

  public SettingsDialog(JFrame owner, AppSettings settings) {
    this(owner, settings, null);
  }

  /**
   * Full-control constructor.
   *
   * @param onSizeChanged invoked with the new font size every time the
   *                      user changes the spinner. Pass {@code null} to
   *                      disable live apply (size changes only persist
   *                      on OK). The same value is never fired twice in
   *                      a row — duplicate spinner events (the editor
   *                      fires on every keystroke) collapse into a single
   *                      notification.
   */
  public SettingsDialog(JFrame owner, AppSettings settings, IntConsumer onSizeChanged) {
    super(owner, "Settings", true);
    setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
    setLayout(new BorderLayout(8, 8));
    this.onSizeChanged = onSizeChanged;

    // ---- Build the four combo boxes ----
    // Each combo: [AUTO_LABEL, ...installed fonts supporting that category].
    List<String> primaryFamilies = FontUtils.monospaceFamilies();
    List<String> cjkFamilies = FontUtils.cjkFamilies();
    List<String> symbolFamilies = FontUtils.symbolFamilies();
    List<String> emojiFamilies = FontUtils.emojiFamilies();

    primaryCombo = buildCombo(primaryFamilies, settings.terminalFontFamily());
    cjkCombo = buildCombo(cjkFamilies, settings.cjkFontFamily());
    symbolCombo = buildCombo(symbolFamilies, settings.symbolFontFamily());
    emojiCombo = buildCombo(emojiFamilies, settings.emojiFontFamily());

    // Size spinner — same bounds as AppSettings clamps to. DefaultModel so
    // the editor accepts free-form numeric input but the spinner buttons
    // step by 1; users with accessibility needs can type 12 directly.
    sizeSpinner = new JSpinner(new SpinnerNumberModel(
        settings.terminalFontSize(),
        AppSettings.MIN_FONT_SIZE,
        AppSettings.MAX_FONT_SIZE,
        1));
    // Spinner fires ChangeEvent on every keystroke in the editor, so
    // dedupe against the last size we already notified on. Without
    // this, dragging the value from 14→16 fires 4 change events
    // (14→15, 15→16 and a few committed-text ones) and the panel
    // rebuilds its font chain three times for what the user sees as a
    // single adjustment.
    sizeSpinner.addChangeListener(this::onSizeChangedInternal);

    // ---- Preview labels ----
    // Each preview shows representative characters for its category. The
    // text mixes single-width and double-width glyphs so the user can
    // verify both render at the expected cell width: e.g. the symbol
    // row has ✔ ⚠ ▓ (narrow) plus ┌─┐ │ (also narrow but very tall)
    // and ←→↔ arrows (narrow). The emoji row mixes narrow dingbats like
    // ✔ ⚠ (which the emoji font also covers) with wide color emoji
    // like 🚀 ✨ 🎉 ⭐ so the user can see both render in color and at
    // the right width.
    primarySample = new CompositeFontLabel("你好 abcdefg 0123 ▓▒░");
    cjkSample = new CompositeFontLabel("你好世界 中文测试 日本語 한글");
    symbolSample = new CompositeFontLabel("✔ ✗ ⚠ ▓ ▒ ┌─┐ │ ← → ↔ π ∑");
    emojiSample = new CompositeFontLabel("✔ ⚠ 🚀 ✨ ⭐ 🔥 🎉");

    // Make the preview labels tall enough to fit color-emoji glyphs.
    // Color emoji are typically larger than the line height of a regular
    // font at the same point size, so a default JLabel height clips them.
    // The added padding (em-height + emoji overhang) keeps everything
    // visible regardless of which fallback the label ends up using.
    java.awt.Dimension previewSize = new java.awt.Dimension(0, PREVIEW_SIZE * 3);
    for (CompositeFontLabel lbl : new CompositeFontLabel[]{
        primarySample, cjkSample, symbolSample, emojiSample}) {
      lbl.setHorizontalAlignment(SwingConstants.LEFT);
      lbl.setBorder(BorderFactory.createEtchedBorder());
      lbl.setPreferredSize(previewSize);
    }

    primaryCombo.addActionListener(e -> refreshPreview(primaryCombo, primarySample,
        () -> null, PreviewCategory.PRIMARY, "你好 abcdefg 0123 ▓▒░"));
    cjkCombo.addActionListener(e -> refreshPreview(cjkCombo, cjkSample,
        () -> FontUtils.findTerminalCjkFallback(PREVIEW_SIZE),
        PreviewCategory.CJK, "你好世界 中文测试 日本語 한글"));
    symbolCombo.addActionListener(e -> refreshPreview(symbolCombo, symbolSample,
        () -> FontUtils.findGeneralSymbolFont(PREVIEW_SIZE),
        PreviewCategory.SYMBOL, "✔ ✗ ⚠ ▓ ▒ ┌─┐ │ ← → ↔ π ∑"));
    emojiCombo.addActionListener(e -> refreshPreview(emojiCombo, emojiSample,
        () -> FontUtils.findEmojiFont(PREVIEW_SIZE),
        PreviewCategory.EMOJI, "✔ ⚠ 🚀 ✨ ⭐ 🔥 🎉"));

    // ---- Layout ----
    JPanel body = new JPanel(new GridBagLayout());
    GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(6, 8, 6, 8);
    c.anchor = GridBagConstraints.WEST;

    int row = 0;
    // Size row — no preview (the spinner shows its own numeric value),
    // so it sits on a single grid row unlike the four font-slot rows
    // which occupy two grid rows (combo + preview label).
    c.gridx = 0; c.gridy = row;
    c.weightx = 0; c.fill = GridBagConstraints.NONE;
    body.add(new JLabel("Terminal font size:"), c);
    c.gridx = 1; c.weightx = 0; c.fill = GridBagConstraints.NONE;
    c.anchor = GridBagConstraints.WEST;
    body.add(sizeSpinner, c);
    c.anchor = GridBagConstraints.WEST;
    row++;

    row = addRow(body, c, row, "Terminal font:", primaryCombo, primarySample);
    row = addRow(body, c, row, "CJK font (Chinese / Japanese / Korean):",
        cjkCombo, cjkSample);
    row = addRow(body, c, row, "Symbol font (✔ ✗ arrows box-drawing):",
        symbolCombo, symbolSample);
    row = addRow(body, c, row, "Emoji font (color emoji):",
        emojiCombo, emojiSample);

    add(body, BorderLayout.CENTER);
    add(buildButtonBar(), BorderLayout.SOUTH);

    // Initial preview render — each slot uses just its own font as the
    // primary and the auto-detected font for the OTHER slots as fallbacks,
    // so the user sees a representative "if I picked this font, what
    // would non-ASCII characters look like" preview per row.
    refreshPreview(primaryCombo, primarySample,
        () -> null, PreviewCategory.PRIMARY, "你好 abcdefg 0123 ▓▒░");
    refreshPreview(cjkCombo, cjkSample,
        () -> FontUtils.findTerminalCjkFallback(PREVIEW_SIZE),
        PreviewCategory.CJK, "你好世界 中文测试 日本語 한글");
    refreshPreview(symbolCombo, symbolSample,
        () -> FontUtils.findGeneralSymbolFont(PREVIEW_SIZE),
        PreviewCategory.SYMBOL, "✔ ✗ ⚠ ▓ ▒ ┌─┐ │ ← → ↔ π ∑");
    refreshPreview(emojiCombo, emojiSample,
        () -> FontUtils.findEmojiFont(PREVIEW_SIZE),
        PreviewCategory.EMOJI, "✔ ⚠ 🚀 ✨ ⭐ 🔥 🎉");

    pack();
    setSize(620, getHeight());
    setLocationRelativeTo(owner);
  }

  /**
   * Build a font combo with an "(Auto)" sentinel prepended to the list of
   * families supporting the slot's category. {@code currentSelection} of
   * {@code null} selects Auto; otherwise the combo selects the family if
   * present (a missing configured family falls back to Auto rather than
   * silently dropping the user's choice).
   */
  private static JComboBox<String> buildCombo(List<String> families, String currentSelection) {
    java.util.Vector<String> items = new java.util.Vector<>(families.size() + 1);
    items.add(AUTO_LABEL);
    items.addAll(families);
    JComboBox<String> combo = new JComboBox<>(items);
    combo.setEditable(false);
    if (currentSelection != null && families.contains(currentSelection)) {
      combo.setSelectedItem(currentSelection);
    } else {
      combo.setSelectedItem(AUTO_LABEL);
    }
    return combo;
  }

  /**
   * Add a single row to the dialog body: a title label, the font combo,
   * and the preview label below. Each row occupies TWO grid rows
   * (label+combo on row N, preview on row N+1), so this method returns
   * the next available row index.
   *
   * <p>The title label and combo share row N (title in column 0, combo
   * fills column 1); the preview label spans both columns on row N+1.
   */
  private static int addRow(JPanel body, GridBagConstraints c, int row,
                            String title, JComboBox<String> combo,
                            CompositeFontLabel preview) {
    c.gridx = 0; c.gridy = row;
    c.weightx = 0; c.fill = GridBagConstraints.NONE;
    body.add(new JLabel(title), c);

    c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
    body.add(combo, c);

    c.gridx = 0; c.gridy = row + 1; c.gridwidth = 2;
    c.weightx = 0; c.fill = GridBagConstraints.HORIZONTAL;
    body.add(preview, c);

    // Reset gridwidth for the next call.
    c.gridwidth = 1;
    return row + 2;
  }

  /**
   * Refresh one preview. The preview's primary font is the slot's
   * resolved font; the fallback chain is empty for non-primary slots so
   * the preview honestly shows what the user's chosen font can render
   * (tofu where it can't, rather than a silent rescue by a different
   * category's auto-detected font). Only the PRIMARY slot needs a
   * CJK/symbol/emoji fallback chain — a Latin monospace primary
   * legitimately needs those to render anything beyond ASCII.
   *
   * <p>Only the passed-in label is touched — the other three previews
   * are left alone, so changing one combo does NOT redraw the others.
   */
  private void refreshPreview(JComboBox<String> combo, CompositeFontLabel sample,
                              java.util.function.Supplier<Font> autoDefault,
                              PreviewCategory category, String sampleText) {
    Font slotFont = resolveSlot(combo, autoDefault);
    List<Font> fallbacks = category.fallbacksFor(PREVIEW_SIZE);

    sample.setText(sampleText);
    sample.setFont(slotFont != null ? slotFont
        : new Font(Font.MONOSPACED, Font.PLAIN, PREVIEW_SIZE));
    sample.setFallbackFonts(fallbacks);
  }

  /**
   * Which category a preview represents. Non-primary previews have an
   * EMPTY fallback chain so they honestly show what the slot's chosen
   * font can render — picking a CJK-incapable font in the CJK slot
   * turns the preview into tofu, which is the desired feedback. Only
   * the PRIMARY slot has fallbacks because a Latin-only monospace
   * primary legitimately needs CJK/symbol/emoji to render non-ASCII.
   */
  private enum PreviewCategory {
    PRIMARY {
      @Override List<Font> fallbacksFor(int size) {
        Font cjk = FontUtils.findTerminalCjkFallback(size);
        Font symbol = FontUtils.findGeneralSymbolFont(size);
        Font emoji = FontUtils.findEmojiFont(size);
        return buildFallbackList(cjk, symbol, emoji);
      }
    },
    CJK {
      @Override List<Font> fallbacksFor(int size) {
        return List.of();
      }
    },
    SYMBOL {
      @Override List<Font> fallbacksFor(int size) {
        return List.of();
      }
    },
    EMOJI {
      @Override List<Font> fallbacksFor(int size) {
        return List.of();
      }
    };

    abstract List<Font> fallbacksFor(int size);
  }

  /**
   * Resolve a combo to a Font. Returns {@code null} when Auto is selected
   * AND {@code autoDefault} returns null (no font at all installed for
   * the category) — caller then falls back to a default monospace so the
   * preview still renders something.
   */
  private static Font resolveSlot(JComboBox<String> combo,
                                  java.util.function.Supplier<Font> autoDefault) {
    Object sel = combo.getSelectedItem();
    if (sel == null || AUTO_LABEL.equals(sel)) {
      return autoDefault.get();
    }
    return new Font((String) sel, Font.PLAIN, PREVIEW_SIZE);
  }

  /**
   * Build a non-null fallback list in (CJK, Symbol, Emoji) order, skipping
   * nulls. Mirrors the runtime chain order in
   * {@link CompositeFontJediTermWidget#buildFallbackChain}.
   */
  private static List<Font> buildFallbackList(Font cjk, Font symbol, Font emoji) {
    List<Font> list = new ArrayList<>(3);
    if (cjk != null) list.add(cjk);
    if (symbol != null) list.add(symbol);
    if (emoji != null) list.add(emoji);
    return list;
  }

  private JPanel buildButtonBar() {
    JButton ok = new JButton("OK");
    ok.addActionListener(e -> { okPressed = true; dispose(); });
    JButton cancel = new JButton("Cancel");
    cancel.addActionListener(e -> dispose());
    JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    bar.add(ok);
    bar.add(cancel);
    getRootPane().setDefaultButton(ok);
    return bar;
  }

  /** True if the user clicked OK; false on cancel / close. */
  public boolean isOkPressed() { return okPressed; }

  /** Primary/Latin font family, or {@code null} for auto-detect. */
  public String selectedFontFamily() { return selectedFrom(primaryCombo); }

  /**
   * Currently selected font size in points. Always within
   * {@link AppSettings#MIN_FONT_SIZE}..{@link AppSettings#MAX_FONT_SIZE}
   * — the spinner model clamps invalid inputs.
   */
  public int selectedFontSize() {
    return (Integer) sizeSpinner.getValue();
  }

  /**
   * Spinner change handler. Deduplicates repeated events for the same
   * value (the spinner fires on every keystroke in its editor), and
   * invokes the live callback when one is registered.
   */
  private void onSizeChangedInternal(ChangeEvent e) {
    int size = selectedFontSize();
    if (size == lastFiredSize) return;
    lastFiredSize = size;
    if (onSizeChanged != null) onSizeChanged.accept(size);
  }

  /** CJK fallback font family, or {@code null} for auto-detect. */
  public String selectedCjkFamily() { return selectedFrom(cjkCombo); }

  /** Symbol fallback font family, or {@code null} for auto-detect. */
  public String selectedSymbolFamily() { return selectedFrom(symbolCombo); }

  /** Emoji fallback font family, or {@code null} for auto-detect. */
  public String selectedEmojiFamily() { return selectedFrom(emojiCombo); }

  private static String selectedFrom(JComboBox<String> combo) {
    Object sel = combo.getSelectedItem();
    if (sel == null || AUTO_LABEL.equals(sel)) return null;
    return (String) sel;
  }
}
