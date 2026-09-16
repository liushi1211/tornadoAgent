package com.tornado.infrastructure.skill.gateway;

import com.tornado.client.error.BizException;
import com.tornado.client.error.ErrorCode;
import com.tornado.domain.skill.gateway.SkillScriptGateway;
import com.tornado.domain.skill.model.Skill;
import com.tornado.domain.skill.model.SkillFile;
import com.tornado.domain.skill.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 技能脚本沙箱网关实现：物化落盘 + 解释器执行 + Node 依赖自愈。
 * 从原 SkillService.materialize 与 AgentLocalTools.RunSkillScriptTool 收编而来，语义原样保留。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillScriptGatewayImpl implements SkillScriptGateway {

    private static final long TIMEOUT_SECONDS = 60;
    private static final long INSTALL_TIMEOUT_SECONDS = 180;
    private static final int MAX_RETRY_FOR_MISSING_DEPS = 3;
    private static final int MAX_OUTPUT_CHARS = 4000;
    private static final List<String> ALLOWED_EXT = List.of("ps1", "js", "mjs", "cjs", "py", "sh");
    private static final Pattern MISSING_MODULE = Pattern.compile("Cannot find module '([^']+)'");
    private static final Pattern SAFE_PKG_NAME = Pattern.compile("^[@][a-zA-Z0-9._-]+/[a-zA-Z0-9._-]+$|^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$");
    private static final Set<String> NODE_CORE = Set.of("fs", "path", "crypto", "http", "https", "os", "util",
            "events", "stream", "url", "zlib", "net", "tls", "dns", "v8", "vm", "assert", "buffer",
            "child_process", "module", "string_decoder", "timers", "tty", "dgram", "readline", "repl",
            "querystring", "punycode", "worker_threads", "perf_hooks", "async_hooks", "inspector",
            "trace_events", "constants", "process");

    private final SkillRepository skillRepository;

    @Value("${saa.skill.sandbox-dir:./skill-sandbox}")
    private String sandboxDir;

    @Override
    public String run(Long uid, String skillName, String scriptPath, String args) {
        try {
            String rel = scriptPath == null ? "" : scriptPath.trim().replace('\\', '/');
            String ext = rel.contains(".") ? rel.substring(rel.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
            if (!ALLOWED_EXT.contains(ext)) {
                return "不支持的脚本类型 ." + ext + "（允许: " + String.join("/", ALLOWED_EXT) + "）";
            }
            Path dir = materialize(uid, skillName.trim());
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

            boolean nodeScript = ext.equals("js") || ext.equals("mjs") || ext.equals("cjs");
            StringBuilder notes = new StringBuilder();
            if (nodeScript) {
                if (Files.exists(dir.resolve("package.json")) && !Files.exists(dir.resolve("node_modules"))) {
                    if (npm(dir, "install", "--omit=dev") == null) {
                        return "依赖安装失败（npm install 超时或出错），脚本无法执行";
                    }
                    notes.append("[preflight] npm install 依据 package.json 安装依赖\n");
                }
                for (int attempt = 0; attempt <= MAX_RETRY_FOR_MISSING_DEPS; attempt++) {
                    ExecResult r = exec(cmd, dir, TIMEOUT_SECONDS);
                    if (r.timedOut) {
                        return "TIMEOUT: 脚本执行超过 " + TIMEOUT_SECONDS + "s 已终止。部分输出:\n" + truncate(r.output);
                    }
                    String pkg = (r.exit == 0 || attempt == MAX_RETRY_FOR_MISSING_DEPS) ? null : missingNpmPackage(r.output);
                    if (pkg == null) {
                        return withNotes(notes, "EXIT=" + r.exit + "\n" + truncate(r.output));
                    }
                    if (npm(dir, "install", "--no-save", pkg) == null) {
                        return withNotes(notes, "依赖 " + pkg + " 自动安装失败。原始输出:\nEXIT=" + r.exit + "\n" + truncate(r.output));
                    }
                    notes.append("[auto-install] 检测到缺失模块，已 npm install --no-save ").append(pkg).append(" 并重试\n");
                }
            }
            ExecResult r = exec(cmd, dir, TIMEOUT_SECONDS);
            if (r.timedOut) {
                return "TIMEOUT: 脚本执行超过 " + TIMEOUT_SECONDS + "s 已终止。部分输出:\n" + truncate(r.output);
            }
            return withNotes(notes, "EXIT=" + r.exit + "\n" + truncate(r.output));
        } catch (java.io.IOException e) {
            return "执行失败（解释器不可用或 IO 错误）: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "执行被中断";
        } catch (Exception e) {
            return "执行失败: " + e.getMessage();
        }
    }

    @Override
    public void cleanup(Long uid, String skillName) {
        try {
            Path root = sandboxRoot().resolve(String.valueOf(uid)).resolve(skillName);
            if (Files.exists(root)) {
                try (var walk = Files.walk(root)) {
                    for (Path p : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                        Files.deleteIfExists(p);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("清理沙箱目录失败（忽略）: {}", e.getMessage());
        }
    }

    private Path materialize(Long uid, String skillName) throws java.io.IOException {
        Skill s = skillRepository.findByUserAndName(uid, skillName);
        if (s == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "技能不存在: " + skillName);
        }
        Path dir = sandboxRoot().resolve(String.valueOf(uid)).resolve(skillName).normalize();
        if (!dir.startsWith(sandboxRoot().normalize())) {
            throw new BizException(ErrorCode.SKILL_PARSE_FAILED, "非法技能路径");
        }
        Files.createDirectories(dir);
        for (SkillFile f : skillRepository.files(s.getId())) {
            Path p = dir.resolve(f.getRelPath()).normalize();
            if (!p.startsWith(dir)) {
                throw new BizException(ErrorCode.SKILL_PARSE_FAILED, "资源路径越界: " + f.getRelPath());
            }
            Files.createDirectories(p.getParent());
            byte[] raw = f.getContent().getBytes(StandardCharsets.UTF_8);
            byte[] bytes = raw;
            if (f.getRelPath().toLowerCase().endsWith(".ps1")) {
                byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
                bytes = Arrays.copyOf(bom, bom.length + raw.length);
                System.arraycopy(raw, 0, bytes, bom.length, raw.length);
            }
            Files.write(p, bytes);
        }
        return dir;
    }

    private Path sandboxRoot() {
        return Paths.get(sandboxDir).toAbsolutePath().normalize();
    }

    private record ExecResult(int exit, String output, boolean timedOut) {}

    private ExecResult exec(List<String> cmd, Path dir, long timeoutSeconds) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        Process p = pb.start();
        String output = new String(readAll(p), StandardCharsets.UTF_8);
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return new ExecResult(-1, output, true);
        }
        return new ExecResult(p.exitValue(), output, false);
    }

    private byte[] readAll(Process p) throws java.io.IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            p.getInputStream().transferTo(bos);
            return bos.toByteArray();
        }
    }

    private String npm(Path dir, String... npmArgs) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(onWindows() ? "npm.cmd" : "npm");
        cmd.addAll(Arrays.asList(npmArgs));
        cmd.add("--registry=https://registry.npmmirror.com");
        cmd.add("--no-audit");
        cmd.add("--no-fund");
        cmd.add("--loglevel=error");
        ExecResult r = exec(cmd, dir, INSTALL_TIMEOUT_SECONDS);
        return r.timedOut ? null : (r.exit == 0 ? r.output : null);
    }

    private boolean onWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private String missingNpmPackage(String output) {
        if (output == null) {
            return null;
        }
        Matcher m = MISSING_MODULE.matcher(output);
        while (m.find()) {
            String mod = m.group(1);
            if (mod.startsWith(".") || NODE_CORE.contains(mod) || mod.contains("..")) {
                continue;
            }
            String pkg = mod.startsWith("@")
                    ? String.join("/", mod.split("/").length > 1 ? Arrays.copyOf(mod.split("/"), 2) : new String[]{mod})
                    : mod.split("/")[0];
            return SAFE_PKG_NAME.matcher(pkg).matches() ? pkg : null;
        }
        return null;
    }

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

    private String withNotes(StringBuilder notes, String body) {
        return notes.length() == 0 ? body : notes + body;
    }
}
