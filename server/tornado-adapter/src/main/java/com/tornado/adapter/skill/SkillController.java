package com.tornado.adapter.skill;

import com.tornado.app.skill.SkillService;
import com.tornado.client.api.PageResult;
import com.tornado.client.api.Result;
import com.tornado.client.context.UserContext;
import com.tornado.client.skill.cmd.SkillInstallCmd;
import com.tornado.client.skill.dto.SkillDTO;
import com.tornado.client.skill.dto.SkillDetailDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/skills")
@RequiredArgsConstructor
public class SkillController {

    private final SkillService skillService;

    @GetMapping
    public Result<PageResult<SkillDTO>> page(@RequestParam(required = false) String keyword,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return Result.ok(skillService.page(UserContext.userId(), keyword, page, size));
    }

    /** multipart 安装：.md 或 .skill/.zip */
    @PostMapping(value = "/install", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<SkillDTO> installFile(@RequestPart("file") MultipartFile file) throws IOException {
        return Result.ok(skillService.installPackage(UserContext.userId(), file.getBytes(), file.getOriginalFilename()));
    }

    /** JSON 安装 */
    @PostMapping(value = "/install", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<SkillDTO> installJson(@RequestBody SkillInstallCmd cmd) {
        return Result.ok(skillService.installJson(UserContext.userId(), cmd));
    }

    @PatchMapping("/{id}/toggle")
    public Result<Void> toggle(@PathVariable Long id, @RequestParam boolean enabled) {
        skillService.toggle(UserContext.userId(), id, enabled);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        skillService.delete(UserContext.userId(), id);
        return Result.ok();
    }

    /** 详情：skill 本体 + 资源文件清单（不含内容） */
    @GetMapping("/{id}")
    public Result<SkillDetailDTO> detail(@PathVariable Long id) {
        return Result.ok(skillService.detail(UserContext.userId(), id));
    }

    /** 单个资源文件内容（脚本预览） */
    @GetMapping("/{id}/file")
    public Result<Map<String, Object>> file(@PathVariable Long id, @RequestParam String path) {
        String content = skillService.fileContent(UserContext.userId(), id, path);
        return Result.ok(Map.of("relPath", path, "content", content));
    }
}
