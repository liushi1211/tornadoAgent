package com.tornado.boot.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tornado.common.api.PageResult;
import com.tornado.common.entity.RagDocument;
import com.tornado.common.ex.BizException;
import com.tornado.common.ex.ErrorCode;
import com.tornado.common.mapper.RagDocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * RAG 入库流水线（状态机 UPLOADED→PARSING→CHUNKED→EMBEDDING→READY/FAILED，见详细设计 §4.5）：
 * 上传落盘 + rag_document 落库后异步执行；多实例互斥靠 Redis 锁 rag:ingest:lock:{docId}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagIngestService {

    private static final Set<String> WHITELIST = Set.of("pdf", "docx", "md", "txt");
    private static final int TEXT_MAX_BYTES = 100 * 1024;

    private final RagDocumentMapper docMapper;
    private final RagProperties ragProps;
    private final DocumentParserService parserService;
    private final VectorStore vectorStore;
    private final StringRedisTemplate redis;
    /** 自注入代理，保证 @Async 生效 */
    @Lazy
    @Autowired
    private RagIngestService self;

    // ---------- 上传 ----------

    public RagDocument upload(Long uid, MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "unnamed" : file.getOriginalFilename();
        String ext = extOf(name);
        if (!WHITELIST.contains(ext)) {
            throw new BizException(ErrorCode.RAG_FILE_INVALID, "仅支持 pdf/docx/md/txt，收到: " + ext);
        }
        if (file.getSize() > ragProps.getMaxFileSizeMb() * 1024 * 1024) {
            throw new BizException(ErrorCode.RAG_FILE_INVALID, "文件超过 " + ragProps.getMaxFileSizeMb() + "MB 上限");
        }
        RagDocument doc = new RagDocument();
        doc.setUserId(uid);
        doc.setTitle(name.length() > 200 ? name.substring(0, 200) : name);
        doc.setDocType("FILE");
        doc.setFileType(ext);
        doc.setSizeBytes(file.getSize());
        doc.setStatus("UPLOADED");
        doc.setChunkCount(0);
        doc.setTokenCount(0);
        doc.setRetryCount(0);
        doc.setDeleted(0);
        try {
            Path dir = Paths.get(ragProps.getUploadDir(), String.valueOf(uid));
            Files.createDirectories(dir);
            Path path = dir.resolve(UUID.randomUUID() + "." + ext);
            Files.write(path, file.getBytes());
            doc.setFilePath(path.toString());
        } catch (IOException e) {
            throw new BizException(ErrorCode.UNKNOWN, "文件落盘失败: " + e.getMessage());
        }
        docMapper.insert(doc);
        self.process(doc.getId());
        return doc;
    }

    public RagDocument addText(Long uid, String title, String content) {
        if (content == null || content.isBlank()) {
            throw new BizException(ErrorCode.INVALID_PARAM, "content 不能为空");
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > TEXT_MAX_BYTES) {
            throw new BizException(ErrorCode.RAG_FILE_INVALID, "文本超过 100KB 上限");
        }
        RagDocument doc = new RagDocument();
        doc.setUserId(uid);
        doc.setTitle(title == null || title.isBlank() ? "粘贴文本" : title);
        doc.setDocType("TEXT");
        doc.setFileType("txt");
        doc.setSizeBytes((long) content.length());
        doc.setStatus("UPLOADED");
        doc.setChunkCount(0);
        doc.setTokenCount(0);
        doc.setRetryCount(0);
        doc.setDeleted(0);
        try {
            Path dir = Paths.get(ragProps.getUploadDir(), String.valueOf(uid));
            Files.createDirectories(dir);
            Path path = dir.resolve(UUID.randomUUID() + ".txt");
            Files.writeString(path, content, StandardCharsets.UTF_8);
            doc.setFilePath(path.toString());
        } catch (IOException e) {
            throw new BizException(ErrorCode.UNKNOWN, "文本落盘失败: " + e.getMessage());
        }
        docMapper.insert(doc);
        self.process(doc.getId());
        return doc;
    }

    // ---------- 查询/删改 ----------

    public PageResult<RagDocument> page(Long uid, String status, int page, int size) {
        long total = docMapper.selectCount(baseQuery(uid, status));
        int safeSize = Math.min(Math.max(size, 1), 100);
        long offset = (long) (Math.max(page, 1) - 1) * safeSize;
        LambdaQueryWrapper<RagDocument> q = baseQuery(uid, status);
        q.last("LIMIT " + safeSize + " OFFSET " + offset);
        List<RagDocument> records = docMapper.selectList(q);
        return PageResult.of(total, page, safeSize, records);
    }

    private LambdaQueryWrapper<RagDocument> baseQuery(Long uid, String status) {
        LambdaQueryWrapper<RagDocument> q = new LambdaQueryWrapper<RagDocument>()
                .eq(RagDocument::getUserId, uid)
                .orderByDesc(RagDocument::getId);
        if (status != null && !status.isBlank()) {
            q.eq(RagDocument::getStatus, status);
        }
        return q;
    }

    public void retry(Long uid, Long id) {
        RagDocument doc = require(uid, id);
        if (!"FAILED".equals(doc.getStatus())) {
            throw new BizException(ErrorCode.RAG_DOC_BUSY, "仅 FAILED 状态可重试");
        }
        if (doc.getRetryCount() >= 3) {
            throw new BizException(ErrorCode.RAG_DOC_BUSY, "重试次数已用尽");
        }
        doc.setStatus("UPLOADED");
        doc.setErrorMsg(null);
        doc.setRetryCount(doc.getRetryCount() + 1);
        docMapper.updateById(doc);
        self.process(id);
    }

    /** 软删元数据 + 级联删除向量（chunk id 约定 doc{docId}c{seq}） */
    public void delete(Long uid, Long id) {
        RagDocument doc = require(uid, id);
        if (List.of("PARSING", "CHUNKED", "EMBEDDING", "UPLOADED").contains(doc.getStatus())) {
            throw new BizException(ErrorCode.RAG_DOC_BUSY, "文档处理中，稍后再删");
        }
        purgeVectors(doc);
        docMapper.deleteById(id);
    }

    public void purgeVectors(RagDocument doc) {
        int chunks = doc.getChunkCount() == null ? 0 : doc.getChunkCount();
        if (chunks <= 0) {
            return;
        }
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < chunks; i++) {
            ids.add(vectorId(doc.getId(), i));
        }
        try {
            vectorStore.delete(ids);
        } catch (Exception e) {
            log.warn("向量删除失败 docId={}: {}", doc.getId(), e.getMessage());
        }
    }

    RagDocument require(Long uid, Long id) {
        RagDocument doc = docMapper.selectById(id);
        if (doc == null || !doc.getUserId().equals(uid)) {
            throw new BizException(ErrorCode.RAG_DOC_NOT_FOUND);
        }
        return doc;
    }

    // ---------- 异步流水线 ----------

    @Async("ragIngestExecutor")
    public void process(Long docId) {
        String lockKey = "rag:ingest:lock:" + docId;
        Boolean locked = redis.opsForValue().setIfAbsent(lockKey, "1", Duration.ofMinutes(10));
        if (!Boolean.TRUE.equals(locked)) {
            log.info("文档 {} 已被其他节点处理，跳过", docId);
            return;
        }
        RagDocument doc = docMapper.selectById(docId);
        if (doc == null) {
            redis.delete(lockKey);
            return;
        }
        try {
            // 1) 解析
            setStatus(doc, "PARSING");
            byte[] bytes = Files.readAllBytes(Paths.get(doc.getFilePath()));
            String text = parserService.parse(doc.getFileType(), bytes);
            // 2) 切分
            setStatus(doc, "CHUNKED");
            List<String> chunks = parserService.chunk(text, ragProps.getChunkChars(), ragProps.getChunkOverlap());
            if (chunks.isEmpty()) {
                throw new IllegalStateException("文档内容为空或未提取到文本");
            }
            // 3) 批量 Embedding + 写向量库（每批 10，失败重试 3 次退避）
            setStatus(doc, "EMBEDDING");
            List<Document> batch = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                batch.add(buildDocument(doc, i, chunks.get(i)));
                if (batch.size() == 10 || i == chunks.size() - 1) {
                    embedWithRetry(batch);
                    batch = new ArrayList<>();
                }
            }
            // 4) 完成
            doc.setChunkCount(chunks.size());
            doc.setTokenCount(text.length() / 2);
            doc.setStatus("READY");
            doc.setErrorMsg(null);
            docMapper.updateById(doc);
            log.info("文档 {} 入库完成，chunks={}", docId, chunks.size());
        } catch (Exception e) {
            log.error("文档 {} 入库失败", docId, e);
            doc.setStatus("FAILED");
            doc.setErrorMsg(truncate(e.getMessage() == null ? e.toString() : e.getMessage(), 480));
            docMapper.updateById(doc);
        } finally {
            redis.delete(lockKey);
        }
    }

    private void embedWithRetry(List<Document> batch) throws Exception {
        int attempt = 0;
        while (true) {
            try {
                vectorStore.add(batch);
                return;
            } catch (Exception e) {
                if (++attempt >= 3) {
                    throw e;
                }
                Thread.sleep(1000L << (attempt - 1));
            }
        }
    }

    private Document buildDocument(RagDocument doc, int seq, String text) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("user_id", String.valueOf(doc.getUserId()));
        meta.put("doc_id", String.valueOf(doc.getId()));
        meta.put("seq", seq);
        meta.put("source", doc.getTitle());
        return Document.builder()
                .id(vectorId(doc.getId(), seq))
                .text(text)
                .metadata(meta)
                .build();
    }

    private void setStatus(RagDocument doc, String status) {
        doc.setStatus(status);
        docMapper.updateById(doc);
    }

    private String vectorId(Long docId, int seq) {
        return "doc" + docId + "c" + seq;
    }

    private String extOf(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase();
    }

    private String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
