package com.tornado.domain.rag.gateway;

/** 原始文件/文本落盘网关（domain 定义，infrastructure 用 java.nio 实现），返回可回读的存储路径 */
public interface RagFileStorageGateway {

    /** 将字节写入 {uploadDir}/{uid}/{uuid}.{ext}，返回绝对路径字符串 */
    String write(Long userId, String ext, byte[] bytes);

    /** 回读落盘文件的全部字节（入库流水线解析用） */
    byte[] read(String path);
}
