package com.tornado.boot.skill;

import com.tornado.common.api.PageResult;
import com.tornado.common.api.Result;
import com.tornado.common.context.UserContext;
import com.tornado.common.entity.Skill;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/skills")
@RequiredArgsConstructor
public class SkillController {

    private final SkillService skillService;

    @GetMapping
    public Result<PageResult<Skill>> page(@RequestParam(required = false) String keyword,
                                          @RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return Result.ok(skillService.page(UserContext.userId(), keyword, page, size));
    }

    /** multipart 安装：.md 或 .skill(zip) */
    @PostMapping(value = "/install", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Skill> installFile(@RequestPart("file") MultipartFile file) throws IOException {
        return Result.ok(skillService.installSkillPackage(UserContext.userId(), file.getBytes(), file.getOriginalFilename()));
    }

    /** JSON 安装 */
    @PostMapping(value = "/install", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<Skill> installJson(@RequestBody SkillService.InstallReq req) {
        return Result.ok(skillService.installJson(UserContext.userId(), req));
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
    public Result<java.util.Map<String, Object>> detail(@PathVariable Long id) {
        Skill s = skillService.byId(UserContext.userId(), id);
        if (s == null) {
            throw new com.tornado.common.ex.BizException(com.tornado.common.ex.ErrorCode.NOT_FOUND, "技能不存在");
        }
        List<java.util.Map<String, Object>> files = skillService.files(id).stream()
                .map(f -> java.util.Map.<String, Object>of(
                        "relPath", f.getRelPath(), "fileType", f.getFileType(), "sizeBytes", f.getSizeBytes()))
                .toList();
        return Result.ok(java.util.Map.of("skill", s, "files", files));
    }

    /** 单个资源文件内容（脚本预览） */
    @GetMapping("/{id}/file")
    public Result<java.util.Map<String, Object>> file(@PathVariable Long id, @RequestParam String path) {
        var f = skillService.fileContent(UserContext.userId(), id, path);
        if (f == null) {
            throw new com.tornado.common.ex.BizException(com.tornado.common.ex.ErrorCode.NOT_FOUND, "资源不存在: " + path);
        }
        return Result.ok(java.util.Map.of("relPath", f.getRelPath(), "fileType", f.getFileType(), "content", f.getContent()));
    }
}
