package com.tornado.boot.skill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tornado.common.api.PageResult;
import com.tornado.common.entity.Skill;
import com.tornado.common.entity.SkillFile;
import com.tornado.common.ex.BizException;
import com.tornado.common.ex.ErrorCode;
import com.tornado.common.mapper.SkillFileMapper;
import com.tornado.common.mapper.SkillMapper;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Skill 管理：frontmatter 解析（name/description/version）+ CRUD + 技能包资源文件。
 * .skill/.zip 包 = SKILL.md + 任意脚本/资源文本文件，资源存 skill_file 表；
 * 执行前经 materialize() 落盘到沙箱目录（.ps1 写 UTF-8 BOM，PowerShell 5.1 无 BOM 会按 GBK 解码报错）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillService {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9-]{1,64}$");
    private static final int MAX_MD_BYTES = 64 * 1024;
    private static final int MAX_FILE_BYTES = 256 * 1024;
    private static final int MAX_PKG_FILES = 50;
    private static final int MAX_PKG_BYTES = 2 * 1024 * 1024;
    private static final Set<String> SCRIPT_EXT = Set.of("ps1", "py", "js", "mjs", "cjs", "sh", "bat", "cmd", "ts");
    private static final Set<String> DOC_EXT = Set.of("md", "txt", "json", "yaml", "yml", "csv");

    @Value("${saa.skill.sandbox-dir:./skill-sandbox}")
    private String sandboxDir;

    @Data
    public static class InstallReq {
        @NotBlank
        private String name;
        private String description;
        @NotBlank
        private String contentMd;
        private String source = "MANUAL";
    }

    /** 包内资源文件（解析产物） */
    public record PkgFile(String relPath, String content, String fileType) {}

    private final SkillMapper skillMapper;
    private final SkillFileMapper skillFileMapper;

    public PageResult<Skill> page(Long uid, String keyword, int page, int size) {
        long total = skillMapper.selectCount(buildPageQuery(uid, keyword));
        LambdaQueryWrapper<Skill> q = buildPageQuery(uid, keyword);
        int safeSize = Math.min(Math.max(size, 1), 100);
        long offset = (long) (Math.max(page, 1) - 1) * safeSize;
        q.last("LIMIT " + safeSize + " OFFSET " + offset);
        List<Skill> records = skillMapper.selectList(q);
        return PageResult.of(total, page, safeSize, records);
    }

    private LambdaQueryWrapper<Skill> buildPageQuery(Long uid, String keyword) {
        LambdaQueryWrapper<Skill> q = new LambdaQueryWrapper<Skill>()
                .eq(Skill::getUserId, uid)
                .eq(Skill::getDeleted, 0)
                .orderByDesc(Skill::getUpdatedAt);
        if (keyword != null && !keyword.isBlank()) {
            q.and(w -> w.like(Skill::getName, keyword).or().like(Skill::getDescription, keyword));
        }
        return q;
    }

    public List<Skill> enabledSkills(Long uid) {
        return skillMapper.selectList(new LambdaQueryWrapper<Skill>()
                .eq(Skill::getUserId, uid).eq(Skill::getEnabled, 1).eq(Skill::getDeleted, 0));
    }

    public Skill content(Long uid, String name) {
        return skillMapper.selectOne(new LambdaQueryWrapper<Skill>()
                .eq(Skill::getUserId, uid).eq(Skill::getName, name).eq(Skill::getDeleted, 0));
    }

    public Skill byId(Long uid, Long id) {
        Skill s = skillMapper.selectById(id);
        return s == null || !s.getUserId().equals(uid) ? null : s;
    }

    /** 技能资源文件清单（不含内容） */
    public List<SkillFile> files(Long skillId) {
        return skillFileMapper.selectList(new LambdaQueryWrapper<SkillFile>()
                .eq(SkillFile::getSkillId, skillId)
                .orderByAsc(SkillFile::getRelPath));
    }

    /** 读单个资源内容（校验归属） */
    public SkillFile fileContent(Long uid, Long skillId, String relPath) {
        SkillFile f = skillFileMapper.selectOne(new LambdaQueryWrapper<SkillFile>()
                .eq(SkillFile::getSkillId, skillId).eq(SkillFile::getRelPath, relPath));
        if (f == null || !f.getUserId().equals(uid)) {
            return null;
        }
        return f;
    }

    public Skill install(Long uid, String markdown) {
        return installWithFiles(uid, markdown, List.of());
    }

    /**
     * .skill/.zip 包安装：SKILL.md + 资源文件；.md 单文件走 install。
     */
    public Skill installSkillPackage(Long uid, byte[] bytes, String filename) {
        if (filename != null && filename.toLowerCase().endsWith(".md")) {
            return install(uid, new String(bytes, StandardCharsets.UTF_8));
        }
        List<PkgFile> pkg = parseZip(bytes);
        PkgFile md = pkg.stream()
                .filter(p -> p.relPath().equalsIgnoreCase("SKILL.md") || p.relPath().toUpperCase().endsWith("/SKILL.MD"))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.SKILL_PARSE_FAILED, ".skill 包内未找到 SKILL.md"));
        List<PkgFile> rest = pkg.stream().filter(p -> p != md).toList();
        return installWithFiles(uid, md.content(), rest);
    }

    public Skill installWithFiles(Long uid, String markdown, List<PkgFile> files) {
        FrontMatter fm = parseFrontMatter(markdown);
        if (fm.name == null || !NAME_PATTERN.matcher(fm.name).matches()) {
            throw new BizException(ErrorCode.SKILL_PARSE_FAILED, "缺少合法 frontmatter name（^[a-z0-9-]{1,64}$）");
        }
        Skill s = save(uid, fm.name, fm.description == null ? "" : fm.description,
                markdown, "UPLOAD", fm.version);
        for (PkgFile f : files) {
            SkillFile sf = new SkillFile();
            sf.setSkillId(s.getId());
            sf.setUserId(uid);
            sf.setRelPath(f.relPath());
            sf.setFileType(f.fileType());
            sf.setContent(f.content());
            sf.setSizeBytes(f.content().getBytes(StandardCharsets.UTF_8).length);
            skillFileMapper.insert(sf);
        }
        if (!files.isEmpty()) {
            log.info("技能 {} 安装资源文件 {} 个", s.getName(), files.size());
        }
        return s;
    }

    public Skill installJson(Long uid, InstallReq req) {
        if (!NAME_PATTERN.matcher(req.getName()).matches()) {
            throw new BizException(ErrorCode.SKILL_PARSE_FAILED, "技能名需匹配 ^[a-z0-9-]{1,64}$");
        }
        if (req.getContentMd().getBytes(StandardCharsets.UTF_8).length > MAX_MD_BYTES) {
            throw new BizException(ErrorCode.SKILL_PARSE_FAILED, "SKILL.md 超过 64KB 上限");
        }
        return save(uid, req.getName(), req.getDescription() == null ? "" : req.getDescription(),
                req.getContentMd(), req.getSource() == null ? "MANUAL" : req.getSource(), null);
    }

    public void toggle(Long uid, Long id, boolean enabled) {
        Skill s = require(uid, id);
        s.setEnabled(enabled ? 1 : 0);
        skillMapper.updateById(s);
    }

    public void delete(Long uid, Long id) {
        Skill s = require(uid, id);
        // 物理删除：规避 uk(user_id,name,deleted) 软删撞唯一键；级联删资源与沙箱目录
        skillFileMapper.delete(new LambdaQueryWrapper<SkillFile>().eq(SkillFile::getSkillId, id));
        skillMapper.deleteById(id);
        try {
            deleteRecursively(sandboxRoot().resolve(String.valueOf(uid)).resolve(s.getName()));
        } catch (Exception e) {
            log.warn("清理沙箱目录失败（忽略）: {}", e.getMessage());
        }
    }

    /**
     * 把技能全部资源物化到沙箱目录 {sandbox}/{uid}/{name}/，返回目录路径。
     * .ps1 强制 UTF-8 BOM（PowerShell 5.1 对无 BOM 文件按 GBK 解码会报伪语法错误）。
     */
    public Path materialize(Long uid, String skillName) throws IOException {
        Skill s = content(uid, skillName);
        if (s == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "技能不存在: " + skillName);
        }
        Path dir = sandboxRoot().resolve(String.valueOf(uid)).resolve(skillName).normalize();
        if (!dir.startsWith(sandboxRoot().normalize())) {
            throw new BizException(ErrorCode.SKILL_PARSE_FAILED, "非法技能路径");
        }
        Files.createDirectories(dir);
        for (SkillFile f : files(s.getId())) {
            Path p = dir.resolve(f.getRelPath()).normalize();
            if (!p.startsWith(dir)) { // zip-slip 兜底
                throw new BizException(ErrorCode.SKILL_PARSE_FAILED, "资源路径越界: " + f.getRelPath());
            }
            Files.createDirectories(p.getParent());
            byte[] bytes = f.getContent().getBytes(StandardCharsets.UTF_8);
            if (f.getRelPath().toLowerCase().endsWith(".ps1")) {
                byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
                bytes = java.util.Arrays.copyOf(bom, bom.length + f.getContent().getBytes(StandardCharsets.UTF_8).length);
                System.arraycopy(f.getContent().getBytes(StandardCharsets.UTF_8), 0, bytes, bom.length,
                        f.getContent().getBytes(StandardCharsets.UTF_8).length);
            }
            Files.write(p, bytes);
        }
        return dir;
    }

    public Path sandboxRoot() {
        return Paths.get(sandboxDir).toAbsolutePath().normalize();
    }

    private void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private Skill require(Long uid, Long id) {
        Skill s = skillMapper.selectById(id);
        if (s == null || !s.getUserId().equals(uid)) {
            throw new BizException(ErrorCode.NOT_FOUND, "技能不存在");
        }
        return s;
    }

    private Skill save(Long uid, String name, String desc, String contentMd, String source, String version) {
        if (contentMd.getBytes(StandardCharsets.UTF_8).length > MAX_MD_BYTES) {
            throw new BizException(ErrorCode.SKILL_PARSE_FAILED, "SKILL.md 超过 64KB 上限");
        }
        if (content(uid, name) != null) {
            throw new BizException(ErrorCode.SKILL_PARSE_FAILED, "技能已存在: " + name);
        }
        Skill s = new Skill();
        s.setUserId(uid);
        s.setName(name);
        s.setDescription(desc.length() > 500 ? desc.substring(0, 500) : desc);
        s.setContentMd(contentMd);
        s.setSource(source);
        s.setEnabled(1);
        s.setVersion(version == null || version.isBlank() ? "1.0.0" : version);
        s.setDeleted(0);
        try {
            skillMapper.insert(s);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SKILL_PARSE_FAILED, "技能已存在: " + name);
        }
        return s;
    }

    private record FrontMatter(String name, String description, String version) {}

    /** 极简 frontmatter 解析：文件头 --- 块内的 key: value 行 */
    private FrontMatter parseFrontMatter(String md) {
        if (md == null) {
            throw new BizException(ErrorCode.SKILL_PARSE_FAILED, "内容为空");
        }
        String[] lines = md.replace("\r\n", "\n").split("\n");
        if (lines.length < 2 || !lines[0].trim().equals("---")) {
            throw new BizException(ErrorCode.SKILL_PARSE_FAILED, "缺少 frontmatter（需以 --- 开头）");
        }
        String name = null, desc = null, ver = null;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().equals("---")) {
                break;
            }
            int idx = line.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            String k = line.substring(0, idx).trim();
            String v = line.substring(idx + 1).trim().replaceAll("^\"|\"$", "");
            switch (k) {
                case "name" -> name = v;
                case "description" -> desc = v;
                case "version" -> ver = v;
                default -> { }
            }
        }
        return new FrontMatter(name, desc, ver);
    }

    /** 解析 zip：全部文本资源（含 SKILL.md）；公共顶层目录会被剥离；二进制/超限文件拒绝 */
    private List<PkgFile> parseZip(byte[] bytes) {
        List<PkgFile> out = new ArrayList<>();
        long total = 0;
        Set<String> paths = new HashSet<>();
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
                    throw new BizException(ErrorCode.SKILL_PARSE_FAILED,
                            "暂只支持文本资源（脚本/文档），二进制文件被拒绝: " + name);
                }
                total += size;
                if (total > MAX_PKG_BYTES || out.size() >= MAX_PKG_FILES) {
                    throw new BizException(ErrorCode.SKILL_PARSE_FAILED, "技能包过大或文件过多");
                }
                paths.add(name);
                out.add(new PkgFile(name, text, classify(name)));
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
        return out;
    }

    /** 所有条目共享同一顶层目录时剥离（zip 常见打包形态：word-count/SKILL.md） */
    private void stripCommonRoot(List<PkgFile> out) {
        String first = out.get(0).relPath();
        int slash = first.indexOf('/');
        if (slash < 0) {
            return;
        }
        String root = first.substring(0, slash);
        boolean allSame = out.stream().allMatch(p -> p.relPath().startsWith(root + "/"));
        if (allSame) {
            for (int i = 0; i < out.size(); i++) {
                PkgFile p = out.get(i);
                out.set(i, new PkgFile(p.relPath().substring(root.length() + 1), p.content(), p.fileType()));
            }
        }
    }

    private String classify(String name) {
        String ext = extOf(name);
        if (SCRIPT_EXT.contains(ext)) {
            return "SCRIPT";
        }
        return DOC_EXT.contains(ext) ? "DOC" : "ASSET";
    }

    private String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private boolean looksBinary(byte[] content, String decoded) {
        for (byte b : content) {
            if (b == 0) {
                return true;
            }
        }
        // UTF-8 解码替换符出现即认为非文本
        return decoded.indexOf('\uFFFD') >= 0;
    }
}
