package com.tornado.boot.rag;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 文档解析（pdfbox3 / poi XWPF / md、txt 直读）+ 字符滑窗切分（~1600 字符，重叠 200） */
@Slf4j
@Service
public class DocumentParserService {

    public String parse(String fileType, byte[] bytes) throws Exception {
        return switch (fileType == null ? "" : fileType.toLowerCase()) {
            case "pdf" -> parsePdf(bytes);
            case "docx" -> parseDocx(bytes);
            default -> new String(bytes, StandardCharsets.UTF_8);
        };
    }

    private String parsePdf(byte[] bytes) throws Exception {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        }
    }

    private String parseDocx(byte[] bytes) throws Exception {
        try (XWPFWordExtractor extractor = new XWPFWordExtractor(
                new org.apache.poi.xwpf.usermodel.XWPFDocument(new ByteArrayInputStream(bytes)))) {
            return extractor.getText();
        }
    }

    /** 滑窗切分：chunk 长度 chunkChars，步进 chunkChars-chunkOverlap */
    public List<String> chunk(String text, int chunkChars, int overlap) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        String normalized = text.replace("\r\n", "\n").trim();
        int step = Math.max(1, chunkChars - overlap);
        for (int start = 0; start < normalized.length(); start += step) {
            int end = Math.min(normalized.length(), start + chunkChars);
            String piece = normalized.substring(start, end).trim();
            if (!piece.isEmpty()) {
                out.add(piece);
            }
            if (end == normalized.length()) {
                break;
            }
        }
        return out;
    }
}
