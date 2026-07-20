package local.term;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persists the root {@link TagNode} tree to a JSON file.
 * - Atomic writes (write to .tmp, then rename).
 * - Corrupt-file recovery: rename to config.json.bak.&lt;timestamp&gt; and start empty.
 *
 * <p>Disk format: each node carries a {@code "type": "folder"|"shell"}
 * discriminator. {@link #parseNode} requires {@code type} on every node and
 * uses only its value — there is no inference from {@code shellPath},
 * {@code startPath}, or child presence. Files lacking the discriminator
 * are treated as corrupt, backed up, and replaced with an empty root.
 */
public class TagConfigStore {
  private static final Logger LOG = LoggerFactory.getLogger(TagConfigStore.class);
  private static final int CURRENT_VERSION = 2;
  public static final UUID ROOT_ID = new UUID(0L, 0L);

  private final Path configFile;
  private final ObjectMapper mapper;

  public TagConfigStore(Path configFile) {
    this.configFile = configFile;
    this.mapper = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);
  }

  public TagNode load() {
    if (!Files.exists(configFile)) {
      TagNode empty = emptyRoot();
      saveQuietly(empty);
      return empty;
    }
    try {
      JsonNode root = mapper.readTree(configFile.toFile());
      return parse(root);
    } catch (IOException | IllegalArgumentException e) {
      backupCorruptFile();
      LOG.warn("Config file corrupt; backed up and starting empty: {}", e.getMessage());
      TagNode empty = emptyRoot();
      saveQuietly(empty);
      return empty;
    }
  }

  public void save(TagNode root) throws IOException {
    Path parent = configFile.getParent();
    if (parent != null) Files.createDirectories(parent);
    Path tmp = configFile.resolveSibling(configFile.getFileName() + ".tmp");
    ConfigDto dto = new ConfigDto(CURRENT_VERSION, flatten(root));
    mapper.writeValue(tmp.toFile(), dto);
    Files.move(tmp, configFile,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
  }

  private void saveQuietly(TagNode root) {
    try { save(root); } catch (IOException e) { LOG.warn("Could not write empty config: {}", e.getMessage()); }
  }

  private void backupCorruptFile() {
    try {
      String ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
      Path backup = configFile.resolveSibling("config.json.bak." + ts);
      Files.move(configFile, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      LOG.error("Could not back up corrupt config file: {}", e.getMessage());
    }
  }

  private TagNode parse(JsonNode root) {
    JsonNode tags = root.path("tags");
    if (!tags.isArray()) return emptyRoot();
    List<TagNode> children = new ArrayList<>();
    for (JsonNode t : tags) children.add(parseNode(t));
    return new TagNode.Folder(ROOT_ID, "root", children);
  }

  /**
   * Parse one node. Requires {@code type} to be present and equal to
   * {@link TagNode#TYPE_FOLDER} or {@link TagNode#TYPE_SHELL}; structural
   * fields like {@code shellPath} or child count play no role in kind
   * determination. Any missing/unknown discriminator is treated as corrupt
   * — the {@link #load} caller handles it by backing the file up and
   * starting with an empty root.
   */
  private TagNode parseNode(JsonNode n) {
    UUID id = UUID.fromString(n.path("id").asText());
    String name = n.path("name").asText("");
    String shell = n.path("shellPath").asText("");
    String icon = n.path("iconPath").isNull() ? null : n.path("iconPath").asText(null);
    String start = n.path("startPath").asText("");
    boolean expanded = n.path("expanded").asBoolean(true);
    JsonNode childrenNode = n.path("children");
    List<TagNode> kids = new ArrayList<>();
    if (childrenNode.isArray()) {
      for (JsonNode c : childrenNode) kids.add(parseNode(c));
    }
    String type = n.path("type").asText("");
    if (TagNode.TYPE_SHELL.equalsIgnoreCase(type)) {
      JsonNode asNode = n.path("autoScript");
      AutoScript auto = (asNode.isMissingNode() || asNode.isNull())
          ? null
          : new AutoScript(
              asNode.path("timeoutMs").asInt(AutoScript.DEFAULT_TIMEOUT_MS),
              parseSteps(asNode.path("steps")),
              // `enabled` is a v2.1+ field; missing → default true so
              // existing configs keep running their scripts (preserves
              // prior behaviour, where presence alone implied enabled).
              asNode.path("enabled").asBoolean(true));
      // shellArgs is optional; older config files won't have it.
      JsonNode argsNode = n.path("shellArgs");
      String shellArgs = argsNode.isMissingNode() || argsNode.isNull()
          ? null
          : argsNode.asText(null);
      if (shellArgs != null && shellArgs.isEmpty()) shellArgs = null;
      return new TagNode.Shell(id, name, shell, shellArgs, icon, start, auto);
    }
    if (TagNode.TYPE_FOLDER.equalsIgnoreCase(type)) {
      return new TagNode.Folder(id, name, kids, expanded);
    }
    throw new IllegalArgumentException(
        "node '" + id + "' missing or unknown type discriminator: '" + type + "'");
  }

  private static List<Step> parseSteps(JsonNode stepsNode) {
    if (!stepsNode.isArray()) return List.of();
    List<Step> out = new ArrayList<>();
    for (JsonNode s : stepsNode) {
      out.add(new Step(
          s.path("waitPattern").asText(""),
          s.path("command").asText("")));
    }
    return out;
  }

  /**
   * Walk {@code node}'s children and produce the persisted DTO list. The
   * root isn't itself serialised — only its children (this is the shape the
   * existing on-disk format uses).
   */
  private List<TagNodeDto> flatten(TagNode node) {
    List<TagNodeDto> out = new ArrayList<>();
    for (TagNode c : node.children()) {
      out.add(toDto(c));
    }
    return out;
  }

  private static TagNodeDto toDto(TagNode c) {
    if (c.type().equals(TagNode.TYPE_SHELL)) {
      TagNode.Shell shell = (TagNode.Shell) c;
      // Shells are leaves; expanded is written as a constant false for
      // schema uniformity (every node carries the field on disk) and is
      // ignored on read. See Shell's record constructor — no expanded param.
      return new TagNodeDto(TagNode.TYPE_SHELL, shell.id(), shell.name(),
          shell.shellPath(), shell.shellArgs(), shell.iconPath(), shell.startPath(),
          false, List.of(), toAutoScriptDto(shell.autoScript()));
    }
    if (!c.type().equals(TagNode.TYPE_FOLDER)) {
      throw new IllegalStateException("unknown TagNode type: " + c.type());
    }
    TagNode.Folder folder = (TagNode.Folder) c;
    List<TagNodeDto> kids = new ArrayList<>();
    for (TagNode k : folder.children()) kids.add(toDto(k));
    return new TagNodeDto(TagNode.TYPE_FOLDER, folder.id(), folder.name(),
        "", null, null, "", folder.expanded(), kids, null);
  }

  private static AutoScriptDto toAutoScriptDto(AutoScript as) {
    if (as == null) return null;
    List<StepDto> stepDtos = new ArrayList<>();
    for (Step s : as.steps()) {
      stepDtos.add(new StepDto(s.waitPattern(), s.command()));
    }
    return new AutoScriptDto(as.timeoutMs(), stepDtos, as.enabled());
  }

  private TagNode emptyRoot() {
    return new TagNode.Folder(ROOT_ID, "root", new ArrayList<>());
  }

  public record ConfigDto(int version, List<TagNodeDto> tags) {}
  public record TagNodeDto(String type, UUID id, String name, String shellPath,
                           String shellArgs, String iconPath, String startPath,
                           boolean expanded, List<TagNodeDto> children,
                           AutoScriptDto autoScript) {}
  public record AutoScriptDto(int timeoutMs, List<StepDto> steps, boolean enabled) {}
  public record StepDto(String waitPattern, String command) {}
}