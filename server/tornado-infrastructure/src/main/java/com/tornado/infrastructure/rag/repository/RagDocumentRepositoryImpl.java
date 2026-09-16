package com.tornado.infrastructure.rag.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tornado.client.api.PageResult;
import com.tornado.domain.rag.model.RagDocument;
import com.tornado.domain.rag.repository.RagDocumentRepository;
import com.tornado.infrastructure.rag.dataobject.RagDocumentDO;
import com.tornado.infrastructure.rag.mapper.RagDocumentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/** RAG 文档仓储实现：DO⇄领域对象转换 + 封装 MyBatis-Plus（软删/@TableLogic、手动分页 LIMIT/OFFSET） */
@Repository
@RequiredArgsConstructor
public class RagDocumentRepositoryImpl implements RagDocumentRepository {

    private final RagDocumentMapper docMapper;

    @Override
    public RagDocument save(RagDocument doc) {
        RagDocumentDO d = toDO(doc);
        d.setDeleted(0);
        docMapper.insert(d);
        doc.setId(d.getId());
        doc.setCreatedAt(d.getCreatedAt());
        doc.setUpdatedAt(d.getUpdatedAt());
        return doc;
    }

    @Override
    public void update(RagDocument doc) {
        docMapper.updateById(toDO(doc));
    }

    @Override
    public RagDocument findById(Long id) {
        return toDomain(docMapper.selectById(id));
    }

    @Override
    public PageResult<RagDocument> page(Long uid, String status, int page, int size) {
        LambdaQueryWrapper<RagDocumentDO> q = baseQuery(uid, status);
        long total = docMapper.selectCount(baseQuery(uid, status));
        int safeSize = Math.min(Math.max(size, 1), 100);
        long offset = (long) (Math.max(page, 1) - 1) * safeSize;
        q.last("LIMIT " + safeSize + " OFFSET " + offset);
        List<RagDocument> records = docMapper.selectList(q).stream().map(this::toDomain).toList();
        return PageResult.of(total, page, safeSize, records);
    }

    @Override
    public void deleteById(Long id) {
        docMapper.deleteById(id);
    }

    private LambdaQueryWrapper<RagDocumentDO> baseQuery(Long uid, String status) {
        LambdaQueryWrapper<RagDocumentDO> q = new LambdaQueryWrapper<RagDocumentDO>()
                .eq(RagDocumentDO::getUserId, uid)
                .orderByDesc(RagDocumentDO::getId);
        if (status != null && !status.isBlank()) {
            q.eq(RagDocumentDO::getStatus, status);
        }
        return q;
    }

    private RagDocumentDO toDO(RagDocument m) {
        RagDocumentDO d = new RagDocumentDO();
        d.setId(m.getId());
        d.setUserId(m.getUserId());
        d.setTitle(m.getTitle());
        d.setDocType(m.getDocType());
        d.setFileType(m.getFileType());
        d.setFilePath(m.getFilePath());
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

    private RagDocument toDomain(RagDocumentDO d) {
        if (d == null) {
            return null;
        }
        RagDocument m = new RagDocument();
        m.setId(d.getId());
        m.setUserId(d.getUserId());
        m.setTitle(d.getTitle());
        m.setDocType(d.getDocType());
        m.setFileType(d.getFileType());
        m.setFilePath(d.getFilePath());
        m.setSizeBytes(d.getSizeBytes());
        m.setStatus(d.getStatus());
        m.setErrorMsg(d.getErrorMsg());
        m.setChunkCount(d.getChunkCount());
        m.setTokenCount(d.getTokenCount());
        m.setRetryCount(d.getRetryCount());
        m.setCreatedAt(d.getCreatedAt());
        m.setUpdatedAt(d.getUpdatedAt());
        return m;
    }
}
