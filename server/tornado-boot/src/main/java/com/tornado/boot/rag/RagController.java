package com.tornado.boot.rag;

import com.tornado.common.api.PageResult;
import com.tornado.common.api.Result;
import com.tornado.common.context.UserContext;
import com.tornado.common.entity.RagDocument;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RagController {

    private final RagIngestService ingestService;
    private final RagSearchService searchService;

    @Data
    public static class TextReq {
        private String title;
        @NotBlank
        private String content;
    }

    @Data
    public static class SearchReq {
        @NotBlank
        private String query;
        private Integer topK;
        private Boolean rerank;
    }

    @GetMapping("/documents")
    public Result<PageResult<RagDocument>> page(@RequestParam(required = false) String status,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return Result.ok(ingestService.page(UserContext.userId(), status, page, size));
    }

    @PostMapping("/documents")
    public Result<RagDocument> upload(@RequestPart("file") MultipartFile file) {
        return Result.ok(ingestService.upload(UserContext.userId(), file));
    }

    @PostMapping("/texts")
    public Result<RagDocument> addText(@Valid @RequestBody TextReq req) {
        return Result.ok(ingestService.addText(UserContext.userId(), req.getTitle(), req.getContent()));
    }

    @PostMapping("/documents/{id}/retry")
    public Result<Void> retry(@PathVariable Long id) {
        ingestService.retry(UserContext.userId(), id);
        return Result.ok();
    }

    @DeleteMapping("/documents/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        ingestService.delete(UserContext.userId(), id);
        return Result.ok();
    }

    @PostMapping("/search")
    public Result<List<RagSearchService.SearchHit>> search(@Valid @RequestBody SearchReq req) {
        return Result.ok(searchService.search(UserContext.userId(), req.getQuery(), req.getTopK()));
    }
}
