package com.opencodebuddy.skill;

import com.opencodebuddy.bridge.NodeDetector;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Skills service.
 * <p>
 * Manages skill import, deletion, enabling, and disabling.
 * <p>
 * active skill 目录同时覆盖 opencode v1（单数）与 v2（复数）：
 * - Global: ~/.claude/skills, ~/.agents/skills,
 *           ~/.config/opencode/skill, ~/.config/opencode/skills,
 *           ~/.opencode/skill, ~/.opencode/skills
 * - Local: {workspace}/.claude/skills, {workspace}/.agents/skills,
 *          {workspace}/.opencode/skill, {workspace}/.opencode/skills
 * <p>
 * v2 的发现规则（https://opencode.ai/v2/docs/skills）：
 * - 根级 *.md 也算 skill（type=file）
 * - {dir}/SKILL.md 是目录型 skill
 * - 分组目录（skills/teams/release/SKILL.md）最多向下找 6 层
 * - 跳过隐藏项与 node_modules
 * <p>
 * Management directories (disabled skills):
 * - Global: ~/.opencodebuddy/skills/global
 * - Local: ~/.opencodebuddy/skills/{project-path-hash}
 * <p>
 * {@code name} 是 frontmatter 的显示名，{@code skillId} 才是磁盘上的目录名 /
 * 文件名 —— 删除、启停都必须用 skillId，否则 v2 里 name 与目录名不一致时会定位不到。
 */
public class SkillService {
    private static final Logger LOG = Logger.getInstance(SkillService.class);
    private static final Gson gson = new Gson();

    // Management directory root path
    private static final String CONFIG_DIR_NAME = ".opencodebuddy";
    private static final String SKILLS_DIR_NAME = "skills";
    private static final String GLOBAL_DIR_NAME = "global";

    /** v2 允许分组目录，最多向下找 6 层。 */
    private static final int MAX_SCAN_DEPTH = 6;

    /** SKILL.md 允许的大小写变体。 */
    private static final String[] SKILL_FILE_NAMES = {"SKILL.md", "skill.md"};

    /** Safe skill name pattern: alphanumeric, dots, hyphens, underscores only. */
    private static final Pattern SAFE_NAME = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]*$");

    /**
     * Validate that a skill name is safe (no path traversal characters).
     */
    private static boolean isSafeSkillName(String name) {
        if (name == null || name.isEmpty()) { return false; }
        if (name.contains("..") || name.contains("/") || name.contains("\\") || name.contains("\0")) { return false; }
        return SAFE_NAME.matcher(name).matches();
    }

    /**
     * Validate a path-derived skillId (目录名或根级 md 去扩展名)。
     * 真实文件名可能含大写、下划线、点号，所以只拦路径穿越。
     */
    private static boolean isSafeSkillId(String skillId) {
        if (skillId == null || skillId.isEmpty() || skillId.length() > 128) { return false; }
        if (".".equals(skillId) || "..".equals(skillId)) { return false; }
        return !skillId.contains("/") && !skillId.contains("\\") && !skillId.contains("\0");
    }

    // ==================== Active Directories (read by Claude / OpenCode) ====================

    /**
     * Gets the primary global active skills directory (~/.config/opencode/skill).
     */
    public static String getGlobalSkillsDir() {
        String homeDir = NodeDetector.resolveHomeForFileOps();
        return Paths.get(homeDir, ".config", "opencode", "skill").toString();
    }

    /**
     * Gets all candidate global active skills directories, ordered from lowest to
     * highest priority: later directories override same-id skills (v2 的来源覆盖顺序：
     * .claude → .agents → ~/.config/opencode → ~/.opencode)。
     */
    public static List<String> getGlobalActiveSkillsDirs() {
        String homeDir = NodeDetector.resolveHomeForFileOps();
        List<String> dirs = new ArrayList<>();
        dirs.add(Paths.get(homeDir, ".claude", "skills").toString());
        dirs.add(Paths.get(homeDir, ".agents", "skills").toString());
        dirs.add(Paths.get(homeDir, ".config", "opencode", "skill").toString());
        dirs.add(Paths.get(homeDir, ".config", "opencode", "skills").toString());
        dirs.add(Paths.get(homeDir, ".opencode", "skill").toString());
        dirs.add(Paths.get(homeDir, ".opencode", "skills").toString());
        return dirs;
    }

    /**
     * Gets the primary local active skills directory ({workspace}/.opencode/skill).
     */
    public static String getLocalSkillsDir(String workspaceRoot) {
        if (workspaceRoot == null || workspaceRoot.isEmpty()) {
            return null;
        }
        return Paths.get(workspaceRoot, ".opencode", "skill").toString();
    }

    /**
     * Gets all candidate local active skills directories (same priority rule as global).
     */
    public static List<String> getLocalActiveSkillsDirs(String workspaceRoot) {
        List<String> dirs = new ArrayList<>();
        if (workspaceRoot != null && !workspaceRoot.isEmpty()) {
            dirs.add(Paths.get(workspaceRoot, ".claude", "skills").toString());
            dirs.add(Paths.get(workspaceRoot, ".agents", "skills").toString());
            dirs.add(Paths.get(workspaceRoot, ".opencode", "skill").toString());
            dirs.add(Paths.get(workspaceRoot, ".opencode", "skills").toString());
        }
        return dirs;
    }

    /**
     * 导入 / 启用时的目标目录：优先沿用磁盘上已存在的布局（v1 单数或 v2 复数）。
     */
    private static String getPrimaryActiveSkillsDir(String scope, String workspaceRoot) {
        List<String> candidates = new ArrayList<>();
        if ("global".equals(scope)) {
            String homeDir = NodeDetector.resolveHomeForFileOps();
            candidates.add(Paths.get(homeDir, ".config", "opencode", "skills").toString());
            candidates.add(Paths.get(homeDir, ".config", "opencode", "skill").toString());
        } else {
            if (workspaceRoot == null || workspaceRoot.isEmpty()) {
                return null;
            }
            candidates.add(Paths.get(workspaceRoot, ".opencode", "skills").toString());
            candidates.add(Paths.get(workspaceRoot, ".opencode", "skill").toString());
        }
        for (String candidate : candidates) {
            if (new File(candidate).isDirectory()) {
                return candidate;
            }
        }
        return candidates.get(0);
    }

    // ==================== Management Directories (storage for disabled skills) ====================

    /**
     * Gets the management directory root path (~/.opencodebuddy/skills).
     */
    private static String getManagementRootDir() {
        String homeDir = NodeDetector.resolveHomeForFileOps();
        return Paths.get(homeDir, CONFIG_DIR_NAME, SKILLS_DIR_NAME).toString();
    }

    /**
     * Gets the global skills management directory (~/.opencodebuddy/skills/global).
     */
    public static String getGlobalManagementDir() {
        return Paths.get(getManagementRootDir(), GLOBAL_DIR_NAME).toString();
    }

    /**
     * Gets the local skills management directory (~/.opencodebuddy/skills/{project-path-hash}).
     * Uses a hash of the project path as the subdirectory name to avoid special character issues.
     */
    public static String getLocalManagementDir(String workspaceRoot) {
        if (workspaceRoot == null || workspaceRoot.isEmpty()) {
            return null;
        }
        // Convert the project path to a safe directory name (hash + project name)
        String projectName = Paths.get(workspaceRoot).getFileName().toString();
        String pathHash = Integer.toHexString(workspaceRoot.hashCode());
        String safeDirName = projectName + "_" + pathHash;
        return Paths.get(getManagementRootDir(), safeDirName).toString();
    }

    /**
     * Ensures that the directory exists, creating it if necessary.
     */
    private static boolean ensureDirectoryExists(String dirPath) {
        if (dirPath == null) { return false; }
        File dir = new File(dirPath);
        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            if (created) {
                LOG.info("[Skills] 创建目录: " + dirPath);
            }
            return created;
        }
        return true;
    }

    /**
     * Gets all skills (global + local), including both enabled and disabled.
     */
    public static JsonObject getAllSkills(String workspaceRoot) {
        JsonObject result = new JsonObject();
        result.add("global", getAllSkillsByScope("global", workspaceRoot));
        result.add("local", getAllSkillsByScope("local", workspaceRoot));
        return result;
    }

    /**
     * Gets all skills for a given scope (including both enabled and disabled).
     * 去重按 skillId：活动目录后扫到的覆盖先扫到的，停用项不覆盖活动项。
     */
    public static JsonObject getAllSkillsByScope(String scope, String workspaceRoot) {
        JsonObject allSkills = new JsonObject();
        Map<String, JsonObject> bySkillId = new LinkedHashMap<>();

        // 1. Scan active directories (enabled = true)
        List<String> activeDirs = "global".equals(scope)
                ? getGlobalActiveSkillsDirs()
                : getLocalActiveSkillsDirs(workspaceRoot);
        for (String activeDir : activeDirs) {
            JsonObject activeSkills = scanSkillsDirectory(activeDir, scope, true);
            for (String key : activeSkills.keySet()) {
                JsonObject skill = activeSkills.getAsJsonObject(key);
                if (skill != null) {
                    bySkillId.put(skillIdOf(skill, key), skill);
                }
            }
        }

        // 2. Scan the management directory (enabled = false)
        String managementDir = "global".equals(scope) ? getGlobalManagementDir() : getLocalManagementDir(workspaceRoot);
        if (managementDir != null) {
            JsonObject disabledSkills = scanSkillsDirectory(managementDir, scope, false);
            for (String key : disabledSkills.keySet()) {
                JsonObject skill = disabledSkills.getAsJsonObject(key);
                if (skill != null) {
                    bySkillId.putIfAbsent(skillIdOf(skill, key), skill);
                }
            }
        }

        for (JsonObject skill : bySkillId.values()) {
            allSkills.add(skill.get("id").getAsString(), skill);
        }
        return allSkills;
    }

    /**
     * Gets skills for a given scope (active only, for backward compatibility).
     */
    public static JsonObject getSkillsByScope(String scope, String workspaceRoot) {
        String dir = "global".equals(scope) ? getGlobalSkillsDir() : getLocalSkillsDir(workspaceRoot);
        if (dir == null) {
            LOG.warn("[Skills] 无法获取 " + scope + " Skills 目录");
            return new JsonObject();
        }
        return scanSkillsDirectory(dir, scope, true);
    }

    /**
     * Scans a directory to discover skills.
     *
     * @param dirPath directory path
     * @param scope   scope (global/local)
     * @param enabled whether these skills are in the enabled state
     */
    private static JsonObject scanSkillsDirectory(String dirPath, String scope, boolean enabled) {
        JsonObject skills = new JsonObject();
        File dir = new File(dirPath);

        if (!dir.exists() || !dir.isDirectory()) {
            LOG.info("[Skills] " + scope + " Skills 目录不存在: " + dirPath);
            return skills;
        }

        scanSkillsTree(dir, dir, scope, enabled, 0, skills);
        LOG.info("[Skills] 从 " + scope + " 目录获取到 " + skills.size() + " 个 Skills (enabled="
                + enabled + "): " + dirPath);
        return skills;
    }

    /**
     * 递归发现 skill：根级 *.md / {dir}/SKILL.md / 分组目录（深度 &lt;= MAX_SCAN_DEPTH）。
     */
    private static void scanSkillsTree(
            File sourceDir,
            File dir,
            String scope,
            boolean enabled,
            int depth,
            JsonObject out) {
        File[] entries = dir.listFiles();
        if (entries == null) {
            return;
        }
        Arrays.sort(entries, Comparator.comparing(File::getName));

        for (File entry : entries) {
            String name = entry.getName();
            // Skip hidden files/directories and dependency trees
            if (name.startsWith(".") || "node_modules".equals(name)) {
                continue;
            }

            if (entry.isFile()) {
                // v2：只有根级 *.md 才是 file skill，嵌套文档不算
                if (depth == 0 && name.toLowerCase(Locale.ROOT).endsWith(".md")) {
                    String skillId = name.substring(0, name.length() - 3);
                    if (isSafeSkillId(skillId)) {
                        addSkill(out, scope, enabled, skillId, entry, "file", sourceDir);
                    }
                }
                continue;
            }

            if (!entry.isDirectory()) {
                continue;
            }

            if (hasSkillFile(entry)) {
                if (isSafeSkillId(name)) {
                    addSkill(out, scope, enabled, name, entry, "directory", sourceDir);
                }
                // skill 目录内部的 md 是文档/示例，不再向下挖
                continue;
            }

            if (depth + 1 <= MAX_SCAN_DEPTH) {
                scanSkillsTree(sourceDir, entry, scope, enabled, depth + 1, out);
            }
        }
    }

    private static boolean hasSkillFile(File dir) {
        for (String name : SKILL_FILE_NAMES) {
            if (new File(dir, name).isFile()) {
                return true;
            }
        }
        return false;
    }

    private static void addSkill(
            JsonObject out,
            String scope,
            boolean enabled,
            String skillId,
            File path,
            String type,
            File sourceDir) {
        String id = scope + "-" + skillId + (enabled ? "" : "-disabled");
        if (out.has(id)) {
            return;
        }

        JsonObject skill = new JsonObject();
        skill.addProperty("id", id);
        skill.addProperty("skillId", skillId);
        skill.addProperty("type", type);
        skill.addProperty("scope", scope);
        skill.addProperty("path", path.getAbsolutePath());
        skill.addProperty("enabled", enabled);
        skill.addProperty("sourceDir", sourceDir.getAbsolutePath());

        // Parse frontmatter for the display name and description
        SkillFrontmatterParser.SkillMetadata metadata =
                SkillFrontmatterParser.parse(path.toPath());
        if (metadata != null) {
            skill.addProperty("name", metadata.name());
            skill.addProperty("description", metadata.description());
        } else {
            // Keep the skill in the management list but mark it as invalid
            skill.addProperty("name", skillId);
            skill.addProperty("warning", "invalid_frontmatter");
        }

        try {
            BasicFileAttributes attrs = Files.readAttributes(path.toPath(), BasicFileAttributes.class);
            skill.addProperty("createdAt", attrs.creationTime().toString());
            skill.addProperty("modifiedAt", attrs.lastModifiedTime().toString());
        } catch (IOException e) {
            LOG.warn("[Skills] 读取文件属性失败: " + path.getAbsolutePath());
        }

        out.add(id, skill);
    }

    private static String skillIdOf(JsonObject skill, String fallback) {
        if (skill.has("skillId") && !skill.get("skillId").isJsonNull()) {
            return skill.get("skillId").getAsString();
        }
        return fallback;
    }

    /**
     * Extracts the description from a skill directory's SKILL.md.
     * Delegates to SkillFrontmatterParser for standard YAML parsing.
     */
    private static String extractDescription(String skillPath, boolean isDirectory) {
        if (!isDirectory) {
            return null;
        }
        return SkillFrontmatterParser.extractDescription(Path.of(skillPath));
    }

    /**
     * Imports skills (copies files/directories to the skills directory).
     *
     * @param sourcePaths   list of source file/directory paths
     * @param scope         scope (global/local)
     * @param workspaceRoot workspace root directory
     * @return import result
     */
    public static JsonObject importSkills(List<String> sourcePaths, String scope, String workspaceRoot) {
        JsonObject result = new JsonObject();
        JsonArray imported = new JsonArray();
        JsonArray errors = new JsonArray();

        String targetDir = getPrimaryActiveSkillsDir(scope, workspaceRoot);
        if (targetDir == null) {
            result.addProperty("success", false);
            result.addProperty("error", "无法获取 " + scope + " Skills 目录");
            return result;
        }

        // Ensure the target directory exists
        File targetDirFile = new File(targetDir);
        if (!targetDirFile.exists()) {
            if (!targetDirFile.mkdirs()) {
                result.addProperty("success", false);
                result.addProperty("error", "无法创建 Skills 目录: " + targetDir);
                return result;
            }
            LOG.info("[Skills] 创建 " + scope + " Skills 目录: " + targetDir);
        }

        for (String sourcePath : sourcePaths) {
            File source = new File(sourcePath);
            if (!source.exists()) {
                JsonObject err = new JsonObject();
                err.addProperty("path", sourcePath);
                err.addProperty("error", "源路径不存在");
                errors.add(err);
                continue;
            }

            String name = source.getName();
            File targetPath = new File(targetDir, name);

            // Check if a skill with the same name already exists
            if (targetPath.exists()) {
                JsonObject err = new JsonObject();
                err.addProperty("path", sourcePath);
                err.addProperty("error", "已存在同名 Skill: " + name);
                errors.add(err);
                continue;
            }

            try {
                // Copy file/directory
                if (source.isDirectory()) {
                    copyDirectory(source.toPath(), targetPath.toPath());
                } else {
                    Files.copy(source.toPath(), targetPath.toPath());
                }

                // Build the import result
                String type = source.isDirectory() ? "directory" : "file";
                String id = scope + "-" + name;
                String description = extractDescription(targetPath.getAbsolutePath(), source.isDirectory());

                JsonObject skill = new JsonObject();
                skill.addProperty("id", id);
                skill.addProperty("skillId", name);
                skill.addProperty("name", name);
                skill.addProperty("type", type);
                skill.addProperty("scope", scope);
                skill.addProperty("path", targetPath.getAbsolutePath());
                skill.addProperty("enabled", true);
                if (description != null) {
                    skill.addProperty("description", description);
                }

                imported.add(skill);
                LOG.info("[Skills] 成功导入 " + scope + " Skill: " + name);

            } catch (IOException e) {
                JsonObject err = new JsonObject();
                err.addProperty("path", sourcePath);
                err.addProperty("error", "复制失败: " + e.getMessage());
                errors.add(err);
                LOG.error("[Skills] 导入 Skill 失败: " + e.getMessage());
            }
        }

        result.addProperty("success", errors.size() == 0 || imported.size() > 0);
        result.addProperty("count", imported.size());
        result.addProperty("total", sourcePaths.size());
        result.add("imported", imported);
        if (errors.size() > 0) {
            result.add("errors", errors);
        }

        return result;
    }

    /**
     * Deletes a skill (supports deleting both enabled and disabled skills).
     *
     * @param skillId       path-derived id (目录名 / 根级 md 去扩展名)
     * @param scope         scope (global/local)
     * @param enabled       whether the skill is currently enabled
     * @param workspaceRoot workspace root directory
     */
    public static JsonObject deleteSkill(String skillId, String scope, boolean enabled, String workspaceRoot) {
        return deleteSkill(skillId, scope, enabled, workspaceRoot, null);
    }

    /**
     * Deletes a skill, preferring the exact path the webview already holds.
     *
     * @param pathHint absolute path from the UI; only honoured when it lives inside
     *                 one of the allowed roots for this scope/state
     */
    public static JsonObject deleteSkill(
            String skillId,
            String scope,
            boolean enabled,
            String workspaceRoot,
            String pathHint) {
        JsonObject result = new JsonObject();

        if (!isSafeSkillId(skillId)) {
            result.addProperty("success", false);
            result.addProperty("error", "Invalid skill id: " + skillId);
            return result;
        }

        List<String> roots = enabled
                ? ("global".equals(scope) ? getGlobalActiveSkillsDirs() : getLocalActiveSkillsDirs(workspaceRoot))
                : Collections.singletonList(
                        "global".equals(scope) ? getGlobalManagementDir() : getLocalManagementDir(workspaceRoot));

        File targetPath = locateSkillFile(skillId, roots, pathHint);
        if (targetPath == null || !targetPath.exists()) {
            result.addProperty("success", false);
            result.addProperty("error", "Skill 不存在: " + skillId);
            return result;
        }

        try {
            if (targetPath.isDirectory()) {
                deleteDirectory(targetPath.toPath());
            } else {
                Files.delete(targetPath.toPath());
            }
            result.addProperty("success", true);
            LOG.info("[Skills] 成功删除 " + scope + " Skill: " + skillId + " (enabled=" + enabled + ")");
        } catch (IOException e) {
            result.addProperty("success", false);
            result.addProperty("error", "删除失败: " + e.getMessage());
            LOG.error("[Skills] 删除 Skill 失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * Legacy delete method (for backward compatibility).
     */
    public static JsonObject deleteSkill(String id, String scope, String workspaceRoot) {
        // Extract the name from the ID (format: {scope}-{skillId} or {scope}-{skillId}-disabled)
        String skillId = id.replace(scope + "-", "");
        boolean enabled = true;
        if (skillId.endsWith("-disabled")) {
            skillId = skillId.substring(0, skillId.length() - "-disabled".length());
            enabled = false;
        }
        return deleteSkill(skillId, scope, enabled, workspaceRoot, null);
    }

    /**
     * Enables a skill (moves it from the management directory to the active directory).
     */
    public static JsonObject enableSkill(String skillId, String scope, String workspaceRoot) {
        return enableSkill(skillId, scope, workspaceRoot, null);
    }

    /**
     * Enables a skill, preferring the exact path the webview already holds.
     */
    public static JsonObject enableSkill(String skillId, String scope, String workspaceRoot, String pathHint) {
        if (!isSafeSkillId(skillId)) {
            JsonObject result = new JsonObject();
            result.addProperty("success", false);
            result.addProperty("error", "Invalid skill id: " + skillId);
            return result;
        }
        String sourceDir = "global".equals(scope) ? getGlobalManagementDir() : getLocalManagementDir(workspaceRoot);
        String targetDir = getPrimaryActiveSkillsDir(scope, workspaceRoot);
        return moveSkill(
                skillId,
                sourceDir == null ? Collections.emptyList() : Collections.singletonList(sourceDir),
                targetDir,
                scope,
                true,
                pathHint);
    }

    /**
     * Disables a skill (moves it from the active directory to the management directory).
     */
    public static JsonObject disableSkill(String skillId, String scope, String workspaceRoot) {
        return disableSkill(skillId, scope, workspaceRoot, null);
    }

    /**
     * Disables a skill, preferring the exact path the webview already holds.
     */
    public static JsonObject disableSkill(String skillId, String scope, String workspaceRoot, String pathHint) {
        if (!isSafeSkillId(skillId)) {
            JsonObject result = new JsonObject();
            result.addProperty("success", false);
            result.addProperty("error", "Invalid skill id: " + skillId);
            return result;
        }
        List<String> activeDirs = "global".equals(scope)
                ? getGlobalActiveSkillsDirs()
                : getLocalActiveSkillsDirs(workspaceRoot);
        String targetDir = "global".equals(scope) ? getGlobalManagementDir() : getLocalManagementDir(workspaceRoot);
        return moveSkill(skillId, activeDirs, targetDir, scope, false, pathHint);
    }

    /**
     * Moves a skill between the active and management directories.
     *
     * @param skillId    path-derived id
     * @param sourceDirs source candidate directories
     * @param targetDir  destination directory
     * @param scope      scope (global/local)
     * @param enabled    state after the move
     * @param pathHint   optional exact path from the UI
     */
    private static JsonObject moveSkill(
            String skillId,
            List<String> sourceDirs,
            String targetDir,
            String scope,
            boolean enabled,
            String pathHint) {
        JsonObject result = new JsonObject();

        if (targetDir == null) {
            result.addProperty("success", false);
            result.addProperty("error", "无法获取 " + scope + " Skills 目录");
            return result;
        }

        File sourceFile = locateSkillFile(skillId, sourceDirs, pathHint);
        if (sourceFile == null || !sourceFile.exists()) {
            result.addProperty("success", false);
            result.addProperty("error", "Skill 不存在: " + skillId);
            return result;
        }

        boolean isDirectory = sourceFile.isDirectory();
        File target = new File(targetDir, isDirectory ? skillId : skillId + ".md");

        // Check if a skill with the same name already exists at the target
        if (target.exists()) {
            result.addProperty("success", false);
            result.addProperty("error", enabled
                    ? "A skill with the same name already exists in the active directory: " + skillId
                    : "A skill with the same name already exists in the management directory: " + skillId);
            result.addProperty("conflict", true);
            return result;
        }

        if (!ensureDirectoryExists(targetDir)) {
            result.addProperty("success", false);
            result.addProperty("error", "Unable to create target directory: " + targetDir);
            return result;
        }

        try {
            Files.move(sourceFile.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // If ATOMIC_MOVE fails (cross-filesystem), fall back to copy-then-delete
            try {
                if (isDirectory) {
                    copyDirectory(sourceFile.toPath(), target.toPath());
                    deleteDirectory(sourceFile.toPath());
                } else {
                    Files.copy(sourceFile.toPath(), target.toPath());
                    Files.delete(sourceFile.toPath());
                }
            } catch (IOException e2) {
                result.addProperty("success", false);
                result.addProperty("error", "Move failed: " + e2.getMessage());
                LOG.error("[Skills] Failed to move Skill: " + e2.getMessage());
                return result;
            }
        }

        result.addProperty("success", true);
        result.addProperty("skillId", skillId);
        result.addProperty("name", skillId);
        result.addProperty("scope", scope);
        result.addProperty("enabled", enabled);
        result.addProperty("path", target.getAbsolutePath());
        LOG.info("[Skills] 成功" + (enabled ? "启用" : "停用") + " " + scope + " Skill: " + skillId);
        return result;
    }

    /**
     * Locates a skill by its path-derived id inside the given candidate roots.
     * `pathHint` wins when it is inside one of the roots and exists.
     */
    private static File locateSkillFile(String skillId, List<String> roots, String pathHint) {
        if (pathHint != null && !pathHint.isEmpty() && !pathHint.contains("..")) {
            File hint = new File(pathHint);
            if (hint.exists() && isInsideAnyRoot(hint, roots)) {
                return hint;
            }
        }
        for (String root : roots) {
            if (root == null) {
                continue;
            }
            File dirCandidate = new File(root, skillId);
            if (dirCandidate.exists()) {
                return dirCandidate;
            }
            File fileCandidate = new File(root, skillId + ".md");
            if (fileCandidate.exists()) {
                return fileCandidate;
            }
        }
        return null;
    }

    private static boolean isInsideAnyRoot(File path, List<String> roots) {
        Path normalized = path.toPath().toAbsolutePath().normalize();
        for (String root : roots) {
            if (root == null) {
                continue;
            }
            Path base = Paths.get(root).toAbsolutePath().normalize();
            if (normalized.startsWith(base)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Toggles the enabled state of a skill.
     */
    public static JsonObject toggleSkill(String skillId, String scope, boolean currentEnabled, String workspaceRoot) {
        return toggleSkill(skillId, scope, currentEnabled, workspaceRoot, null);
    }

    /**
     * Toggles the enabled state of a skill, preferring the exact path from the UI.
     */
    public static JsonObject toggleSkill(
            String skillId,
            String scope,
            boolean currentEnabled,
            String workspaceRoot,
            String pathHint) {
        if (currentEnabled) {
            return disableSkill(skillId, scope, workspaceRoot, pathHint);
        } else {
            return enableSkill(skillId, scope, workspaceRoot, pathHint);
        }
    }

    /**
     * Recursively copies a directory.
     */
    private static void copyDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path targetDir = target.resolve(source.relativize(dir));
                Files.createDirectories(targetDir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path targetFile = target.resolve(source.relativize(file));
                Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Recursively deletes a directory.
     */
    private static void deleteDirectory(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
