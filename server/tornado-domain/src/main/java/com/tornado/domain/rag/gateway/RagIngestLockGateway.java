package com.tornado.domain.rag.gateway;

/** 入库流水线多实例互斥锁（domain 定义，infrastructure 用 Redis 实现） */
public interface RagIngestLockGateway {

    /** 抢占某文档的处理锁（带 TTL），返回是否成功 */
    boolean tryLock(Long docId);

    void unlock(Long docId);
}
