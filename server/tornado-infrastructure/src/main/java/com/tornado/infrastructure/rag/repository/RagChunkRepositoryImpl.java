package com.tornado.infrastructure.rag.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tornado.domain.rag.model.RagChunk;
import com.tornado.domain.rag.repository.RagChunkRepository;
import com.tornado.infrastructure.rag.dataobject.RagChunkDO;
import com.tornado.infrastructure.rag.mapper.RagChunkMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/** RAG 切片仓储实现（MyBatis-Plus）。 */
@Repository
@RequiredArgsConstructor
public class RagChunkRepositoryImpl implements RagChunkRepository {

    private final RagChunkMapper ragChunkMapper;

    @Override
    public void saveBatch(List<RagChunk> chunks) {
        for (RagChunk c : chunks) {
            RagChunkDO d = new RagChunkDO();
            d.setDocId(c.getDocId());
            d.setUserId(c.getUserId());
            d.setSeq(c.getSeq());
            d.setTitle(c.getTitle());
            d.setContent(c.getContent());
            ragChunkMapper.insert(d);
        }
    }

    @Override
    public void deleteByDoc(Long docId) {
        ragChunkMapper.delete(new LambdaQueryWrapper<RagChunkDO>().eq(RagChunkDO::getDocId, docId));
    }
}
