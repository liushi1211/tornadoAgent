package com.tornado.infrastructure.rag.gateway;

import com.tornado.client.error.BizException;
import com.tornado.client.error.ErrorCode;
import com.tornado.domain.rag.gateway.RagFileStorageGateway;
import com.tornado.infrastructure.rag.config.RagProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/** 原始文件落盘实现：写入 {uploadDir}/{uid}/{uuid}.{ext}，失败转 BizException（保留迁移前错误语义） */
@Component
@RequiredArgsConstructor
public class RagFileStorageGatewayImpl implements RagFileStorageGateway {

    private final RagProperties ragProps;

    @Override
    public String write(Long userId, String ext, byte[] bytes) {
        try {
            Path dir = Paths.get(ragProps.getUploadDir(), String.valueOf(userId));
            Files.createDirectories(dir);
            Path path = dir.resolve(UUID.randomUUID() + "." + ext);
            Files.write(path, bytes);
            return path.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new BizException(ErrorCode.UNKNOWN, "文件落盘失败: " + e.getMessage());
        }
    }

    @Override
    public byte[] read(String path) {
        try {
            return Files.readAllBytes(Paths.get(path));
        } catch (IOException e) {
            throw new BizException(ErrorCode.UNKNOWN, "文件读取失败: " + e.getMessage());
        }
    }
}
