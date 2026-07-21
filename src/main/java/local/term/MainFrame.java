package local.term;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JSplitPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Image;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.PropertyChangeEvent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Top-level JFrame: menu bar + JSplitPane of TagTreePanel | TerminalPanel.
 */
public class MainFrame extends JFrame {
  private static final Logger LOG = LoggerFactory.getLogger(MainFrame.class);
  /** Title shown in the JFrame decoration; prepended with {@code "● "} while
   *  any open terminal has produced output in the last second. */
  private static final String BASE_TITLE = "Local Terminal";

  /** Divider width when the tree panel is fully shown. JSplitPane's FlatLaf
   *  default is ~7 px on Windows; we use a literal value so the layout
   *  is reproducible across L&F swaps. */
  private static final int NORMAL_DIVIDER_SIZE = 7;
  /** Divider width when the tree is hidden — 3 px per user spec: a hairline
   *  "≫" cue that the user can drag right to restore the tree. */
  private static final int COLLAPSED_DIVIDER_SIZE = 3;

  private final TagTreePanel treePanel;
  private final TerminalPanel terminalPanel;
  private final AppSettings settings;
  private final Path settingsPath;
  /** The split between tree and terminal. Drag the divider to resize;
   *  divider location is persisted so the user's last-chosen width
   *  survives restarts (see {@link #persistDividerLocation}). */
  private JSplitPane split;
  /** Menu item backing the View → Toggle tree panel action. We mutate its
   *  label between "Hide tree panel" and "Show tree panel" so the menu
   *  reflects the current state at a glance. */
  private JMenuItem toggleTreeMenuItem;
  /** True when the tree panel is fully collapsed (divider at 0). The
   *  hide/show toggle and divider-drag both flow through this flag. */
  private boolean treePanelHidden;
  /** Last non-zero divider location. When the user hides the tree we stash
   *  the current location here so showing it again restores the same
   *  width; the listener on JSplitPane keeps this in sync with the user's
   *  drag gestures while the tree is visible. */
  private int lastVisibleDividerLocation;
  /** App-wide Ctrl+B dispatcher. A {@link JMenuItem} accelerator only fires
   *  when the focused component lets the keystroke through, and JediTerm's
   *  terminal panel consumes Ctrl+B (it's the readline "back-char" binding
   *  and gets swallowed by the terminal's input map). A dispatcher
   *  registered with {@link KeyboardFocusManager} sees the event BEFORE
   *  any component dispatches it, so the tree-toggle works no matter
   *  which child has focus. */
  private final KeyEventDispatcher treeToggleDispatcher = e -> {
    if (e.getID() != KeyEvent.KEY_PRESSED) return false;
    if (e.getKeyCode() != KeyEvent.VK_B) return false;
    // Ctrl only — leave Ctrl+Shift+B / Ctrl+Alt+B / etc. alone for the
    // shell or future bindings.
    int mods = e.getModifiersEx();
    if ((mods & InputEvent.CTRL_DOWN_MASK) == 0) return false;
    if ((mods & (InputEvent.SHIFT_DOWN_MASK | InputEvent.ALT_DOWN_MASK
        | InputEvent.META_DOWN_MASK)) != 0) return false;
    // Only when this frame (or one of its owned windows — settings dialog,
    // future popups) has focus. Returning false here would let sibling
    // top-level windows also trigger the toggle, which would be surprising.
    java.awt.Window focused = KeyboardFocusManager
        .getCurrentKeyboardFocusManager().getFocusedWindow();
    if (focused == null) return false;
    if (!SwingUtilities.isDescendingFrom(focused, this)) return false;
    toggleTreePanel();
    e.consume();
    return true;
  };

  public MainFrame() {
    super(BASE_TITLE);
    setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

    // Window icon: loaded from /terminal.png on the classpath, falling
    // back to /terminal.ico via Toolkit if the PNG is missing. PNG is
    // preferred because it's JDK-native ({@code ImageIO.read}); ICO
    // would need a third-party codec on Java 17 because ImageIO doesn't
    // decode .ico and Toolkit.createImage on the ICO format is
    // unreliable for PNG-embedded 256×256 variants. The icon drives the
    // OS-painted title bar (via the FlatLaf dark-mode DWM hint) and the
    // Windows taskbar / macOS dock.
    Image icon = loadWindowIcon();
    if (icon != null) setIconImage(icon);

    Path configPath = Paths.get(System.getProperty("user.home"),
        ".local-term-java", "config.json");
    settingsPath = Paths.get(System.getProperty("user.home"),
        ".local-term-java", "settings.json");
    TagConfigStore store = new TagConfigStore(configPath);
    this.settings = AppSettings.load(settingsPath);

    // Apply persisted font preferences before any terminal launches.
    if (settings.terminalFontFamily() != null) {
      DarkSettingsProvider.setOverrideFontFamily(settings.terminalFontFamily());
    }
    if (settings.cjkFontFamily() != null) {
      DarkSettingsProvider.setCjkFontFamily(settings.cjkFontFamily());
    }
    if (settings.symbolFontFamily() != null) {
      DarkSettingsProvider.setSymbolFontFamily(settings.symbolFontFamily());
    }
    if (settings.emojiFontFamily() != null) {
      DarkSettingsProvider.setEmojiFontFamily(settings.emojiFontFamily());
    }
    // Font size — applied to the static provider BEFORE the first session
    // opens so the very first terminal renders at the user's chosen size
    // (rather than defaulting to 14 then re-rendering at the saved value).
    DarkSettingsProvider.setTerminalFontSize(settings.terminalFontSize());
    // Initialise the CJK fallback once on startup so the first terminal
    // session — opened before the user visits Settings — already has a
    // usable fallback for non-ASCII runs. CompositeFontJediTermWidget
    // also lazy-inits this if we forget, but doing it here keeps the
    // persisted settings consistent.
    //
    // findTerminalCjkFallback accepts proportional CJK fonts as a last
    // resort (e.g. Microsoft YaHei UI) so the terminal pane never shows
    // tofu for Chinese when some CJK-capable font is installed. The
    // Settings dialog explains the column-spacing trade-off.
    DarkSettingsProvider.setCjkFallbackFont(FontUtils.findTerminalCjkFallback(14));

    // One shared icon resolver for both the tree cell renderer and the
    // terminal tab headers — keeps the SVG / ICO / raster cache single,
    // so opening N terminals doesn't re-decode the same /shell-icons/*.svg
    // and the icons shown in the tree match the tabs pixel-for-pixel.
    ShellIconResolver iconResolver = new ShellIconResolver(
        new IconLoader(), FolderIconCellRenderer.defaultTargetSize());

    this.treePanel = new TagTreePanel(store, iconResolver);
    this.terminalPanel = new TerminalPanel(iconResolver);

    treePanel.setOnLeafActivated(terminalPanel::openSession);
    // Clicking the "≪ Hide tree" button on the tree toolbar hides the
    // entire tree panel via the container-level toggle, which keeps the
    // decision in one place. While the tree is hidden, the JSplitPane
    // divider itself (a 3-px sliver at the leftmost edge) becomes the
    // affordance: dragging it right reveals the tree at its prior width.
    treePanel.setOnHideRequested(() -> setTreePanelVisible(true));

    // While any open terminal has produced output in the last second,
    // prepend a "●" to the title so the user notices even when the
    // window is unfocused. TerminalPanel routes through its per-session
    // debounce timer; we just listen for the aggregate transition.
    terminalPanel.addActivityListener(anyActive ->
        setTitle(anyActive ? "● " + BASE_TITLE : BASE_TITLE));

    buildMenuBar();
    layoutContent();

    // Register the global Ctrl+B dispatcher. We do this AFTER layoutContent
    // so any synchronous toggleTreePanel() call (via a re-entrant keystroke
    // that fires during the EDT pass that created this frame) sees the
    // JSplitPane already wired up. KeyboardFocusManager keeps the
    // dispatcher until the JVM exits or it's explicitly removed; we
    // remove it in windowClosing so closing the last window doesn't leave
    // a stale reference behind if the same JVM hosts another frame later.
    KeyboardFocusManager.getCurrentKeyboardFocusManager()
        .addKeyEventDispatcher(treeToggleDispatcher);

    // Apply the saved divider location after the split has been laid out.
    // JSplitPane.setDividerLocation(int) interprets its argument as a
    // proportion (0.0–1.0) when the split isn't yet shown, so we wait
    // until the component has a real width before snapping the divider
    // to the persisted pixel value. Calling inside invokeLater defers
    // it until after the first paint, when the split has finished its
    // initial layout pass and reports a stable width.
    SwingUtilities.invokeLater(() -> {
      if (split != null && lastVisibleDividerLocation > 0) {
        split.setDividerLocation(lastVisibleDividerLocation);
      }
    });

    addWindowListener(new WindowAdapter() {
      @Override public void windowClosing(WindowEvent e) {
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .removeKeyEventDispatcher(treeToggleDispatcher);
        onCloseRequested();
      }
    });

    setSize(1100, 700);
    setLocationRelativeTo(null);
    // Start maximized so the app fills the screen on launch; the OS
    // remembers the maximized frame state across normal/maximize toggles.
    setExtendedState(getExtendedState() | JFrame.MAXIMIZED_BOTH);
  }

  /**
   * Load the frame icon from {@code /terminal.png} on the classpath.
   * Returns {@code null} when the resource is missing or fails to
   * decode — the frame then uses Swing's default icon. PNG is JDK-native
   * so {@link ImageIO#read} is sufficient.
   */
  private static Image loadWindowIcon() {
    try (InputStream is = MainFrame.class.getResourceAsStream("/terminal.png")) {
      if (is == null) return null;
      return ImageIO.read(is);
    } catch (IOException e) {
      LOG.warn("Failed to load /terminal.png as window icon: {}", e.getMessage());
      return null;
    }
  }

  private void buildMenuBar() {
    JMenuBar bar = new JMenuBar();
    JMenu file = new JMenu("File");
    JMenuItem reload = new JMenuItem("Reload tree from disk");
    reload.addActionListener(e -> treePanel.reload());
    JMenuItem settingsItem = new JMenuItem("Settings...");
    settingsItem.addActionListener(e -> openSettings());
    JMenuItem exit = new JMenuItem("Exit");
    exit.addActionListener(e -> onCloseRequested());
    file.add(reload);
    file.add(settingsItem);
    file.addSeparator();
    file.add(exit);
    bar.add(file);

    // View menu — single "toggle tree panel" action that flips between
    // hiding and showing the left tree strip. Ctrl+B mirrors the shortcut
    // VS Code uses for the primary side bar, which most users will already
    // know. We keep the menu item as a field so the label can flip
    // dynamically to reflect the current state.
    JMenu view = new JMenu("View");
    toggleTreeMenuItem = new JMenuItem(hideTreeMenuLabel(false));
    toggleTreeMenuItem.setAccelerator(KeyStroke.getKeyStroke("ctrl B"));
    toggleTreeMenuItem.addActionListener(e -> toggleTreePanel());
    view.add(toggleTreeMenuItem);
    bar.add(view);

    setJMenuBar(bar);
  }

  private void openSettings() {
    // Live apply: the dialog fires onSizeChanged every time the user
    // adjusts the spinner. We update the static provider state and
    // push the new size into every open session immediately, so the
    // terminal pane resizes the moment the spinner ticks. Persistence
    // still happens on OK (Cancel rolls back the saved file, but the
    // live in-memory changes survive until the next openSettings).
    SettingsDialog dlg = new SettingsDialog(this, settings, size -> {
      DarkSettingsProvider.setTerminalFontSize(size);
      // Refresh the CJK fallback at the new size so a CJK fallback
      // picked at one point size doesn't carry the old metrics into
      // the new render.
      Font cjkAtSize = FontUtils.findTerminalCjkFallback(size);
      DarkSettingsProvider.setCjkFallbackFont(cjkAtSize);
      terminalPanel.applyFontSize(size);
    });
    dlg.setVisible(true);
    if (!dlg.isOkPressed()) return;
    // Apply the four slot choices — primary + 3 fallback slots. Each
    // setting is null when the user picked "(Auto)" so DarkSettingsProvider
    // falls through to auto-detect for that slot. Users who leave all
    // four on Auto therefore get the original auto-detected chain.
    String primary = dlg.selectedFontFamily();
    String cjk = dlg.selectedCjkFamily();
    String symbol = dlg.selectedSymbolFamily();
    String emoji = dlg.selectedEmojiFamily();
    settings.setTerminalFontFamily(primary);
    settings.setCjkFontFamily(cjk);
    settings.setSymbolFontFamily(symbol);
    settings.setEmojiFontFamily(emoji);
    // Persist + apply the chosen size. The live callback already pushed
    // the static + per-session state while the spinner was being
    // adjusted; this final write just snapshots the chosen value into
    // the in-memory AppSettings before save() reads it.
    int size = dlg.selectedFontSize();
    settings.setTerminalFontSize(size);
    DarkSettingsProvider.setOverrideFontFamily(primary);
    DarkSettingsProvider.setCjkFontFamily(cjk);
    DarkSettingsProvider.setSymbolFontFamily(symbol);
    DarkSettingsProvider.setEmojiFontFamily(emoji);
    DarkSettingsProvider.setTerminalFontSize(size);
    // Pick a CJK fallback so CompositeFontPanel can render non-ASCII in fonts
    // that lack CJK (Consolas, Courier New, …). The fallback is recomputed
    // each time the user opens Settings so it follows whatever the system
    // considers the best CJK-capable font. Uses the terminal-aware helper
    // that accepts proportional CJK as last resort, so Chinese actually
    // renders even if no monospaced CJK font is installed.
    Font cjkResolved = FontUtils.findTerminalCjkFallback(size);
    DarkSettingsProvider.setCjkFallbackFont(cjkResolved);
    try {
      settings.save();
      LOG.info("Settings saved: primary={}, cjk={}, symbol={}, emoji={}, size={}",
          primary, cjk, symbol, emoji, size);
    } catch (IOException ex) {
      JOptionPane.showMessageDialog(this,
          "Could not save settings: " + ex.getMessage(),
          "Settings error", JOptionPane.ERROR_MESSAGE);
      LOG.error("Settings save failed", ex);
    }
  }

  private void layoutContent() {
    // JSplitPane gives us two things the previous BorderLayout version
    // couldn't: a draggable divider the user can use to resize the tree
    // to any width they like, and an out-of-the-box OS-native collapse
    // gesture (drag the divider to 0 to hide). We make the divider itself
    // the "show tree" affordance when hidden — at 3 px wide it's a
    // hairline cue the user can drag right to restore the tree without
    // needing a separate "≫" button or menu accelerator.
    split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treePanel, terminalPanel);
    split.setContinuousLayout(true);
    // Set the divider size for the visible state. The toggle switches it
    // to 3 px when the tree is hidden so the collapsed strip is just a
    // hairline rather than the full 7-px L&F default.
    split.setDividerSize(NORMAL_DIVIDER_SIZE);
    // Pin the tree's minimum so dragging the divider can't crush it below
    // a usable width. The terminal side has no minimum — it gets whatever
    // remains.
    treePanel.setMinimumSize(new Dimension(AppSettings.MIN_TREE_PANEL_WIDTH, 0));
    terminalPanel.setMinimumSize(new Dimension(0, 0));

    // Restore the last-saved width. setDividerLocation pre-show only
    // works if the split already has its preferred size assigned; we use
    // the runtime listener below to apply the real value once the
    // component is on screen, falling back to a synchronous call here so
    // the very first paint is also correct.
    lastVisibleDividerLocation = settings.treePanelWidth();
    setContentPane(split);

    // Persist divider drags. We track the latest pixel position in
    // memory during the drag (so the in-memory AppSettings stays
    // current) but only WRITE to disk when the user releases the mouse
    // at the end of a drag — see the MouseAdapter below. Saving on
    // every property-change tick would burn dozens of disk writes per
    // second during a fast drag, and the persistence boundary the user
    // asked for is "drag end", not "drag motion".
    split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY,
        this::onDividerLocationChanged);
    attachDragReleaseListener();
  }

  /**
   * Attach the drag-end persistence listener to the JSplitPane's divider
   * component. We MUST target the divider rather than the JSplitPane
   * itself: BasicSplitPaneUI installs the divider as a sibling child of
   * the JSplitPane, and that child owns its own {@code MouseInputAdapter}
   * for handling drag gestures. Mouse events on the divider are dispatched
   * to the divider first, and do not bubble to the JSplitPane — so a
   * listener registered on JSplitPane never fires for divider drags,
   * which leaves the user's last-chosen width un-persisted on disk.
   *
   * <p>FlatLaf's split-pane divider extends {@code BasicSplitPaneDivider},
   * so an {@code instanceof} check catches both the stock Sun divider and
   * FlatLaf's subclass. If the UI hasn't installed the divider yet (rare
   * — it normally happens in the {@code JSplitPane} constructor), we
   * retry on the next EDT tick. As a final fallback we also attach to
   * the JSplitPane itself: some L&Fs forward divider clicks up the
   * container chain, and {@link #persistCurrentDividerLocation} is a
   * no-op when the position hasn't moved.
   */
  private void attachDragReleaseListener() {
    MouseAdapter releaseListener = new MouseAdapter() {
      @Override public void mousePressed(MouseEvent e) {
        // Single click on the 3-px splitter strip while the tree is
        // hidden is the "show tree panel" gesture — snap the divider to
        // lastVisibleDividerLocation. We use mousePressed (not mouseClicked)
        // because FlatLaf's divider drag controller consumes the press /
        // release pair before AWT dispatches mouseClicked, so click events
        // never reach this listener. Pressing on the strip first restores
        // the tree; if the user then drags, the drag continues from the
        // restored position and behaves as a normal resize.
        if (treePanelHidden && SwingUtilities.isLeftMouseButton(e)) {
          setTreePanelVisible(false);
        }
      }
      @Override public void mouseReleased(MouseEvent e) {
        persistCurrentDividerLocation();
      }
    };
    boolean attached = false;
    for (Component c : split.getComponents()) {
      if (c instanceof BasicSplitPaneDivider) {
        c.addMouseListener(releaseListener);
        attached = true;
      }
    }
    if (!attached) {
      // UI hasn't installed the divider yet — defer until it has.
      SwingUtilities.invokeLater(() -> {
        for (Component c : split.getComponents()) {
          if (c instanceof BasicSplitPaneDivider) {
            c.addMouseListener(releaseListener);
            return;
          }
        }
        // Final fallback: attach to the JSplitPane for L&Fs that forward
        // divider events up the container chain.
        split.addMouseListener(releaseListener);
      });
    }
  }

  /**
   * JSplitPane fires DIVIDER_LOCATION_PROPERTY after every divider change
   * (programmatic OR user drag). We mirror the latest pixel position into
   * {@link #lastVisibleDividerLocation} so {@link #setTreePanelVisible}
   * has a fresh value to restore on Show, but we DO NOT touch disk here
   * — that happens on mouseReleased (drag end). The no-op guard skips
   * the property changes that fire while {@link #setTreePanelVisible}
   * is mid-flight (e.g. the 0-px position during a Hide) so the cached
   * "last visible width" stays intact.
   */
  private void onDividerLocationChanged(PropertyChangeEvent e) {
    if (split == null) return;
    // While hidden, this listener is a no-op. The DIVIDER_LOCATION_PROPERTY
    // event can fire BEFORE mousePressed (FlatLaf's divider triggers a
    // divider-move / layout on press that fires the property change
    // first), so any "user drag revealed tree" logic here would flip
    // treePanelHidden prematurely and short-circuit the mousePressed
    // click-to-show handler. The mousePressed handler on the divider
    // is the single source of truth for transitioning out of the
    // hidden state.
    if (treePanelHidden) return;
    int newLoc = split.getDividerLocation();
    if (newLoc <= 0) return;
    if (newLoc == lastVisibleDividerLocation) return;
    lastVisibleDividerLocation = newLoc;
  }

  /**
   * Write the current divider location to {@link AppSettings} once. Called
   * on mouseReleased (drag end) so the persisted width reflects the
   * user's final choice. Skips when the divider is hidden or when the
   * position hasn't moved since the last save — a no-op click on the
   * divider surface (e.g. accidentally grabbing then releasing without
   * moving) mustn't rewrite the file with the same value.
   *
   * <p>Always mirrors the live position into
   * {@link #lastVisibleDividerLocation} so a Hide-then-Show right after
   * a drag restores the user's choice even on L&Fs whose
   * {@code DIVIDER_LOCATION_PROPERTY} listener doesn't fire mid-drag.
   * Without this, the in-memory field could stay stale (still at the
   * pre-drag value) and the next Show would snap back to that old
   * width — looking like "the splitter always shows a fixed width" to
   * the user.
   */
  private void persistCurrentDividerLocation() {
    if (split == null) return;
    int loc = split.getDividerLocation();
    if (loc <= 0) return;
    // Drag-while-hidden is a peek gesture — the user is just sliding
    // the 3-px strip to bring the tree into view temporarily. It must
    // NOT overwrite the saved width or the in-memory lastVisible
    // value, otherwise a Hide → Show round-trip would restore to
    // wherever they happened to drag instead of their saved width.
    if (treePanelHidden) return;
    lastVisibleDividerLocation = loc;
    if (loc == settings.treePanelWidth()) return;
    settings.setTreePanelWidth(loc);
    try {
      settings.save();
    } catch (IOException ex) {
      LOG.warn("Failed to persist divider location: {}", ex.getMessage());
    }
  }

  /**
   * Toggle the left tree panel between fully hidden and fully shown.
   * Driven by both the View menu item and the toolbar / strip buttons,
   * so the menu label and divider stay in sync via
   * {@link #setTreePanelVisible(boolean)}.
   */
  private void toggleTreePanel() {
    setTreePanelVisible(!treePanelHidden);
  }

  /**
   * Show or hide the tree panel. {@code hide == true} means the user just
   * pressed Hide (or the tree was visible and toggle was called).
   *
   * <p>Hide path: stash the current divider location so a subsequent
   * Show restores the user's last-chosen width, shrink the divider to
   * {@link #COLLAPSED_DIVIDER_SIZE} (3 px — just a hairline draggable
   * strip on the very left), and pin the divider to 0.
   *
   * <p>Show path: restore the divider to its normal width, then snap the
   * divider back to {@link #lastVisibleDividerLocation} on a LATER EDT
   * tick. JDK-4133984 (a long-standing JSplitPane bug) means
   * {@code setDividerLocation(int)} silently no-ops when called on the
   * same EDT tick as a {@code setDividerSize} change — the L&F cached
   * the previous (collapsed) divider position and ignores the new pixel
   * move until the next paint pass. Splitting size and position across
   * different EDT ticks, plus re-applying on a third tick, dodges the
   * bug for every L&F. Each step is idempotent so a race between
   * successive events doesn't break the layout.
   *
   * <p>The divider itself acts as the show-tree affordance when hidden:
   * the user can drag the 3-px sliver right and the tree re-appears at
   * whatever width the drag lands on — at which point our
   * {@link #onDividerLocationChanged} listener treats it as a normal
   * drag and persists the new width.
   */
  private void setTreePanelVisible(boolean hide) {
    treePanelHidden = hide;
    if (split == null) return;
    if (hide) {
      // Stash BEFORE the divider-size change — the read returns the
      // pixel position the user actually sees, and we want to restore
      // exactly that width on Show.
      int current = split.getDividerLocation();
      if (current > 0) lastVisibleDividerLocation = current;
      // Stage 1: shrink the strip on the next EDT tick so the layout
      // pass that consumes the new size is decoupled from this method.
      SwingUtilities.invokeLater(() -> split.setDividerSize(COLLAPSED_DIVIDER_SIZE));
      // Stage 2: pin the divider to 0 once the size change has settled.
      // Calling these on the same tick trips JSplitPane's no-op guard.
      SwingUtilities.invokeLater(() -> applyDividerPixel(0));
    } else {
      // Restore directly to lastVisibleDividerLocation; the on-disk
      // settings width is the same value (drag-while-visible keeps them
      // in sync). Fall back to the persisted width only as a last
      // resort if lastVisible was never set.
      final int target = lastVisibleDividerLocation > 0
          ? lastVisibleDividerLocation
          : settings.treePanelWidth();
      split.setDividerSize(NORMAL_DIVIDER_SIZE);
      split.setDividerLocation(target);
      // Re-apply on the next EDT tick with a reflection poke of
      // BasicSplitPaneUI.lastLocation so a layout pass can't revert
      // the divider to a stale cached value (JDK-4133984).
      forceDividerLocationOnNextTick(target);
    }
    if (toggleTreeMenuItem != null) {
      toggleTreeMenuItem.setText(hideTreeMenuLabel(hide));
    }
  }

  /**
   * Re-apply the divider position on the next EDT tick, with a direct
   * poke of {@code BasicSplitPaneUI.lastLocation} via reflection so the
   * upcoming layout pass reads the right value. Without the reflection
   * poke, FlatLaf's split pane caches the previous (collapsed)
   * lastLocation and the layout pass resets dividerLocation back to 0
   * even though our {@code setDividerLocation(target)} set the field
   * correctly. The poke forces the L&F's cached value in lockstep with
   * the JSplitPane field.
   */
  private void forceDividerLocationOnNextTick(int pixels) {
    SwingUtilities.invokeLater(() -> {
      if (split == null) return;
      split.setDividerSize(NORMAL_DIVIDER_SIZE);
      split.setDividerLocation(pixels);
      try {
        javax.swing.plaf.basic.BasicSplitPaneUI ui =
            (javax.swing.plaf.basic.BasicSplitPaneUI) split.getUI();
        java.lang.reflect.Field f =
            javax.swing.plaf.basic.BasicSplitPaneUI.class
                .getDeclaredField("lastLocation");
        f.setAccessible(true);
        f.setInt(ui, pixels);
      } catch (ReflectiveOperationException | SecurityException ex) {
        LOG.debug("Reflection poke of BasicSplitPaneUI.lastLocation failed: {}",
            ex.getMessage());
      }
      split.revalidate();
      split.repaint();
    });
  }

  /**
   * Pin the divider at {@code pixels} immediately and re-apply on the
   * next EDT tick. The deferred re-apply dodges JDK-4133984 (the first
   * setDividerLocation after a setDividerSize can silently no-op on
   * some L&Fs); the reflection poke of
   * {@code BasicSplitPaneUI.lastLocation} ensures the L&F's cached
   * value matches so a layout pass can't revert the divider.
   */
  private void applyDividerPixel(int pixels) {
    if (split == null) return;
    split.setDividerLocation(pixels);
    forceDividerLocationOnNextTick(pixels);
  }

  /**
   * Label for the View menu toggle. When the tree is currently visible
   * (i.e. the menu action will HIDE it) we say "Hide tree panel"; when
   * it's hidden the same click will reveal it, so we say "Show tree panel".
   */
  private static String hideTreeMenuLabel(boolean hidden) {
    return hidden ? "Show tree panel" : "Hide tree panel";
  }

  private void onCloseRequested() {
    int n = terminalPanel.openSessionCount();
    if (n == 0) { disposeAndExit(); return; }
    int confirm = JOptionPane.showConfirmDialog(this,
        "Close " + n + " running terminal(s)?", "Exit",
        JOptionPane.YES_NO_OPTION);
    if (confirm == JOptionPane.YES_OPTION) disposeAndExit();
  }

  private void disposeAndExit() {
    LOG.info("Shutting down: closing {} terminal(s)", terminalPanel.openSessionCount());
    terminalPanel.closeAll();
    dispose();
    System.exit(0);
  }
}
