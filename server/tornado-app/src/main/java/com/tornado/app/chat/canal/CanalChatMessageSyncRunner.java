package com.tornado.app.chat.canal;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.Message;
import com.tornado.domain.chat.gateway.ChatMessageIndexGateway;
import com.tornado.domain.chat.model.ChatMessageIndex;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Canal 客户端消费者（替代被镜像白名单挡住的 canal-adapter）：
 * 订阅 canal-server 的 binlog 变更，把 cloud_ai.chat_message 的增改删实时灌入共享 Elasticsearch 索引。
 * 由 saa.canal.enabled 开关控制（默认关，避免无 canal-server 时启动报错）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "saa.canal.enabled", havingValue = "true")
public class CanalChatMessageSyncRunner {

    private static final String TABLE = "chat_message";

    private final ChatMessageIndexGateway indexGateway;

    @Value("${saa.canal.host:host.docker.internal}")
    private String host;
    @Value("${saa.canal.port:11111}")
    private int port;
    @Value("${saa.canal.destination:example}")
    private String destination;
    @Value("${saa.canal.filter:cloud_ai\\.chat_message}")
    private String filter;
    @Value("${saa.canal.batch-size:100}")
    private int batchSize;

    private volatile boolean running;
    private Thread worker;
    private volatile CanalConnector connector;

    @PostConstruct
    public void start() {
        indexGateway.ensureIndex();
        running = true;
        worker = new Thread(this::runLoop, "canal-es-sync");
        worker.setDaemon(true);
        worker.start();
        log.info("Canal→ES 消费者已启动，目标 canal-server {}:{} destination={} filter={}", host, port, destination, filter);
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (connector != null) {
            try {
                connector.disconnect();
            } catch (Exception ignore) {
                /* noop */
            }
        }
        if (worker != null) {
            worker.interrupt();
        }
    }

    private void runLoop() {
        while (running) {
            try {
                connector = CanalConnectors.newSingleConnector(
                        new InetSocketAddress(host, port), destination, "", "");
                connector.connect();
                connector.subscribe(filter);
                connector.rollback();
                log.info("Canal 已连接 canal-server，开始消费…");
                while (running) {
                    Message message = connector.getWithoutAck(batchSize);
                    long batchId = message.getId();
                    List<CanalEntry.Entry> entries = message.getEntries();
                    if (batchId == -1 || entries.isEmpty()) {
                        sleep(500);
                        continue;
                    }
                    process(entries);
                    connector.ack(batchId);
                }
            } catch (Exception e) {
                if (running) {
                    log.error("Canal 消费异常，3s 后重连: {}", e.getMessage());
                    sleep(3000);
                }
            } finally {
                try {
                    if (connector != null) {
                        connector.disconnect();
                    }
                } catch (Exception ignore) {
                    /* noop */
                }
            }
        }
    }

    private void process(List<CanalEntry.Entry> entries) throws Exception {
        List<ChatMessageIndex> upserts = new ArrayList<>();
        List<String> deletes = new ArrayList<>();
        for (CanalEntry.Entry entry : entries) {
            if (entry.getEntryType() != CanalEntry.EntryType.ROWDATA) {
                continue;
            }
            if (!TABLE.equals(entry.getHeader().getTableName())) {
                continue;
            }
            CanalEntry.RowChange rowChange = CanalEntry.RowChange.parseFrom(entry.getStoreValue());
            CanalEntry.EventType eventType = rowChange.getEventType();
            for (CanalEntry.RowData rd : rowChange.getRowDatasList()) {
                if (eventType == CanalEntry.EventType.DELETE) {
                    String id = column(rd.getBeforeColumnsList(), "id");
                    if (id != null) {
                        deletes.add(id);
                    }
                } else { // INSERT / UPDATE 取变更后列
                    upserts.add(toIndex(rd.getAfterColumnsList()));
                }
            }
        }
        if (!upserts.isEmpty()) {
            indexGateway.bulkUpsert(upserts);
        }
        if (!deletes.isEmpty()) {
            indexGateway.bulkDelete(deletes);
        }
    }

    private ChatMessageIndex toIndex(List<CanalEntry.Column> cols) {
        return new ChatMessageIndex(
                column(cols, "id"),
                parseLong(column(cols, "user_id")),
                parseLong(column(cols, "session_id")),
                column(cols, "role"),
                column(cols, "content"),
                column(cols, "thinking"),
                column(cols, "tool_calls"),
                column(cols, "finish_reason"),
                column(cols, "created_at"));
    }

    private static String column(List<CanalEntry.Column> cols, String name) {
        for (CanalEntry.Column c : cols) {
            if (c.getName().equalsIgnoreCase(name)) {
                return c.getIsNull() ? null : c.getValue();
            }
        }
        return null;
    }

    private static Long parseLong(String s) {
        try {
            return s == null ? null : Long.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
