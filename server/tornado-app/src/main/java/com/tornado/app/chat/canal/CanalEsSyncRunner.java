package com.tornado.app.chat.canal;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.Message;
import com.tornado.domain.chat.gateway.ChatMessageIndexGateway;
import com.tornado.domain.chat.model.ChatMessageIndex;
import com.tornado.domain.rag.gateway.RagChunkIndexGateway;
import com.tornado.domain.rag.model.RagChunkIndex;
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
 * 订阅 canal-server 的 binlog 变更，把 cloud_ai.chat_message 与 cloud_ai.rag_chunk 实时灌入共享 Elasticsearch。
 * 由 saa.canal.enabled 开关控制（默认关）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "saa.canal.enabled", havingValue = "true")
public class CanalEsSyncRunner {

    private static final String T_MESSAGE = "chat_message";
    private static final String T_CHUNK = "rag_chunk";

    private final ChatMessageIndexGateway messageIndexGateway;
    private final RagChunkIndexGateway chunkIndexGateway;

    @Value("${saa.canal.host:host.docker.internal}")
    private String host;
    @Value("${saa.canal.port:11111}")
    private int port;
    @Value("${saa.canal.destination:example}")
    private String destination;
    @Value("${saa.canal.filter:cloud_ai\\\\.chat_message,cloud_ai\\\\.rag_chunk}")
    private String filter;
    @Value("${saa.canal.batch-size:100}")
    private int batchSize;
    // canal-server 客户端口(11111)鉴权账号。canal-admin 纳管后默认给 server 下发
    // canal.user=canal / canal.passwd=SHA1(SHA1("canal"))，客户端必须带账号，否则报 auth failed for user。
    @Value("${saa.canal.username:canal}")
    private String username;
    @Value("${saa.canal.password:canal}")
    private String password;

    private volatile boolean running;
    private Thread worker;
    private volatile CanalConnector connector;

    @PostConstruct
    public void start() {
        messageIndexGateway.ensureIndex();
        chunkIndexGateway.ensureIndex();
        running = true;
        worker = new Thread(this::runLoop, "canal-es-sync");
        worker.setDaemon(true);
        worker.start();
        log.info("Canal→ES 消费者已启动，canal-server {}:{} destination={} filter={}", host, port, destination, filter);
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
                        new InetSocketAddress(host, port), destination, username, password);
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
                    log.error("Canal 消费异常，3s 后重连: {}", e.toString());
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
        List<ChatMessageIndex> msgUpserts = new ArrayList<>();
        List<String> msgDeletes = new ArrayList<>();
        List<RagChunkIndex> chunkUpserts = new ArrayList<>();
        List<String> chunkDeletes = new ArrayList<>();
        for (CanalEntry.Entry entry : entries) {
            String table = entry.getHeader().getTableName();
            if (entry.getEntryType() != CanalEntry.EntryType.ROWDATA) {
                continue;
            }
            if (!T_MESSAGE.equals(table) && !T_CHUNK.equals(table)) {
                continue;
            }
            CanalEntry.RowChange rc = CanalEntry.RowChange.parseFrom(entry.getStoreValue());
            CanalEntry.EventType type = rc.getEventType();
            log.info("Canal 消费 {} {} {}", table, type,rc.getSql());
            for (CanalEntry.RowData rd : rc.getRowDatasList()) {
                if (T_MESSAGE.equals(table)) {
                    if (type == CanalEntry.EventType.DELETE) {
                        String id = column(rd.getBeforeColumnsList(), "id");
                        if (id != null) {
                            msgDeletes.add(id);
                        }
                    } else {
                        msgUpserts.add(toMessageIndex(rd.getAfterColumnsList()));
                    }
                } else { // rag_chunk
                    if (type == CanalEntry.EventType.DELETE) {
                        List<CanalEntry.Column> cols = rd.getBeforeColumnsList();
                        chunkDeletes.add(RagChunkIndex.docId(
                                parseLong(column(cols, "doc_id")), parseInt(column(cols, "seq"))));
                    } else {
                        chunkUpserts.add(toChunkIndex(rd.getAfterColumnsList()));
                    }
                }
            }
        }
        if (!msgUpserts.isEmpty()) {
            messageIndexGateway.bulkUpsert(msgUpserts);
        }
        if (!msgDeletes.isEmpty()) {
            messageIndexGateway.bulkDelete(msgDeletes);
        }
        if (!chunkUpserts.isEmpty()) {
            chunkIndexGateway.bulkUpsert(chunkUpserts);
        }
        if (!chunkDeletes.isEmpty()) {
            chunkIndexGateway.bulkDelete(chunkDeletes);
        }
    }

    private ChatMessageIndex toMessageIndex(List<CanalEntry.Column> cols) {
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

    private RagChunkIndex toChunkIndex(List<CanalEntry.Column> cols) {
        Long docId = parseLong(column(cols, "doc_id"));
        int seq = parseInt(column(cols, "seq"));
        return new RagChunkIndex(
                RagChunkIndex.docId(docId, seq),
                docId,
                parseLong(column(cols, "user_id")),
                seq,
                column(cols, "title"),
                column(cols, "content"));
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

    private static int parseInt(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
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
