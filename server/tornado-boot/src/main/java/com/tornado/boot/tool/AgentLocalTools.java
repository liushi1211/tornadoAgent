package com.tornado.boot.tool;

import com.tornado.boot.rag.RagSearchService;
import com.tornado.boot.skill.SkillService;
import com.tornado.common.entity.Skill;
import com.tornado.common.entity.SkillFile;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 会话级本地工具（每请求 new，绑定 userId，天然租户隔离；不作为 Bean 注册）。
 */
public final class AgentLocalTools {

    private AgentLocalTools() {}

    /** read_skill：渐进披露，命中技能后读取 SKILL.md 全文 + 资源清单 */
    public static class ReadSkillTool {
        private final SkillService skillService;
        private final Long uid;

        public ReadSkillTool(SkillService skillService, Long uid) {
            this.skillService = skillService;
            this.uid = uid;
        }

        @Tool(name = "read_skill", description = "按技能名读取该技能 SKILL.md 全文与资源文件清单，用于渐进式加载技能说明")
        public String readSkill(@ToolParam(description = "技能 name（小写字母数字与连字符）") String name) {
            Skill s = skillService.content(uid, name);
            if (s == null) {
                return "未找到技能: " + name;
            }
            StringBuilder sb = new StringBuilder("# ").append(s.getName())
                    .append("\n").append(s.getDescription()).append("\n\n").append(s.getContentMd());
            List<SkillFile> fs = skillService.files(s.getId());
            if (!fs.isEmpty()) {
                sb.append("\n\n【技能包内资源文件】\n");
                for (SkillFile f : fs) {
                    sb.append("- ").append(f.getRelPath())
                            .append("  (").append(f.getFileType()).append(", ").append(f.getSizeBytes()).append("B)\n");
                }
                sb.append("脚本可先用 read_skill_file 查看内容，再用 run_skill_script 执行（执行需用户批准）。\n");
            }
            return sb.toString();
        }
    }

    /** read_skill_file：读取技能包内某个资源文件内容 */
    public static class ReadSkillFileTool {
        private final SkillService skillService;
        private final Long uid;

        public ReadSkillFileTool(SkillService skillService, Long uid) {
            this.skillService = skillService;
            this.uid = uid;
        }

        @Tool(name = "read_skill_file", description = "读取技能包内指定资源文件的文本内容（如脚本源码），path 来自 read_skill 的资源清单")
        public String read(@ToolParam(description = "技能 name") String name,
                           @ToolParam(description = "资源相对路径，如 scripts/count.js") String path) {
            Skill s = skillService.content(uid, name);
            if (s == null) {
                return "未找到技能: " + name;
            }
            SkillFile f = skillService.fileContent(uid, s.getId(), path == null ? "" : path.trim());
            return f == null ? "未找到资源文件: " + path : f.getContent();
        }
    }

    /** run_skill_script：执行技能包内脚本（服务端进程执行，必须经 HumanInTheLoopHook 人工批准） */
    public static class RunSkillScriptTool {
        private static final long TIMEOUT_SECONDS = 60;
        private static final int MAX_OUTPUT_CHARS = 4000;
        private static final List<String> ALLOWED_EXT = List.of("ps1", "js", "mjs", "cjs", "py", "sh");

        private final SkillService skillService;
        private final Long uid;

        public RunSkillScriptTool(SkillService skillService, Long uid) {
            this.skillService = skillService;
            this.uid = uid;
        }

        @Tool(name = "run_skill_script", description = "在沙箱目录执行技能包内的脚本资源并返回输出。"
                + "scriptPath 必须是 read_skill 清单中的相对路径；args 为空格分隔的命令行参数（可空）；执行属于服务端命令，需用户批准")
        public String run(@ToolParam(description = "技能 name") String name,
                          @ToolParam(description = "脚本相对路径，如 scripts/count.js") String scriptPath,
                          @ToolParam(description = "命令行参数，空格分隔；含空格的单个参数（如一段文本）必须用双引号整体包裹，可传空字符串") String args) {
            try {
                String rel = scriptPath == null ? "" : scriptPath.trim().replace('\\', '/');
                String ext = rel.contains(".") ? rel.substring(rel.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
                if (!ALLOWED_EXT.contains(ext)) {
                    return "不支持的脚本类型 ." + ext + "（允许: " + String.join("/", ALLOWED_EXT) + "）";
                }
                Path dir = skillService.materialize(uid, name.trim());
                Path script = dir.resolve(rel).normalize();
                if (!script.startsWith(dir) || !Files.exists(script)) {
                    return "脚本不存在或路径越界: " + rel;
                }
                List<String> cmd = new ArrayList<>();
                switch (ext) {
                    case "ps1" -> { cmd.add("powershell.exe"); cmd.add("-NoProfile"); cmd.add("-ExecutionPolicy"); cmd.add("Bypass"); cmd.add("-File"); }
                    case "js", "mjs", "cjs" -> cmd.add("node");
                    case "py" -> cmd.add("python");
                    default -> cmd.add("bash");
                }
                cmd.add(script.toString());
                cmd.addAll(tokenizeArgs(args));
                ProcessBuilder pb = new ProcessBuilder(cmd)
                        .directory(dir.toFile())
                        .redirectErrorStream(true);
                pb.environment().put("PYTHONIOENCODING", "utf-8");
                Process p = pb.start();
                String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    return "TIMEOUT: 脚本执行超过 " + TIMEOUT_SECONDS + "s 已终止。部分输出:\n" + truncate(output);
                }
                return "EXIT=" + p.exitValue() + "\n" + truncate(output);
            } catch (java.io.IOException e) {
                return "执行失败（解释器不可用或 IO 错误）: " + e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "执行被中断";
            } catch (Exception e) {
                return "执行失败: " + e.getMessage();
            }
        }

        /** 空格分隔 + 双引号包裹的参数切分，最多 8 个、单个 ≤256 字符 */
        private List<String> tokenizeArgs(String args) {
            List<String> out = new ArrayList<>();
            if (args == null || args.isBlank()) {
                return out;
            }
            StringBuilder cur = new StringBuilder();
            boolean inQuote = false;
            for (char c : args.toCharArray()) {
                if (c == '"') {
                    inQuote = !inQuote;
                } else if (c == ' ' && !inQuote) {
                    if (cur.length() > 0) {
                        out.add(cur.toString());
                        cur.setLength(0);
                    }
                } else {
                    cur.append(c);
                }
            }
            if (cur.length() > 0) {
                out.add(cur.toString());
            }
            if (out.size() > 8) {
                out = out.subList(0, 8);
            }
            return out.stream().map(s -> s.length() > 256 ? s.substring(0, 256) : s).collect(Collectors.toList());
        }

        private String truncate(String s) {
            if (s == null) {
                return "";
            }
            return s.length() <= MAX_OUTPUT_CHARS ? s : s.substring(0, MAX_OUTPUT_CHARS) + "\n...(输出截断)";
        }
    }

    /** kb_search：知识库检索工具，返回带 [doc#seq] 引用标注的上下文 */
    public static class KbSearchTool {
        private final RagSearchService ragSearchService;
        private final Long uid;

        public KbSearchTool(RagSearchService ragSearchService, Long uid) {
            this.ragSearchService = ragSearchService;
            this.uid = uid;
        }

        @Tool(name = "kb_search", description = "在用户个人知识库中检索与问题相关的资料片段，回答含引用标注")
        public String kbSearch(@ToolParam(description = "检索查询语句") String query) {
            return ragSearchService.searchContext(uid, query, 5);
        }
    }

    /** delete_uploaded_document：敏感写操作，挂 HumanInTheLoopHook 需人工批准 */
    public static class DeleteDocumentTool {
        private final RagSearchService ragSearchService;
        private final Long uid;

        public DeleteDocumentTool(RagSearchService ragSearchService, Long uid) {
            this.ragSearchService = ragSearchService;
            this.uid = uid;
        }

        @Tool(name = "delete_uploaded_document", description = "删除用户知识库中的指定文档（不可逆，需人工批准）")
        public String deleteDocument(@ToolParam(description = "文档 id（数字）") String docId) {
            try {
                return ragSearchService.deleteDocument(uid, Long.parseLong(docId.trim()));
            } catch (NumberFormatException e) {
                return "docId 需为数字，收到: " + docId;
            }
        }
    }
}
