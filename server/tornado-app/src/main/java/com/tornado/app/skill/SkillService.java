package com.tornado.app.skill;

import com.tornado.client.api.PageResult;
import com.tornado.client.error.BizException;
import com.tornado.client.error.ErrorCode;
import com.tornado.client.skill.cmd.SkillInstallCmd;
import com.tornado.client.skill.dto.SkillDTO;
import com.tornado.client.skill.dto.SkillDetailDTO;
import com.tornado.client.skill.dto.SkillFileMetaDTO;
import com.tornado.domain.skill.gateway.SkillScriptGateway;
import com.tornado.domain.skill.model.Skill;
import com.tornado.domain.skill.model.SkillFile;
import com.tornado.domain.skill.repository.SkillRepository;
import com.tornado.domain.skill.service.SkillMdParser;
import com.tornado.domain.skill.service.SkillPackageParser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 技能应用服务（管理面）：安装/查询/启停/删除/详情，编排 domain 解析器 + 仓储 + 沙箱网关。
 * 运行时消费（read_skill/run_skill_script）由 chat 的 AgentLocalTools 直接依赖 domain 仓储与网关。
 */
@Service
@RequiredArgsConstructor
public class SkillService {

    private final SkillRepository skillRepository;
    private final SkillScriptGateway skillScriptGateway;

    public PageResult<SkillDTO> page(Long uid, String keyword, int page, int size) {
        PageResult<Skill> p = skillRepository.page(uid, keyword, page, size);
        PageResult<SkillDTO> out = new PageResult<>();
        out.setTotal(p.getTotal());
        out.setPage(p.getPage());
        out.setSize(p.getSize());
        out.setRecords(p.getRecords().stream().map(this::toDTO).toList());
        return out;
    }

    public SkillDTO installPackage(Long uid, byte[] bytes, String filename) {
        if (filename != null && filename.toLowerCase().endsWith(".md")) {
            return installMarkdown(uid, new String(bytes, StandardCharsets.UTF_8), "UPLOAD");
        }
        SkillPackageParser.ParsedPackage pkg = SkillPackageParser.parse(bytes);
        Skill s = saveWithMd(uid, pkg.skillMd(), "UPLOAD");
        for (SkillFile f : pkg.files()) {
            f.setSkillId(s.getId());
            f.setUserId(uid);
        }
        skillRepository.saveFiles(pkg.files());
        return toDTO(s);
    }

    public SkillDTO installJson(Long uid, SkillInstallCmd cmd) {
        if (!SkillMdParser.NAME_PATTERN.matcher(cmd.getName()).matches()) {
            throw new BizException(ErrorCode.SKILL_PARSE_FAILED, "技能名需匹配 ^[a-z0-9-]{1,64}$");
        }
        if (cmd.getContentMd().getBytes(StandardCharsets.UTF_8).length > SkillMdParser.MAX_MD_BYTES) {
            throw new BizException(ErrorCode.SKILL_PARSE_FAILED, "SKILL.md 超过 64KB 上限");
        }
        if (skillRepository.existsByName(uid, cmd.getName())) {
            throw new BizException(ErrorCode.SKILL_PARSE_FAILED, "技能已存在: " + cmd.getName());
        }
        Skill s = new Skill();
        s.setUserId(uid);
        s.setName(cmd.getName());
        s.setDescription(cmd.getDescription() == null ? "" : cmd.getDescription());
        s.setContentMd(cmd.getContentMd());
        s.setSource(cmd.getSource() == null ? "MANUAL" : cmd.getSource());
        s.setEnabled(1);
        s.setVersion("1.0.0");
        skillRepository.save(s);
        return toDTO(s);
    }

    private SkillDTO installMarkdown(Long uid, String markdown, String source) {
        Skill s = saveWithMd(uid, markdown, source);
        return toDTO(s);
    }

    private Skill saveWithMd(Long uid, String markdown, String source) {
        SkillMdParser.FrontMatter fm = SkillMdParser.parse(markdown);
        if (fm.name() == null || !SkillMdParser.NAME_PATTERN.matcher(fm.name()).matches()) {
            throw new BizException(ErrorCode.SKILL_PARSE_FAILED, "缺少合法 frontmatter name（^[a-z0-9-]{1,64}$）");
        }
        if (markdown.getBytes(StandardCharsets.UTF_8).length > SkillMdParser.MAX_MD_BYTES) {
            throw new BizException(ErrorCode.SKILL_PARSE_FAILED, "SKILL.md 超过 64KB 上限");
        }
        if (skillRepository.existsByName(uid, fm.name())) {
            throw new BizException(ErrorCode.SKILL_PARSE_FAILED, "技能已存在: " + fm.name());
        }
        Skill s = new Skill();
        s.setUserId(uid);
        s.setName(fm.name());
        s.setDescription(fm.description() == null ? "" : fm.description());
        s.setContentMd(markdown);
        s.setSource(source);
        s.setEnabled(1);
        s.setVersion(fm.version() == null || fm.version().isBlank() ? "1.0.0" : fm.version());
        skillRepository.save(s);
        return s;
    }

    public void toggle(Long uid, Long id, boolean enabled) {
        Skill s = require(uid, id);
        s.setEnabledFlag(enabled);
        skillRepository.updateEnabled(s);
    }

    public void delete(Long uid, Long id) {
        Skill s = require(uid, id);
        skillRepository.deleteById(id);
        skillScriptGateway.cleanup(uid, s.getName());
    }

    public SkillDetailDTO detail(Long uid, Long id) {
        Skill s = require(uid, id);
        List<SkillFileMetaDTO> files = skillRepository.files(id).stream()
                .map(f -> new SkillFileMetaDTO(f.getRelPath(), f.getFileType(), f.getSizeBytes()))
                .toList();
        return new SkillDetailDTO(toDTO(s), files);
    }

    public String fileContent(Long uid, Long id, String relPath) {
        require(uid, id);
        SkillFile f = skillRepository.file(id, relPath);
        if (f == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "资源不存在: " + relPath);
        }
        return f.getContent();
    }

    private Skill require(Long uid, Long id) {
        Skill s = skillRepository.findById(id);
        if (s == null || !s.getUserId().equals(uid)) {
            throw new BizException(ErrorCode.NOT_FOUND, "技能不存在");
        }
        return s;
    }

    private SkillDTO toDTO(Skill s) {
        SkillDTO d = new SkillDTO();
        d.setId(s.getId());
        d.setName(s.getName());
        d.setDescription(s.getDescription());
        d.setContentMd(s.getContentMd());
        d.setSource(s.getSource());
        d.setEnabled(s.isEnabled());
        d.setVersion(s.getVersion());
        return d;
    }
}
