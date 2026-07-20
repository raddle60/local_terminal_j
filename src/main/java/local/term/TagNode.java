package local.term;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Tree node representing either an organisational folder or a launchable
 * shell configuration. Two concrete kinds:
 *
 * <ul>
 *   <li>{@link Folder} — only a {@code name} and {@code children}. Used to
 *       group shell nodes (and nested folders) into a hierarchy. Always
 *       non-leaf (always has children-or-empty).</li>
 *   <li>{@link Shell} — the actual launchable entry: {@code name},
 *       {@code shellPath}, optional {@code iconPath}, {@code startPath}.
 *       Always a leaf (no children).</li>
 * </ul>
 *
 * <p>Top-level entries (direct children of the synthetic root) MUST be
 * {@link Folder}s — the user-facing rule is enforced in
 * {@link TagTreePanel}'s empty-area context menu, which only exposes
 * "Add top-level folder…". Sub-tree entries may mix folders and shells.
 *
 * <p>Equality and hashing are based only on {@code id}; two TagNodes with
 * the same id are considered the same node regardless of structural kind
 * (this is the only sensible contract for editing — when the user edits a
 * node, they change its other fields but keep the id).
 */
public sealed interface TagNode permits TagNode.Folder, TagNode.Shell {

  /** Discriminator value for {@link Folder} (matches the on-disk {@code type} field). */
  String TYPE_FOLDER = "folder";
  /** Discriminator value for {@link Shell} (matches the on-disk {@code type} field). */
  String TYPE_SHELL = "shell";

  UUID id();
  String name();

  /**
   * Explicit kind discriminator. Returns {@link #TYPE_FOLDER} for {@link Folder}
   * and {@link #TYPE_SHELL} for {@link Shell}. Carried on the interface
   * (rather than derived from {@code instanceof}) so generic persistence
   * code can branch on the kind without downcasting, and so the on-disk
   * JSON has a single source-of-truth string to write and validate.
   */
  String type();

  /**
   * Direct children of this node. Empty for {@link Shell} (shells are
   * leaves); the (possibly empty) child list for {@link Folder}.
   */
  List<TagNode> children();

  /** True for a {@link Folder}, false for a {@link Shell}. */
  boolean isFolder();

  /** True if this node has no children (i.e., is a launchable shell leaf). */
  default boolean isLeaf() {
    return !isFolder();
  }

  /**
   * Locate the descendant (or self) with id {@code target}, or {@code null}
   * when no such node exists in this subtree.
   */
  default TagNode findById(UUID target) {
    if (id().equals(target)) return this;
    for (TagNode c : children()) {
      TagNode hit = c.findById(target);
      if (hit != null) return hit;
    }
    return null;
  }

  // -----------------------------------------------------------------
  // Folder
  // -----------------------------------------------------------------

  /**
   * Organisational node — a name and a (possibly empty) list of children.
   * Children may be either {@link Folder}s or {@link Shell}s.
   */
  record Folder(UUID id, String name, List<TagNode> children, boolean expanded) implements TagNode {
    public Folder {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(name, "name");
      children = children == null ? List.of() : List.copyOf(children);
    }

    /** Compact constructor for callers that don't specify an expansion flag (defaults to true). */
    public Folder(UUID id, String name, List<TagNode> children) {
      this(id, name, children, true);
    }

    @Override public String type() { return TYPE_FOLDER; }

    @Override public boolean isFolder() { return true; }

    /** Returns a new Folder with {@code child} added or replaced (matched by id). */
    public Folder addOrReplaceChild(TagNode child) {
      List<TagNode> next = new ArrayList<>(children);
      boolean replaced = false;
      for (int i = 0; i < next.size(); i++) {
        if (next.get(i).id().equals(child.id())) {
          next.set(i, child);
          replaced = true;
          break;
        }
      }
      if (!replaced) next.add(child);
      return new Folder(id, name, next);
    }

    /** Returns a new Folder with the child having {@code childId} removed. */
    public Folder removeChild(UUID childId) {
      List<TagNode> next = new ArrayList<>();
      for (TagNode c : children) {
        if (!c.id().equals(childId)) next.add(c);
      }
      return new Folder(id, name, next);
    }

    /**
     * Replace the immediate child at {@code childId} by {@code replacement}.
     * Sibling order is preserved. Returns the receiver unchanged when no
     * immediate child has that id.
     */
    public Folder replaceChild(UUID childId, TagNode replacement) {
      boolean found = false;
      List<TagNode> next = new ArrayList<>(children.size());
      for (TagNode c : children) {
        if (!found && c.id().equals(childId)) {
          next.add(replacement);
          found = true;
        } else {
          next.add(c);
        }
      }
      if (!found) return this;
      return new Folder(id, name, next);
    }

    /**
     * Returns a new Folder with the expansion flag set, or this instance
     * if the value is already {@code newExpanded}. The short-circuit is
     * load-bearing: callers (e.g. {@code persistExpansion}) use {@code ==}
     * to detect a real change and skip a redundant save.
     */
    public Folder withExpanded(boolean newExpanded) {
      return newExpanded == expanded ? this : new Folder(id, name, children, newExpanded);
    }

    /** Returns a new Folder with the given children (preserves id, name, expanded). */
    public Folder withChildren(List<TagNode> newChildren) {
      return new Folder(id, name, newChildren, expanded);
    }

    /** Equality is id-based — see {@link TagNode#equalsById}. */
    @Override public boolean equals(Object o) { return TagNode.equalsById(this, o); }
    @Override public int hashCode() { return TagNode.hashCodeById(this); }
  }

  // -----------------------------------------------------------------
  // Shell
  // -----------------------------------------------------------------

  /**
   * Launchable leaf node — name plus the shell-process config (binary path
   * + optional args, icon, working directory, optional auto-script).
   * Always a leaf: {@link #children()} returns the empty list. {@code iconPath},
   * {@code shellArgs}, and {@code autoScript} are optional / nullable.
   */
  record Shell(UUID id, String name, String shellPath, String shellArgs,
               String iconPath, String startPath, AutoScript autoScript) implements TagNode {

    public Shell {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(shellPath, "shellPath");
      Objects.requireNonNull(startPath, "startPath");
      // iconPath, shellArgs, and autoScript are intentionally nullable.
    }

    @Override public String type() { return TYPE_SHELL; }
    @Override public boolean isFolder() { return false; }
    @Override public List<TagNode> children() { return List.of(); }

    /**
     * 5-arg overload preserved for existing call sites that don't supply
     * {@code shellArgs} or {@code autoScript} (mostly tests and the
     * non-duplicate editor path before args existed).
     */
    public Shell(UUID id, String name, String shellPath, String iconPath, String startPath) {
      this(id, name, shellPath, null, iconPath, startPath, null);
    }
  }

  // -----------------------------------------------------------------
  // Equality / hashing (delegated to id)
  // -----------------------------------------------------------------

  /** Two TagNodes with the same id are considered equal (id is the editing key). */
  static boolean equalsById(TagNode a, Object b) {
    if (a == b) return true;
    if (!(b instanceof TagNode other)) return false;
    return a.id().equals(other.id());
  }

  static int hashCodeById(TagNode a) {
    return a.id().hashCode();
  }
}
