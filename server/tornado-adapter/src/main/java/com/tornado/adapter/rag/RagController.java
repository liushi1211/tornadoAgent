package com.tornado.adapter.rag;

import com.tornado.app.rag.RagIngestService;
import com.tornado.app.rag.RagSearchService;
import com.tornado.client.api.PageResult;
import com.tornado.client.api.Result;
import com.tornado.client.context.UserContext;
import com.tornado.client.rag.cmd.RagSearchCmd;
import com.tornado.client.rag.cmd.RagTextCmd;
import com.tornado.client.rag.dto.RagDocumentDTO;
import com.tornado.client.rag.dto.RagSearchHit;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RagController {

    private final RagIngestService ingestService;
    private final RagSearchService searchService;

    @GetMapping("/documents")
    public Result<PageResult<RagDocumentDTO>> page(@RequestParam(required = false) String status,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return Result.ok(ingestService.page(UserContext.userId(), status, page, size));
    }

    @PostMapping("/documents")
    public Result<RagDocumentDTO> upload(@RequestPart("file") MultipartFile file) throws IOException {
        return Result.ok(ingestService.upload(UserContext.userId(), file.getOriginalFilename(), file.getBytes()));
    }

    @PostMapping("/texts")
    public Result<RagDocumentDTO> addText(@Valid @RequestBody RagTextCmd cmd) {
        return Result.ok(ingestService.addText(UserContext.userId(), cmd.getTitle(), cmd.getContent()));
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
    public Result<List<RagSearchHit>> search(@Valid @RequestBody RagSearchCmd cmd) {
        return Result.ok(searchService.search(UserContext.userId(), cmd.getQuery(), cmd.getTopK()));
    }
}
