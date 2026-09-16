package com.tornado.domain.rag.gateway;

import java.util.List;

/**
 * 文档解析与切分网关（domain 定义，infrastructure 用 pdfbox/poi 实现）。
 * 解析依赖第三方库故下沉 infra；滑窗切分为纯 JDK 逻辑，仍以网关形式统一暴露给应用层。
 */
public interface DocumentParseGateway {

    /** 按文件类型抽取纯文本（pdf/docx 走解析器，其余按 UTF-8 直读） */
    String parse(String fileType, byte[] bytes) throws Exception;

    /** 字符滑窗切分：chunk 长度 chunkChars，步进 chunkChars-overlap */
    List<String> chunk(String text, int chunkChars, int overlap);
}
