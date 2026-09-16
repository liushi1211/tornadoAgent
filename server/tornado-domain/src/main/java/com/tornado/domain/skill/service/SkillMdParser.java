package com.tornado.domain.skill.service;

import com.tornado.client.error.BizException;
import com.tornado.client.error.ErrorCode;

import java.util.regex.Pattern;

/**
 * SKILL.md frontmatter 解析（纯领域逻辑，零框架依赖）。
 * name 校验 ^[a-z0-9-]{1,64}$。
 */
public final class SkillMdParser {

    public static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9-]{1,64}$");
    public static final int MAX_MD_BYTES = 64 * 1024;

    private SkillMdParser() {}

    public record FrontMatter(String name, String description, String version) {}

    public static FrontMatter parse(String md) {
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
}
