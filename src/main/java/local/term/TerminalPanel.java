package local.term;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Right panel: JTabbedPane of live {@link TerminalSession}s.
 *
 * openSession() calls the launcher; on Success adds a tab, on Failure
 * shows an error dialog. closeAll() closes every open session.
 *
 * The onClosed callback registered on each session is the single cleanup
 * point — it fires for BOTH user-close (× button) and EOF (shell exit),
 * so tab removal and map cleanup happen consistently in either path.
 *
 * NOTE (spec deviation): the plan's onClosed only removed the map entry,
 * leaving a dead tab visible after the shell exits (EOF). Here onClosed
 * removes BOTH the tab AND the map entry, and closeSessionByKey simply
 * delegates to session.close() so all cleanup flows through onClosed.
 *
 * <p>Activity indicator: each session's {@link TerminalSession} calls
 * {@code onOutput} on every batch of new shell bytes and {@code onHighlightCleared}
 * after a 1-second debounce. The matching {@link CloseableTabHeader} shows a
 * 2 px accent strip at the top edge while highlighted. The panel also keeps
 * an aggregate {@code activeCount} and fires {@link ActivityListener}
 * transitions when the count crosses 0 — used by {@link MainFrame} to
 * prepend a {@code ●} to the window title while any shell has output.
 */
public class TerminalPanel extends JPanel {
  /** Notified whenever the "any session currently has output" flag toggles. */
  public interface ActivityListener {
    void onActivityChanged(boolean anyActive);
  }

  private final JTabbedPane tabs = new JTabbedPane();
  private final Map<UUID, TerminalSession> sessions = new LinkedHashMap<>();
  private final ShellIconResolver iconResolver;
  private final List<ActivityListener> activityListeners = new CopyOnWriteArrayList<>();
  /**
   * Live auto-script progress dialogs, keyed by the shell tag id. A dialog
   * is added when {@link #openSession} launches a shell that has an
   * auto-script, and removed when its session closes (either via the user
   * closing the tab, EOF, or a launch failure disposing the dialog).
   */
  private final Map<UUID, AutoScriptProgressDialog> progressDialogs =
      new LinkedHashMap<>();
  /**
   * Number of sessions currently in their highlight window. Decremented on
   * {@code onHighlightCleared} and when a session closes (so a highlight
   * from a now-closed tab can't keep the title ball stuck on).
   */
  private final AtomicInteger activeCount = new AtomicInteger();

  public TerminalPanel(ShellIconResolver iconResolver) {
    super(new BorderLayout());
    this.iconResolver = iconResolver;
    // FlatLaf's default tab padding (≈ 8 px on each side of the tab
    // component) leaves a visible blank band on both sides of the tab
    // content — the close button ends up flanked by equal-looking
    // whitespace whether the panel is right-aligned or not. Tighten to
    // 2 px so the tab rectangle hugs the inner content; the
    // 1-px contentSeparator and tab separator lines still render as
    // their own visual boundary.
    //
    // Verified against flatlaf 3.6: FlatTabbedPaneUI.getTabInsets
    // reads this client property first and only falls through to
    // BasicTabbedPaneUI's UIManager default when unset, so a single
    // putClientProperty is enough.
    tabs.putClientProperty("JTabbedPane.tabInsets", new Insets(0, 2, 0, 2));
    add(tabs, BorderLayout.CENTER);
    // Pull keyboard focus onto the newly-visible terminal whenever the
    // selected tab changes — covers header clicks (the CloseableTabHeader
    // workaround calls setSelectedIndex directly, bypassing the focus
    // trail the L&F normally provides), keyboard tab navigation, and any
    // future programmatic selection path. Without this the user had to
    // click the terminal area before keystrokes registered.
    tabs.addChangeListener(e -> {
      int idx = tabs.getSelectedIndex();
      if (idx < 0) return;             // last tab just closed
      java.awt.Component selected = tabs.getComponentAt(idx);
      if (selected == null) return;
      // requestFocusInWindow() can fail if the component isn't laid out yet
      // (e.g. right after removeTabAt). Defer to the next event loop tick so
      // the UI has finished updating before we ask for focus.
      SwingUtilities.invokeLater(selected::requestFocusInWindow);
    });
  }

  public void addActivityListener(ActivityListener listener) {
    activityListeners.add(listener);
  }

  public void openSession(TagNode.Shell tag) {
    // Build the progress dialog (if any) BEFORE the launcher, but don't
    // show it yet — we want to confirm the launcher succeeded before the
    // user sees the window, so a launch failure never leaves an orphan
    // dialog. The dialog also acts as the ProgressListener for the runner.
    AutoScript script = tag.autoScript();
    AutoScriptProgressDialog progressDlg = null;
    AutoScriptRunner.ProgressListener progressListener = null;
    // A disabled-but-stored AutoScript (user un-checked "enable" after
    // editing steps) must not open a progress dialog or wire a listener;
    // TerminalLauncher also gates the runner on enabled(), so without
    // this gate we'd have an empty listener and a stray dialog window.
    if (script != null && script.enabled() && !script.steps().isEmpty()) {
      Window owner = SwingUtilities.getWindowAncestor(this);
      progressDlg = new AutoScriptProgressDialog(owner, tag.name(), script);
      progressListener = progressDlg;
      // Hold a strong reference so GC doesn't drop it before launch
      // returns and the runner fires events.
      progressDialogs.put(tag.id(), progressDlg);
    }

    // Timeout handler: when an auto-script step waits too long, the runner
    // halts and notifies us. The progress dialog paints the failure inside
    // itself — when it's still visible, no second popup is needed. If the
    // dialog was already disposed (e.g. user closed it manually after
    // an earlier failure, or no dialog exists for this shell), fall back
    // to the legacy JOptionPane so the user still hears about the timeout.
    java.util.function.Consumer<AutoScriptRunner.TimeoutInfo> onTimeout = info -> {
      AutoScriptProgressDialog dlg = progressDialogs.get(tag.id());
      if (dlg != null && dlg.isVisible()) return;
      javax.swing.SwingUtilities.invokeLater(() ->
          JOptionPane.showMessageDialog(TerminalPanel.this,
              "Auto-script for '" + info.tagName() + "' stopped at step "
                  + (info.stepIdx() + 1) + ".\n"
                  + "Waited " + info.timeoutMs() + "ms for pattern '"
                  + info.waitPattern() + "' without seeing it.\n\n"
                  + "The terminal tab remains open — inspect the output "
                  + "above and decide what to do next.",
              "Auto-script timeout",
              JOptionPane.WARNING_MESSAGE));
    };
    TerminalLauncher.LaunchResult result =
        TerminalLauncher.launch(tag, onTimeout, progressListener);
    if (result instanceof TerminalLauncher.LaunchResult.Failure f) {
      // Launch failed — clean up the not-yet-shown dialog and surface the
      // legacy launch-failure dialog.
      if (progressDlg != null) {
        progressDlg.disposeNow();
        progressDialogs.remove(tag.id());
      }
      JOptionPane.showMessageDialog(this,
          "Could not start terminal '" + tag.name() + "':\n" + f.message(),
          "Terminal launch failed", JOptionPane.ERROR_MESSAGE);
      return;
    }
    TerminalSession session = ((TerminalLauncher.LaunchResult.Success) result).session();
    UUID key = tag.id();
    sessions.put(key, session);
    int index = tabs.getTabCount();
    tabs.addTab(tag.name(), session.getWidget());
    Icon icon = iconResolver.getIcon(tag);
    // FlatLaf's FlatTabbedPaneUI doesn't reliably forward mousePressed
    // events from a custom tab component (set via setTabComponentAt)
    // up to the JTabbedPane's own MouseHandler, so clicks on the
    // CloseableTabHeader never trigger setSelectedIndex through the
    // normal Swing path. The standard workaround is to have the custom
    // component drive tab selection itself on mousePressed. The close
    // button consumes its own mousePressed via BasicButtonUI, so this
    // listener doesn't fire when the user clicks the × — close stays
    // handled by the JButton's ActionListener.
    CloseableTabHeader header = new CloseableTabHeader(icon, tag.name(),
        () -> {
          // Dynamically look up our current index — a fixed int would go
          // stale when an earlier tab closes and shifts all later indices.
          var w = session.getWidget();
          for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabs.getComponentAt(i) == w) {
              tabs.setSelectedIndex(i);
              return;
            }
          }
        },
        () -> session.close());
    tabs.setTabComponentAt(index, header);
    // Tooltip recovers a truncated title on hover and also exposes
    // the close button for users on touch-friendly L&Fs that hide the ×.
    tabs.setToolTipTextAt(index, tag.name());
    tabs.setSelectedIndex(index);
    session.getWidget().requestFocusInWindow();

    // Now that the tab is fully wired, show the progress dialog (if any).
    // The dialog was constructed modeless so the EDT is free to render the
    // initial state immediately.
    if (progressDlg != null) {
      progressDlg.showIfActive();
    }

    // Wire per-session output → top-edge accent strip + aggregate title flag.
    // Counting rule: increment only on the leading edge (false → true) and
    // decrement only on the trailing edge (true → false). Counting every
    // output batch makes a single silence drain the count by 1, while a
    // burst may have incremented it by N — so a session with sustained
    // output (e.g. tail -f, build log) would get its count stuck > 0 even
    // after silence, leaving the window-title ball permanently on. Using
    // header.isHighlighted() as the per-session state keeps the count in
    // lockstep with the actual visible highlight windows.
    session.setOnOutput(() -> {
      if (header.isHighlighted()) return;       // already counted; refresh timer via restart()
      header.setHighlighted(true);
      if (activeCount.incrementAndGet() == 1) {
        fireActivityChanged(true);
      }
    });
    session.setOnHighlightCleared(() -> {
      if (!header.isHighlighted()) return;      // stale clear after a leading-edge race
      header.setHighlighted(false);
      if (Math.max(0, activeCount.decrementAndGet()) == 0) {
        fireActivityChanged(false);
      }
    });

    // Fires for BOTH user-close (× button) and EOF (shell exit).
    session.setOnClosed(() -> {
      // Drop any in-flight highlight for this tab — otherwise the title
      // ball would stay on after a session goes away during its window.
      if (header.isHighlighted()) {
        header.setHighlighted(false);
        int now = Math.max(0, activeCount.decrementAndGet());
        if (now == 0) fireActivityChanged(false);
      }
      sessions.remove(key);
      // Dispose any progress dialog still associated with this tag.
      // onClosed may fire on a non-EDT thread (TerminalSession.close is
      // also called from inside AutoScriptRunner when a write fails), so
      // hop to the EDT before touching Swing widgets.
      AutoScriptProgressDialog dlg = progressDialogs.remove(key);
      if (dlg != null) dlg.disposeNow();
      for (int i = 0; i < tabs.getTabCount(); i++) {
        if (session.getWidget() == tabs.getComponentAt(i)) {
          tabs.removeTabAt(i);
          break;
        }
      }
    });
  }

  private void fireActivityChanged(boolean anyActive) {
    // ActivityListener list is CopyOnWriteArrayList so this is safe without
    // an external lock and matches the notify pattern used elsewhere in
    // Swing listeners.
    for (ActivityListener l : activityListeners) {
      try {
        l.onActivityChanged(anyActive);
      } catch (RuntimeException ex) {
        // Don't let one listener's NPE take down the EDT for the rest.
      }
    }
  }

  public void closeSessionByKey(UUID key) {
    TerminalSession s = sessions.get(key);
    if (s == null) return;
    s.close();  // onClosed handles tab removal + map cleanup + progress dialog dispose
  }

  public void closeAll() {
    // Snapshot before iterating: s.close() fires onClosed which calls
    // sessions.remove(key), mutating the map during iteration.
    var snapshot = new java.util.ArrayList<>(sessions.values());
    sessions.clear();
    for (TerminalSession s : snapshot) s.close();
    tabs.removeAll();
    // Dispose any progress dialogs whose onClosed didn't fire (e.g., when
    // the dialog was constructed but its session never reached the
    // registration step — defensive only).
    for (AutoScriptProgressDialog dlg : new java.util.ArrayList<>(progressDialogs.values())) {
      dlg.disposeNow();
    }
    progressDialogs.clear();
    // every close() fires onClosed which clears its own highlight; clear
    // any residual aggregate just to be safe (e.g., a session that was
    // about to be highlighted but never had its highlight cleared before
    // closeAll was called).
    activeCount.set(0);
    fireActivityChanged(false);
  }

  public int openSessionCount() { return sessions.size(); }

  /**
   * Apply a new font size to every open session. Called by
   * {@code MainFrame} when the user adjusts the Settings font-size
   * spinner so changes propagate live without restarting terminals.
   *
   * <p>Sessions without a {@code CompositeFontPanel} (the standard
   * launcher always builds one, but defensive against legacy / test
   * paths) are skipped — those would need a full restart to honor the
   * change.
   */
  public void applyFontSize(int size) {
    for (TerminalSession session : sessions.values()) {
      CompositeFontPanel panel = session.getCompositePanel();
      if (panel == null) continue;
      // applyFontSize re-runs reinitFontAndResize() which resizes the
      // terminal buffer to match the new cell metrics; that touches
      // Swing state, so marshal to the EDT even when invoked from
      // another thread (the dialog spinner fires on the EDT in
      // practice, but defensive hop costs nothing).
      if (SwingUtilities.isEventDispatchThread()) {
        panel.applyFontSize(size);
      } else {
        SwingUtilities.invokeLater(() -> panel.applyFontSize(size));
      }
    }
  }

  /**
   * Tab header: optional shell icon + name label + close button, with a
   * 2 px accent strip at the top edge while the owning session is in
   * its output-highlight window. Icon-then-title matches the tree
   * renderer and the IDE/editor tab convention; tooltip on the whole
   * header surfaces a truncated title on hover.
   */
  private static class CloseableTabHeader extends JPanel {
    private volatile boolean highlighted;

    CloseableTabHeader(Icon icon, String title, Runnable onSelect,
        Runnable onClose) {
      // BorderLayout so the close button is hard-right against the
      // header's right edge instead of trailing the label by a fixed
      // hgap. With FlowLayout.LEFT the close button sits flush after
      // the label, which leaves a visible blank band to the right of
      // the × whenever the tab's preferred width exceeds its contents
      // (FlatLaf tabs add their own padding around the tab component,
      // amplifying the effect). WEST + EAST without CENTER pins the
      // label to the left and the × to the right; the gap between them
      // is the empty middle of the header, not extra padding after the
      // button.
      super(new BorderLayout());
      setOpaque(false);
      // ShellIconResolver returns a zero-width ImageIcon (never null) when
      // even default.svg is missing, so we explicitly check the dimensions
      // and fall back to a label-only header. Keeps tab heights uniform
      // regardless of which fallback fired.
      boolean hasIcon = icon != null
          && icon.getIconWidth() > 0
          && icon.getIconHeight() > 0;
      JLabel label = hasIcon
          ? new JLabel(title, icon, SwingConstants.LEADING)
          : new JLabel(title);
      // Inner WEST panel keeps icon + label grouped at their natural
      // widths (FlowLayout.LEFT, 4 px gap) so the icon doesn't get
      // pulled away from the title when the WEST region has slack.
      JPanel labelGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
      labelGroup.setOpaque(false);
      labelGroup.add(label);
      add(labelGroup, BorderLayout.WEST);
      // Anonymous subclass so the close button can paint its own hover /
      // press affordance. Default Swing buttons rely on the L&F to paint
      // the rollover background, but {@code contentAreaFilled(false)} +
      // {@code borderPainted(false)} (the FlatLaf-recommended setup for
      // minimal chrome in a tab header) disables that entirely — without
      // our own feedback the × looks identical whether you're hovering or
      // not, so users can't tell whether the button is interactive.
      //
      // Visual language: a rounded translucent overlay. We don't use a
      // "danger" red here — closing a tab isn't destructive in the way
      // delete-on-disk is, and IDE/browser tabs across VS Code / IntelliJ
      // / Chrome all use neutral grays for tab-close hover. White at low
      // alpha reads on FlatLaf Dark without a separate light-theme branch.
      JButton close = new JButton("×") {
        private boolean hover;
        private boolean pressed;
        {
          // Right margin is 0 so the × sits flush against the header's
          // right edge — BorderLayout EAST already anchors the button at
          // the right; any positive right margin would just push the
          // glyph back into the visible space the user reads as "gap".
          // Left margin stays at 4 so the × doesn't crowd the label.
          setMargin(new Insets(0, 4, 0, 0));
          setBorder(null);
          setContentAreaFilled(false);
          addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
              hover = true;
              repaint();
            }
            @Override public void mouseExited(MouseEvent e) {
              hover = false;
              pressed = false;
              repaint();
            }
            @Override public void mousePressed(MouseEvent e) {
              pressed = true;
              repaint();
            }
            @Override public void mouseReleased(MouseEvent e) {
              pressed = false;
              repaint();
            }
          });
          addActionListener(e -> onClose.run());
        }

        @Override
        protected void paintComponent(Graphics g) {
          if (hover || pressed) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
              g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                  RenderingHints.VALUE_ANTIALIAS_ON);
              // Press bumps alpha so the user sees the click registered.
              g2.setColor(new Color(255, 255, 255, pressed ? 72 : 40));
              g2.fillRoundRect(0, 0, getWidth(), getHeight(), 4, 4);
            } finally {
              g2.dispose();
            }
          }
          super.paintComponent(g);
        }
      };
      add(close, BorderLayout.EAST);
      setToolTipText(title);
      // Tab selection workaround: FlatLaf's FlatTabbedPaneUI doesn't
      // reliably forward mousePressed from a JLabel child up through the
      // custom tab component to the JTabbedPane's MouseHandler.
      //
      // Two fixes here:
      // 1. Register the listener on BOTH the header panel and the label
      //    child — the first tab's label area (icon + text) was a dead
      //    zone because FlatLaf won't bubble from JLabel → JPanel.
      // 2. consume() the event so it never reaches FlatLaf's own tab
      //    handler.  Without this, the handler recalculates the click's
      //    tab after we already selected the correct one, and the
      //    recalculation can be off (selecting the wrong neighbour).
      //    The close button is unaffected — BasicButtonUI consumes its
      //    own mousePressed, so × still fires ActionListener only.
      MouseAdapter selectOnPress = new MouseAdapter() {
        @Override public void mousePressed(MouseEvent e) {
          onSelect.run();
          e.consume();
        }
      };
      addMouseListener(selectOnPress);
      label.addMouseListener(selectOnPress);
    }

    void setHighlighted(boolean h) {
      if (this.highlighted == h) return;
      this.highlighted = h;
      // Swing repaints aren't coalesced into the next paint of an arbitrary
      // parent — explicit repaint() guarantees the strip appears immediately
      // even if the user isn't looking at the panel area.
      repaint();
    }

    boolean isHighlighted() { return highlighted; }

    /**
     * Draw the 2 px activity accent strip AFTER children so it sits
     * on top of the icon / label / close button.  Overriding
     * {@code paintChildren} instead of {@code paint} avoids confusing
     * FlatLaf's mouse-event dispatch for custom tab components — the
     * overlay must not change how the UI delegate routes clicks to
     * the tab header.
     */
    @Override
    protected void paintChildren(Graphics g) {
      super.paintChildren(g);
      if (!highlighted) return;
      Graphics2D g2 = (Graphics2D) g.create();
      try {
        // Vivid orange (Material Orange A700). Picked over the L&F
        // focusColor because on dark FlatLaf the default accent blue is
        // easy to miss against the dark tab background; orange is the
        // canonical "attention" hue and reads on both dark and light
        // themes. 1 px so it stays a thin accent line and doesn't
        // crowd the tab content.
        g2.setColor(new Color(0xFF, 0x6D, 0x00));
        g2.fillRect(0, 0, getWidth(), 1);
      } finally {
        g2.dispose();
      }
    }
  }
}
