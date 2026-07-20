package local.term;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the sealed {@link TagNode} model. We exercise
 * {@link TagNode.Folder} (which holds children) and {@link TagNode.Shell}
 * (leaf, no children) directly so the helpers {@code addOrReplaceChild},
 * {@code removeChild}, {@code replaceChild} and {@code withChildren} don't
 * need any virtual dispatch — they're concrete on Folder.
 */
class TagNodeTest {
  private TagNode.Shell shell(String name) {
    return new TagNode.Shell(UUID.randomUUID(), name,
        "C:\\Windows\\System32\\cmd.exe", null, "C:\\");
  }

  private TagNode.Folder folder(String name, List<TagNode> children) {
    return new TagNode.Folder(UUID.randomUUID(), name, children);
  }

  @Test
  void shellChildrenIsAlwaysEmpty() {
    TagNode.Shell s = shell("cmd");
    assertTrue(s.children().isEmpty(), "shells are always leaves");
    assertFalse(s.isFolder());
    assertTrue(s.isLeaf());
  }

  @Test
  void folderIsFolderEvenWhenEmpty() {
    TagNode.Folder f = folder("parent", List.of());
    assertTrue(f.isFolder());
    assertFalse(f.isLeaf());
    assertTrue(f.children().isEmpty());
  }

  @Test
  void folderMayContainShellsAndNestedFolders() {
    TagNode.Shell s1 = shell("cmd1");
    TagNode.Shell s2 = shell("cmd2");
    TagNode.Folder inner = folder("inner", List.of(s1));
    TagNode.Folder root = folder("root", List.of(inner, s2));
    assertEquals(2, root.children().size());
    assertSame(inner, root.children().get(0));
    assertSame(s2, root.children().get(1));
  }

  @Test
  void addOrReplaceChild_addsNewChild() {
    TagNode.Folder parent = folder("parent", List.of());
    TagNode.Shell child = shell("child");
    TagNode updated = parent.addOrReplaceChild(child);

    assertEquals(1, updated.children().size());
    assertEquals(child, updated.children().get(0));
    // Original parent unchanged.
    assertTrue(parent.children().isEmpty());
  }

  @Test
  void addOrReplaceChild_replacesExistingChildById() {
    TagNode.Shell a = shell("a");
    TagNode.Shell aRenamed = new TagNode.Shell(a.id(), "a-renamed",
        a.shellPath(), a.iconPath(), a.startPath());

    TagNode.Folder parent = folder("parent", List.of(a));
    TagNode updated = parent.addOrReplaceChild(aRenamed);

    assertEquals(1, updated.children().size());
    assertEquals("a-renamed", updated.children().get(0).name());
    assertSame(a, parent.children().get(0),
        "original parent must not be mutated; still references the original 'a'");
  }

  @Test
  void addOrReplaceChild_preservesOtherChildren() {
    TagNode.Shell a = shell("a");
    TagNode.Shell b = shell("b");
    TagNode.Shell c = shell("c");
    TagNode.Folder parent = folder("parent", List.of(a, b));

    TagNode updated = parent.addOrReplaceChild(c);

    assertEquals(3, updated.children().size());
    assertEquals(List.of(a, b, c), updated.children());
  }

  @Test
  void removeChild_removesById() {
    TagNode.Shell a = shell("a");
    TagNode.Shell b = shell("b");
    TagNode.Folder parent = folder("parent", List.of(a, b));

    TagNode updated = parent.removeChild(a.id());

    assertEquals(1, updated.children().size());
    assertEquals(b, updated.children().get(0));
  }

  @Test
  void removeChild_returnsEquivalentFolderWhenAbsent() {
    TagNode.Folder parent = folder("parent", List.of());
    TagNode updated = parent.removeChild(UUID.randomUUID());

    assertTrue(updated.children().isEmpty());
    // Same id + name + empty children (still a new instance-free fold).
    assertEquals(parent.id(), updated.id());
    assertEquals(parent.name(), updated.name());
  }

  @Test
  void replaceChild_swapsById() {
    TagNode.Shell a = shell("a");
    TagNode.Shell b = shell("b");
    TagNode.Folder parent = folder("parent", List.of(a));

    TagNode.Shell aEdited = new TagNode.Shell(a.id(), "a-edited",
        a.shellPath(), a.iconPath(), a.startPath());
    TagNode updated = parent.replaceChild(a.id(), aEdited);

    assertEquals(1, updated.children().size());
    assertEquals("a-edited", updated.children().get(0).name());
    assertSame(a, parent.children().get(0), "original parent must not be mutated");
  }

  @Test
  void replaceChild_returnsReceiverWhenChildIdAbsent() {
    TagNode.Shell a = shell("a");
    TagNode.Folder parent = folder("parent", List.of(a));
    TagNode.Shell fake = shell("not-a-child");

    TagNode updated = parent.replaceChild(fake.id(), fake);

    assertSame(parent, updated,
        "no matching child id → must return the receiver unchanged");
    assertEquals(1, updated.children().size());
  }

  @Test
  void replaceChild_preservesSiblingOrder() {
    TagNode.Shell a = shell("a");
    TagNode.Shell b = shell("b");
    TagNode.Shell c = shell("c");
    TagNode.Folder parent = folder("parent", List.of(a, b, c));

    TagNode.Shell bEdited = new TagNode.Shell(b.id(), "b-edited",
        b.shellPath(), b.iconPath(), b.startPath());
    TagNode updated = parent.replaceChild(b.id(), bEdited);

    assertEquals(List.of(a, bEdited, c), updated.children());
  }

  @Test
  void findById_findsInNestedTree() {
    TagNode.Shell grandchild = shell("grandchild");
    TagNode.Folder child = folder("child", List.of(grandchild));
    TagNode.Folder root = folder("root", List.of(child));

    assertEquals(grandchild, root.findById(grandchild.id()));
    assertEquals(child, root.findById(child.id()));
    assertEquals(root, root.findById(root.id()));
    assertNull(root.findById(UUID.randomUUID()));
  }

  @Test
  void isFolder_isFalseForShell_trueForFolder() {
    assertTrue(folder("f", List.of()).isFolder());
    assertFalse(shell("s").isFolder());
  }

  @Test
  void type_explicitAttributeOnInterface() {
    // The interface exposes type() so generic persistence code can branch on
    // kind without downcasting. Each record pins its kind to a constant
    // that matches the on-disk JSON discriminator.
    assertEquals("folder", folder("f", List.of()).type());
    assertEquals("shell", shell("s").type());
    assertEquals(TagNode.TYPE_FOLDER, folder("f", List.of()).type());
    assertEquals(TagNode.TYPE_SHELL, shell("s").type());
  }

  @Test
  void folder_withExpanded_returnsNewInstanceWithFlagSet() {
    TagNode.Folder original = new TagNode.Folder(
        java.util.UUID.randomUUID(), "name", java.util.List.of(), true);
    TagNode.Folder collapsed = original.withExpanded(false);
    assertNotSame(original, collapsed, "must be a new record instance");
    assertFalse(collapsed.expanded());
    assertEquals(original.id(), collapsed.id());
    assertEquals(original.name(), collapsed.name());
    assertEquals(original.children(), collapsed.children());
  }

  @Test
  void folder_withExpanded_sameValue_returnsSameInstance() {
    TagNode.Folder original = new TagNode.Folder(
        java.util.UUID.randomUUID(), "name", java.util.List.of(), true);
    assertSame(original, original.withExpanded(true),
        "no-op when flag is unchanged (cheap equality shortcut)");
  }

  @Test
  void folder_equalityIsIdBased_ignoringExpanded() {
    java.util.UUID id = java.util.UUID.randomUUID();
    TagNode.Folder a = new TagNode.Folder(id, "name", java.util.List.of(), true);
    TagNode.Folder b = new TagNode.Folder(id, "name", java.util.List.of(), false);
    assertEquals(a, b, "equality stays id-based per existing contract");
    assertEquals(a.hashCode(), b.hashCode());
  }
}
