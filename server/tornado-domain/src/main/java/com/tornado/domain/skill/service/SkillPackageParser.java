package com.tornado.domain.skill.service;

import com.tornado.client.error.BizException;
import com.tornado.client.error.ErrorCode;
import com.tornado.domain.skill.model.SkillFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * .skill/.zip 技能包解析（纯领域逻辑，仅用 JDK）。
 * 产出 SKILL.md 文本 + 资源文件列表；zip-slip 防护、大小/数量上限、二进制拒绝、公共顶层目录剥离。
 */
public final class SkillPackageParser {

    private static final int MAX_FILE_BYTES = 256 * 1024;
    private static final int MAX_PKG_FILES = 50;
    private static final int MAX_PKG_BYTES = 2 * 1024 * 1024;
    private static final Set<String> SCRIPT_EXT = Set.of("ps1", "py", "js", "mjs", "cjs", "sh", "bat", "cmd", "ts");
    private static final Set<String> DOC_EXT = Set.of("md", "txt", "json", "yaml", "yml", "csv");

    private SkillPackageParser() {}

    /** 解析结果：SKILL.md 正文 + 其余资源 */
    public record ParsedPackage(String skillMd, List<SkillFile> files) {}

    public static ParsedPackage parse(byte[] bytes) {
        List<SkillFile> out = new ArrayList<>();
        long total = 0;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().replace('\\', '/');
                if (name.contains("..")) {
                    throw new BizException(ErrorCode.SKILL_PARSE_FAILED, "包内非法路径: " + name);
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                long size = 0;
                while ((n = zin.read(buf)) > 0) {
                    size += n;
                    if (size > MAX_FILE_BYTES) {
                        throw new BizException(ErrorCode.SKILL_PARSE_FAILED, "文件超过 256KB: " + name);
                    }
                    bos.write(buf, 0, n);
                }
                byte[] content = bos.toByteArray();
                String text = new String(content, StandardCharsets.UTF_8);
                if (looksBinary(content, text)) {
                    throw new BizException(ErrorCode.SKILL_PARSE_FAILED, "暂只支持文本资源，二进制文件被拒绝: " + name);
                }
                total += size;
                if (total > MAX_PKG_BYTES || out.size() >= MAX_PKG_FILES) {
                    throw new BizException(ErrorCode.SKILL_PARSE_FAILED, "技能包过大或文件过多");
                }
                SkillFile f = new SkillFile();
                f.setRelPath(name);
                f.setContent(text);
                f.setFileType(classify(name));
                f.setSizeBytes((int) size);
                out.add(f);
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.SKILL_PARSE_FAILED, ".skill 包解析失败: " + e.getMessage());
        }
        if (out.isEmpty()) {
            throw new BizException(ErrorCode.SKILL_PARSE_FAILED, ".skill 包为空");
        }
        stripCommonRoot(out);
        SkillFile md = out.stream()
                .filter(f -> f.getRelPath().equalsIgnoreCase("SKILL.md"))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.SKILL_PARSE_FAILED, ".skill 包内未找到 SKILL.md"));
        List<SkillFile> rest = new ArrayList<>(out);
        rest.remove(md);
        return new ParsedPackage(md.getContent(), rest);
    }

    private static void stripCommonRoot(List<SkillFile> out) {
        String first = out.get(0).getRelPath();
        int slash = first.indexOf('/');
        if (slash < 0) {
            return;
        }
        String root = first.substring(0, slash);
        boolean allSame = out.stream().allMatch(p -> p.getRelPath().startsWith(root + "/"));
        if (allSame) {
            for (SkillFile p : out) {
                p.setRelPath(p.getRelPath().substring(root.length() + 1));
            }
        }
    }

    private static String classify(String name) {
        String ext = extOf(name);
        if (SCRIPT_EXT.contains(ext)) {
            return "SCRIPT";
        }
        return DOC_EXT.contains(ext) ? "DOC" : "ASSET";
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean looksBinary(byte[] content, String decoded) {
        for (byte b : content) {
            if (b == 0) {
                return true;
            }
        }
        return decoded.indexOf('\uFFFD') >= 0;
    }
}
