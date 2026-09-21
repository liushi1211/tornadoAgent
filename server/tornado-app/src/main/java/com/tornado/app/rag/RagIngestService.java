package com.tornado.app.rag;

import com.tornado.client.api.PageResult;
import com.tornado.client.error.BizException;
import com.tornado.client.error.ErrorCode;
import com.tornado.client.rag.dto.RagDocumentDTO;
import com.tornado.domain.rag.gateway.DocumentParseGateway;
import com.tornado.domain.rag.gateway.RagFileStorageGateway;
import com.tornado.domain.rag.gateway.RagIngestLockGateway;
import com.tornado.domain.rag.gateway.RagSettingsGateway;
import com.tornado.domain.rag.gateway.VectorStoreGateway;
import com.tornado.domain.rag.model.RagChunk;
import com.tornado.domain.rag.model.RagDocument;
import com.tornado.domain.rag.model.VectorChunk;
import com.tornado.domain.rag.repository.RagChunkRepository;
import com.tornado.domain.rag.repository.RagDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * RAG 入库应用服务（管理面 + 异步流水线）：上传落库后异步执行
 * UPLOADED→PARSING→CHUNKED→EMBEDDING→READY/FAILED（见详细设计 §4.5），多实例互斥走 RagIngestLockGateway。
 * 文件系统 / 向量库 / 解析器 / 配置等细节全部经 domain 网关下沉到 infrastructure，本层只做编排。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagIngestService {

    private static final Set<String> WHITELIST = Set.of("pdf", "docx", "md", "txt");
    private static final int TEXT_MAX_BYTES = 100 * 1024;

    private final RagDocumentRepository docRepository;
    private final DocumentParseGateway parseGateway;
    private final VectorStoreGateway vectorStoreGateway;
    private final RagFileStorageGateway fileStorageGateway;
    private final RagIngestLockGateway lockGateway;
    private final RagSettingsGateway settings;
    private final RagChunkRepository ragChunkRepository;

    /** 自注入代理，保证 @Async 生效 */
    @Lazy
    @Autowired
    private RagIngestService self;

    // ---------- 上传 ----------

    public RagDocumentDTO upload(Long uid, String filename, byte[] bytes) {
        String name = filename == null || filename.isBlank() ? "unnamed" : filename;
        String ext = extOf(name);
        if (!WHITELIST.contains(ext)) {
            throw new BizException(ErrorCode.RAG_FILE_INVALID, "仅支持 pdf/docx/md/txt，收到: " + ext);
        }
        if (bytes.length > settings.maxFileSizeMb() * 1024 * 1024) {
            throw new BizException(ErrorCode.RAG_FILE_INVALID, "文件超过 " + settings.maxFileSizeMb() + "MB 上限");
        }
        String path = fileStorageGateway.write(uid, ext, bytes);
        RagDocument doc = new RagDocument();
        doc.setUserId(uid);
        doc.setTitle(name.length() > 200 ? name.substring(0, 200) : name);
        doc.setDocType("FILE");
        doc.setFileType(ext);
        doc.setSizeBytes((long) bytes.length);
        doc.setStatus(RagDocument.ST_UPLOADED);
        doc.setChunkCount(0);
        doc.setTokenCount(0);
        doc.setRetryCount(0);
        doc.setFilePath(path);
        docRepository.save(doc);
        self.process(doc.getId());
        return toDTO(doc);
    }

    public RagDocumentDTO addText(Long uid, String title, String content) {
        if (content == null || content.isBlank()) {
            throw new BizException(ErrorCode.INVALID_PARAM, "content 不能为空");
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > TEXT_MAX_BYTES) {
            throw new BizException(ErrorCode.RAG_FILE_INVALID, "文本超过 100KB 上限");
        }
        String path = fileStorageGateway.write(uid, "txt", content.getBytes(StandardCharsets.UTF_8));
        RagDocument doc = new RagDocument();
        doc.setUserId(uid);
        doc.setTitle(title == null || title.isBlank() ? "粘贴文本" : title);
        doc.setDocType("TEXT");
        doc.setFileType("txt");
        doc.setSizeBytes((long) content.length());
        doc.setStatus(RagDocument.ST_UPLOADED);
        doc.setChunkCount(0);
        doc.setTokenCount(0);
        doc.setRetryCount(0);
        doc.setFilePath(path);
        docRepository.save(doc);
        self.process(doc.getId());
        return toDTO(doc);
    }

    // ---------- 查询/删改 ----------

    public PageResult<RagDocumentDTO> page(Long uid, String status, int page, int size) {
        PageResult<RagDocument> p = docRepository.page(uid, status, page, size);
        PageResult<RagDocumentDTO> out = new PageResult<>();
        out.setTotal(p.getTotal());
        out.setPage(p.getPage());
        out.setSize(p.getSize());
        out.setRecords(p.getRecords().stream().map(this::toDTO).toList());
        return out;
    }

    public void retry(Long uid, Long id) {
        RagDocument doc = require(uid, id);
        if (!doc.canRetry()) {
            throw new BizException(ErrorCode.RAG_DOC_BUSY, "仅 FAILED 状态且重试未用尽可重试");
        }
        doc.setStatus(RagDocument.ST_UPLOADED);
        doc.setErrorMsg(null);
        doc.setRetryCount(doc.retryCountOrZero() + 1);
        docRepository.update(doc);
        self.process(id);
    }

    /** 处理中禁止删除；软删元数据 + 级联删除向量 */
    public void delete(Long uid, Long id) {
        RagDocument doc = require(uid, id);
        if (doc.isInProgress()) {
            throw new BizException(ErrorCode.RAG_DOC_BUSY, "文档处理中，稍后再删");
        }
        vectorStoreGateway.deleteByDocument(id, doc.chunkCountOrZero());
        ragChunkRepository.deleteByDoc(id);
        docRepository.deleteById(id);
    }

    public RagDocument require(Long uid, Long id) {
        RagDocument doc = docRepository.findById(id);
        if (doc == null || !doc.getUserId().equals(uid)) {
            throw new BizException(ErrorCode.RAG_DOC_NOT_FOUND);
        }
        return doc;
    }

    // ---------- 异步流水线 ----------

    @Async("ragIngestExecutor")
    public void process(Long docId) {
        if (!lockGateway.tryLock(docId)) {
            log.info("文档 {} 已被其他节点处理，跳过", docId);
            return;
        }
        RagDocument doc = docRepository.findById(docId);
        if (doc == null) {
            lockGateway.unlock(docId);
            return;
        }
        try {
            doc.begin(RagDocument.ST_PARSING);
            docRepository.update(doc);
            byte[] bytes = fileStorageGateway.read(doc.getFilePath());
            String text = parseGateway.parse(doc.getFileType(), bytes);

            doc.begin(RagDocument.ST_CHUNKED);
            docRepository.update(doc);
            List<String> chunks = parseGateway.chunk(text, settings.chunkChars(), settings.chunkOverlap());
            if (chunks.isEmpty()) {
                throw new IllegalStateException("文档内容为空或未提取到文本");
            }

            // 切片正文落库（供 canal→ES 做 BM25 关键词召回）；重试时先清后写，幂等
            ragChunkRepository.deleteByDoc(docId);
            List<RagChunk> chunkRows = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                RagChunk rc = new RagChunk();
                rc.setDocId(docId);
                rc.setUserId(doc.getUserId());
                rc.setSeq(i);
                rc.setTitle(doc.getTitle());
                rc.setContent(chunks.get(i));
                chunkRows.add(rc);
            }
            ragChunkRepository.saveBatch(chunkRows);

            doc.begin(RagDocument.ST_EMBEDDING);
            docRepository.update(doc);
            List<VectorChunk> batch = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                batch.add(new VectorChunk(docId, i, doc.getUserId(), doc.getTitle(), chunks.get(i)));
                if (batch.size() == 10 || i == chunks.size() - 1) {
                    embedWithRetry(batch);
                    batch = new ArrayList<>();
                }
            }

            doc.markReady(chunks.size(), text.length() / 2);
            docRepository.update(doc);
            log.info("文档 {} 入库完成，chunks={}", docId, chunks.size());
        } catch (Exception e) {
            log.error("文档 {} 入库失败", docId, e);
            doc.markFailed(e.getMessage() == null ? e.toString() : e.getMessage());
            docRepository.update(doc);
        } finally {
            lockGateway.unlock(docId);
        }
    }

    private void embedWithRetry(List<VectorChunk> batch) throws Exception {
        int attempt = 0;
        while (true) {
            try {
                vectorStoreGateway.add(batch);
                return;
            } catch (Exception e) {
                if (++attempt >= 3) {
                    throw e;
                }
                Thread.sleep(1000L << (attempt - 1));
            }
        }
    }

    private String extOf(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase();
    }

    private RagDocumentDTO toDTO(RagDocument m) {
        RagDocumentDTO d = new RagDocumentDTO();
        d.setId(m.getId());
        d.setTitle(m.getTitle());
        d.setDocType(m.getDocType());
        d.setFileType(m.getFileType());
        d.setSizeBytes(m.getSizeBytes());
        d.setStatus(m.getStatus());
        d.setErrorMsg(m.getErrorMsg());
        d.setChunkCount(m.getChunkCount());
        d.setTokenCount(m.getTokenCount());
        d.setRetryCount(m.getRetryCount());
        d.setCreatedAt(m.getCreatedAt());
        d.setUpdatedAt(m.getUpdatedAt());
        return d;
    }
}
