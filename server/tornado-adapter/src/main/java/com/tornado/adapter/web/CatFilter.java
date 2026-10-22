package com.tornado.adapter.web;

import com.dianping.cat.Cat;
import com.dianping.cat.CatConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.dianping.cat.configuration.NetworkInterfaceManager;
import com.dianping.cat.configuration.client.entity.Server;
import com.dianping.cat.message.Message;
import com.dianping.cat.message.MessageProducer;
import com.dianping.cat.message.Transaction;
import com.dianping.cat.message.internal.DefaultMessageManager;
import com.dianping.cat.message.internal.DefaultTransaction;
import com.dianping.cat.message.spi.MessageTree;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.unidal.helper.Joiners;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class CatFilter implements Filter {
    
    private static Map<MessageFormat, String> s_patterns = new LinkedHashMap<MessageFormat, String>();

    private List<Handler> m_handlers = new ArrayList<>();

    /** 精确前缀排除 */
    private static final List<String> EXCLUDE_PREFIXES = List.of(
            "/actuator", "/error", "/favicon.ico", "/api/chat/stream");

    /** 正则排除：HITL resume 等流式端点 */
    private static final List<Pattern> EXCLUDE_PATTERNS = List.of(
            Pattern.compile("^/api/chat/hitl/.+/resume$"));

    // ---------- 以下为在原版 com.dianping.cat.servlet.CatFilter 语义之上叠加的本项目增强 ----------

    /** 纯数字路径段（含逗号/点/横线等 id 常见字符）→ {num}，防 RESTful 路径撑爆 URL 聚合基数。 */
    private static final Pattern NUMERIC_SEGMENT = Pattern.compile("^[0-9.,\\-]+$");

    /** MDC key：CAT messageId，作为「日志 ↔ 链路」关联键；同时随响应头返回，便于前端定位某次调用。 */
    public static final String MDC_TRACE_ID = "catTraceId";

    /** 响应头：本次请求的 CAT messageId（= MDC_TRACE_ID 的值）。 */
    public static final String HEADER_MESSAGE_ID = "X-Message-Id";

    /** 出入参落 CAT 的单侧截断上限（远低于 cat-client maxMessageLength=5000，防丢链路细节）。 */
    private static final int MAX_BODY = 1024;

    /** 疑似敏感字段脱敏：JSON 形式 "token":"xxx"。 */
    private static final Pattern SENSITIVE_JSON = Pattern.compile(
            "(?i)(\"[\\w.-]*(?:token|password|secret|authorization|api[-_]?key|credential)[\\w.-]*\"\\s*:\\s*)\"[^\"]*\"");

    /** 疑似敏感字段脱敏：query 形式 token=xxx。 */
    private static final Pattern SENSITIVE_QUERY = Pattern.compile(
            "(?i)((?:token|password|secret|authorization|api[_-]?key|credential)=)[^&]*");

    // ---------- 链路摘要日志（让「整条链路」在 Kibana 一条日志里可读） ----------

    /** 专用 logger 名，便于在 Kibana 里用 log.logger: "CAT_TRACE" 单独筛链路摘要。 */
    private static final Logger TRACE_LOG = LoggerFactory.getLogger("CAT_TRACE");

    /** 摘要日志开关（env CAT_TRACE_LOG / 系统属性 cat.trace-log），默认开。 */
    private static final boolean TRACE_LOG_ENABLED = boolProp("CAT_TRACE_LOG", "cat.trace-log", true);

    /** CAT 应用名（= app.properties 的 app.name），用于拼 Log View 深链。 */
    private static final String CAT_APP_NAME = strProp("CAT_APP_NAME", "cat.app-name", "tornado-agent");

    private static final String CAT_LOGVIEW_BASE = strProp("CAT_LOGVIEW_URL", "cat.logview-url",
            "http://localhost:8070/cat/r/logview");

    private static final String KIBANA_DISCOVER_BASE = strProp("KIBANA_DISCOVER_URL", "kibana.discover-url",
            "http://localhost:5601/app/discover");

    /** 摘要总长上限：ECS message 是 text 不截断，但避免超长 SQL/入参把日志撑爆。 */
    private static final int MAX_SUMMARY = 4000;

    /** 单个节点 data（SQL 文本等）的截断上限。 */
    private static final int MAX_NODE_DATA = 200;

    /** 调用树递归深度与每层节点数上限（防极端扇出把摘要撑大）。 */
    private static final int MAX_DEPTH = 3;

    private static final int MAX_CHILDREN_PER_NODE = 20;

    /** 结构化链路文档 logger（logback 里单独落一个 ndjson 文件给 Filebeat→ES，additivity=false）。 */
    private static final Logger TRACE_DOC_LOG = LoggerFactory.getLogger("CAT_TRACE_DOC");

    /** 结构化文档开关（env CAT_TRACE_DOC / 属性 cat.trace-doc），默认开。 */
    private static final boolean TRACE_DOC_ENABLED = boolProp("CAT_TRACE_DOC", "cat.trace-doc", true);

    /** 链路文档用的 JSON 序列化器（无状态，线程安全，可静态复用）。 */
    private static final ObjectMapper TRACE_JSON = new ObjectMapper();

    /** 出入参 JSON 体最大采集长度（超出截断，截断后多半解析失败→退化为 raw 字符串）。 */
    private static final int MAX_JSON_BODY = 8192;

    /** 一条链路文档最多多少个 span 节点（防极端扇出撑爆文档）。 */
    private static final int MAX_SPANS = 60;

    /** span 上 data（SQL 文本等）的截断上限，比 CAT data 宽松些，便于在 Kibana 里看全 SQL。 */
    private static final int MAX_SPAN_DATA = 1000;

    @Override
    public void destroy() {
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException,	ServletException {

        if (!(request instanceof HttpServletRequest hr) || !(response instanceof HttpServletResponse hres)
                || isExcluded(hr.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        // 缓存请求/响应体以便把出入参挂到 Url 事务上；流式接口已被 isExcluded 排除，缓冲不会破坏 SSE。
        ContentCachingRequestWrapper wrappedReq = new ContentCachingRequestWrapper(hr);
        ContentCachingResponseWrapper wrappedRes = new ContentCachingResponseWrapper(hres);
        try {
            Context ctx = new Context(wrappedReq, wrappedRes, chain, m_handlers);
            ctx.handle();
        } finally {
            MDC.remove(MDC_TRACE_ID);
            try {
                wrappedRes.copyBodyToResponse(); // 必须回写，否则客户端收不到响应体
            } catch (Throwable ignore) {
                /* CAT 兜底，绝不影响响应 */
            }
        }
    }

    private boolean isExcluded(String uri) {
        if (uri == null) {
            return true;
        }
        for (String p : EXCLUDE_PREFIXES) {
            if (uri.startsWith(p)) {
                return true;
            }
        }
        for (Pattern p : EXCLUDE_PATTERNS) {
            if (p.matcher(uri).matches()) {
                return true;
            }
        }
        return false;
    }

    protected String getOriginalUrl(ServletRequest request) {
        return ((HttpServletRequest)request).getRequestURI();
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        String pattern = filterConfig.getInitParameter("pattern");

        if (pattern != null) {
            try {
                String[] patterns = pattern.split(";");

                for (String temp : patterns) {
                    String[] temps = temp.split(":");

                    s_patterns.put(new MessageFormat(temps[0].trim()), temps[1].trim());
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        m_handlers.add(CatHandler.ENVIRONMENT);
        m_handlers.add(CatHandler.ID_SETUP);
        m_handlers.add(CatHandler.LOG_SPAN);
        m_handlers.add(CatHandler.LOG_CLIENT_PAYLOAD);
    }

    private static enum CatHandler implements CatFilter.Handler {
        ENVIRONMENT {
            protected int detectMode(ServletRequest request) {
                HttpServletRequest req = (HttpServletRequest) request;
                String source = req.getHeader("X-CAT-SOURCE");
                String id = req.getHeader("X-CAT-ID");

                if ("container".equals(source)) {
                    return 2;
                } else if (id != null && id.length() > 0) {
                    return 1;
                } else {
                    return 0;
                }
            }

            @Override
            public void handle(CatFilter.Context ctx) throws IOException, ServletException {
                ServletRequest req = ctx.getRequest();
                boolean top = !Cat.getManager().hasContext();

                ctx.setTop(top);

                if (top) {
                    ctx.setMode(detectMode(req));
                    ctx.setType(CatConstants.TYPE_URL);

                    setTraceMode(req);
                } else {
                    ctx.setType(CatConstants.TYPE_URL_FORWARD);
                }

                ctx.handle();
            }

            protected void setTraceMode(ServletRequest request) {
                HttpServletRequest req = (HttpServletRequest) request;
                String traceMode = "X-CAT-TRACE-MODE";
                String headMode = req.getHeader(traceMode);

                if ("true".equals(headMode)) {
                    Cat.getManager().setTraceMode(true);
                }
            }
        },

        ID_SETUP {
            private String m_servers;

            private String getCatServer() {
                if (m_servers == null) {
                    DefaultMessageManager manager = (DefaultMessageManager) Cat.getManager();
                    List<Server> servers = manager.getConfigManager().getServers();

                    m_servers = Joiners.by(',').join(servers, new Joiners.IBuilder<Server>() {
                        @Override
                        public String asString(Server server) {
                            String ip = server.getIp();
                            Integer httpPort = server.getHttpPort();

                            if ("127.0.0.1".equals(ip)) {
                                ip = NetworkInterfaceManager.INSTANCE.getLocalHostAddress();
                            }

                            return ip + ":" + httpPort;
                        }
                    });
                }

                return m_servers;
            }

            @Override
            public void handle(CatFilter.Context ctx) throws IOException, ServletException {
                boolean isTraceMode = Cat.getManager().isTraceMode();

                HttpServletRequest req = (HttpServletRequest) ctx.getRequest();
                HttpServletResponse res = (HttpServletResponse) ctx.getResponse();
                MessageProducer producer = Cat.getProducer();
                int mode = ctx.getMode();

                switch (mode) {
                    case 0:
                        ctx.setId(producer.createMessageId());
                        break;
                    case 1:
                        ctx.setRootId(req.getHeader("X-CAT-ROOT-ID"));
                        ctx.setParentId(req.getHeader("X-CAT-PARENT-ID"));
                        ctx.setId(req.getHeader("X-CAT-ID"));
                        break;
                    case 2:
                        ctx.setRootId(producer.createMessageId());
                        ctx.setParentId(ctx.getRootId());
                        ctx.setId(producer.createMessageId());
                        break;
                    default:
                        throw new RuntimeException(String.format("Internal Error: unsupported mode(%s)!", mode));
                }

                if (isTraceMode) {
                    MessageTree tree = Cat.getManager().getThreadLocalMessageTree();

                    tree.setMessageId(ctx.getId());
                    tree.setParentMessageId(ctx.getParentId());
                    tree.setRootMessageId(ctx.getRootId());

                    res.setHeader("X-CAT-SERVER", getCatServer());

                    switch (mode) {
                        case 0:
                            res.setHeader("X-CAT-ROOT-ID", ctx.getId());
                            break;
                        case 1:
                            res.setHeader("X-CAT-ROOT-ID", ctx.getRootId());
                            res.setHeader("X-CAT-PARENT-ID", ctx.getParentId());
                            res.setHeader("X-CAT-ID", ctx.getId());
                            break;
                        case 2:
                            res.setHeader("X-CAT-ROOT-ID", ctx.getRootId());
                            res.setHeader("X-CAT-PARENT-ID", ctx.getParentId());
                            res.setHeader("X-CAT-ID", ctx.getId());
                            break;
                    }
                }

                ctx.handle();
            }
        },

        LOG_CLIENT_PAYLOAD {
            @Override
            public void handle(CatFilter.Context ctx) throws IOException, ServletException {
                ServletRequest req = ctx.getRequest();
                String type = ctx.getType();

                if (ctx.isTop()) {
                    logRequestClientInfo(req, type);
                    logRequestPayload(req, type);
                } else {
                    logRequestPayload(req, type);
                }

                ctx.handle();
            }

            protected void logRequestClientInfo(ServletRequest request, String type) {
                HttpServletRequest req = (HttpServletRequest) request;
                StringBuilder sb = new StringBuilder(1024);
                String ip = "";
                String ipForwarded = req.getHeader("x-forwarded-for");

                if (ipForwarded == null) {
                    ip = req.getRemoteAddr();
                } else {
                    ip = ipForwarded;
                }

                sb.append("IPS=").append(ip);
                sb.append("&VirtualIP=").append(req.getRemoteAddr());
                sb.append("&Server=").append(req.getServerName());
                sb.append("&Referer=").append(req.getHeader("referer"));
                sb.append("&Agent=").append(req.getHeader("user-agent"));

                Cat.logEvent(type, type + ".Server", Message.SUCCESS, sb.toString());
            }

            protected void logRequestPayload(ServletRequest request, String type) {
                StringBuilder sb = new StringBuilder(256);
                HttpServletRequest req = (HttpServletRequest) request;

                sb.append(req.getScheme().toUpperCase()).append('/');
                sb.append(req.getMethod()).append(' ').append(req.getRequestURI());

                String qs = req.getQueryString();

                if (qs != null) {
                    sb.append('?').append(qs);
                }

                Cat.logEvent(type, type + ".Method", Message.SUCCESS, sb.toString());
            }
        },

        LOG_SPAN {
            private void customizeStatus(Transaction t, ServletRequest req) {
                Object catStatus = req.getAttribute(CatConstants.CAT_STATE);

                if (catStatus != null) {
                    t.setStatus(catStatus.toString());
                } else {
                    t.setStatus(Message.SUCCESS);
                }
            }

            private void customizeUri(Transaction t, ServletRequest req) {
                if (t instanceof DefaultTransaction) {
                    Object catPageType = req.getAttribute(CatConstants.CAT_PAGE_TYPE);

                    if (catPageType instanceof String) {
                        ((DefaultTransaction) t).setType(catPageType.toString());
                    }

                    Object catPageUri = req.getAttribute(CatConstants.CAT_PAGE_URI);

                    if (catPageUri instanceof String) {
                        ((DefaultTransaction) t).setName(catPageUri.toString());
                    }
                }
            }

            private String getRequestURI(ServletRequest request) {
                HttpServletRequest req = (HttpServletRequest) request;
                String requestURI = req.getRequestURI();

                if (s_patterns.size() == 0) {
                    return normalize(requestURI);
                } else {
                    for (Map.Entry<MessageFormat, String> entry : s_patterns.entrySet()) {
                        MessageFormat format = entry.getKey();

                        try {
                            format.parse(requestURI);

                            return entry.getValue();
                        } catch (Exception e) {
                            // ignore
                        }
                    }
                    return requestURI;
                }
            }

            @Override
            public void handle(Context ctx) throws IOException, ServletException {
                ServletRequest req = ctx.getRequest();
                Transaction t = Cat.newTransaction(ctx.getType(), getRequestURI(req));

                // 关联键：Url 根事务建立后取当前 tree 的 messageId（顶层即根 id），写 MDC 供日志携带、写响应头供前端定位。
                try {
                    String traceId = Cat.getCurrentMessageId();
                    if (traceId != null) {
                        MDC.put(MDC_TRACE_ID, traceId);
                        if (ctx.getResponse() instanceof HttpServletResponse hres) {
                            hres.setHeader(HEADER_MESSAGE_ID, traceId);
                        }
                    }
                } catch (Throwable ignore) {
                    /* CAT 兜底 */
                }

                try {
                    ctx.handle();
                    customizeStatus(t, req);
                } catch (ServletException e) {
                    t.setStatus(e);
                    Cat.logError(e);
                    throw e;
                } catch (IOException e) {
                    t.setStatus(e);
                    Cat.logError(e);
                    throw e;
                } catch (Throwable e) {
                    t.setStatus(e);
                    Cat.logError(e);
                    throw new RuntimeException(e);
                } finally {
                    customizeUri(t, req);
                    safeIoData(t, req, ctx.getResponse());
                    int httpStatus = ctx.getResponse() instanceof HttpServletResponse h ? h.getStatus() : 0;
                    safeTraceLog(t, t.getName(), httpStatus, req, ctx.getResponse());
                    t.complete();
                }
            }
        };
    }

    // ---------- 本项目增强：出入参采集 + 路径归一化（均在事务异常/兜底保护下，绝不影响业务响应） ----------

    /** 把入参(query + json body)/出参(status + json body)脱敏截断后挂到 Url 事务 data 上。 */
    private static void safeIoData(Transaction t, ServletRequest req, ServletResponse res) {
        if (t == null) {
            return;
        }
        try {
            HttpServletRequest hr = (HttpServletRequest) req;
            StringBuilder sb = new StringBuilder(256);
            sb.append(hr.getMethod()).append(' ').append(hr.getRequestURI());
            String qs = hr.getQueryString();
            if (qs != null && !qs.isEmpty()) {
                sb.append('?').append(desensitize(truncate(qs)));
            }
            if (req instanceof ContentCachingRequestWrapper crq) {
                byte[] body = crq.getContentAsByteArray();
                if (body.length > 0 && isJson(crq.getContentType())) {
                    sb.append(" || req=").append(desensitize(truncate(new String(body, StandardCharsets.UTF_8))));
                }
            }
            if (res instanceof HttpServletResponse hres) {
                sb.append(" || status=").append(hres.getStatus());
            }
            if (res instanceof ContentCachingResponseWrapper crs) {
                byte[] body = crs.getContentAsByteArray();
                if (body.length > 0 && isJson(crs.getContentType())) {
                    sb.append(" || resp=").append(desensitize(truncate(new String(body, StandardCharsets.UTF_8))));
                }
            }
            // 反向深链：把「本次请求在 Kibana 里的日志查询地址」写进 CAT 链路，Log View 里可直接复制到 Kibana。
            String traceId = Cat.getCurrentMessageId();
            if (traceId != null && !traceId.isEmpty()) {
                sb.append(" || logs=").append(kibanaUrl(traceId));
            }
            t.addData(sb.toString());
        } catch (Throwable ignore) {
            /* CAT 兜底：采集失败绝不影响业务响应 */
        }
    }

    /**
     * 输出一条「链路摘要」日志：整棵调用树（Url → Sql/Event → 子事务）+ 各节点耗时 + 状态 + 出入参里的 SQL 文本，
     * 随 logback → ECS JSON → Filebeat → ES 落库，于是 Kibana 里按 catTraceId 就能看到完整链路，
     * 不再依赖 CAT 本地 dump（CAT 明细只留 1 小时）。
     *
     * <p>MDC 额外带 catUri / catStatus / catCostMs 三个字段（EcsEncoder 平铺到顶层），便于在 Kibana 直接按接口、
     * 状态码、耗时筛选。全程兜底，绝不影响业务。
     */
    private static void safeTraceLog(Transaction t, String uri, int httpStatus, ServletRequest req, ServletResponse res) {
        if (t == null || (!TRACE_LOG_ENABLED && !TRACE_DOC_ENABLED)) {
            return;
        }
        try {
            long cost = t.getDurationInMillis();
            String traceId = MDC.get(MDC_TRACE_ID);
            StringBuilder sb = new StringBuilder(512);
            sb.append("CAT_TRACE ").append(uri).append(" || status=").append(httpStatus)
                    .append(" || cost=").append(cost).append("ms");
            appendChildren(sb, t, 1);
            if (traceId != null && !traceId.isEmpty()) {
                sb.append(" || catView=").append(catViewUrl(traceId));
                sb.append(" || logs=").append(kibanaUrl(traceId));
            }
            String msg = sb.length() > MAX_SUMMARY ? sb.substring(0, MAX_SUMMARY) + "...(truncated)" : sb.toString();

            if (TRACE_LOG_ENABLED) {
                MDC.put("catUri", uri);
                MDC.put("catStatus", String.valueOf(httpStatus));
                MDC.put("catCostMs", String.valueOf(cost));
                try {
                    TRACE_LOG.info(msg);
                } finally {
                    MDC.remove("catUri");
                    MDC.remove("catStatus");
                    MDC.remove("catCostMs");
                }
            }
            emitTraceDoc(t, msg, traceId, uri, httpStatus, cost, req, res);
        } catch (Throwable ignore) {
            /* CAT/日志兜底：摘要失败不影响业务，也不影响 CAT 上报 */
        }
    }

    /**
     * 输出一条【结构化】链路文档：一次请求一条，带层级（spans[].depth / path）与真 JSON 的出入参，
     * 供 Kibana 展开文档的 JSON 页签直接看调用树与请求/响应体。
     *
     * <p>spans 用「扁平 + depth/path」而不是递归嵌套：Kibana JSON 页签里按顺序看就是缩进树，
     * 也能用 ES|QL（8.13 为 preview）或 Lens 表格按 path 排序展示。
     * request/response 解析成 JSON 对象写入；解析失败（如超长被截断）退化为 {"raw": "..."}。
     */
    private static void emitTraceDoc(Transaction t, String message, String traceId, String uri,
                                     int httpStatus, long cost, ServletRequest req, ServletResponse res) {
        if (!TRACE_DOC_ENABLED) {
            return;
        }
        try {
            ObjectNode root = TRACE_JSON.createObjectNode();
            root.put("@timestamp", Instant.now().toString());
            root.putObject("service").put("name", CAT_APP_NAME);
            root.putObject("event").put("dataset", CAT_APP_NAME + ".trace");
            ObjectNode logObj = root.putObject("log");
            logObj.put("level", httpStatus >= 500 ? "ERROR" : (httpStatus >= 400 ? "WARN" : "INFO"));
            logObj.put("logger", "CAT_TRACE");
            root.put("message", message);
            if (traceId != null && !traceId.isEmpty()) {
                root.put("catTraceId", traceId);
            }

            ObjectNode tr = root.putObject("trace");
            tr.put("uri", uri);
            tr.put("status", httpStatus);
            tr.put("costMs", cost);
            tr.put("success", httpStatus < 400);
            if (traceId != null && !traceId.isEmpty()) {
                tr.put("viewUrl", catViewUrl(traceId));
                tr.put("kibanaUrl", kibanaUrl(traceId));
            }

            ArrayNode spans = root.putArray("spans");
            ObjectNode self = TRACE_JSON.createObjectNode();
            self.put("depth", 0);
            self.put("path", "0");
            self.put("type", t.getType());
            self.put("name", t.getName());
            self.put("costMs", cost);
            self.put("success", httpStatus < 400);
            spans.add(self);
            List<ObjectNode> children = new ArrayList<>();
            collectSpans(t, 1, "0", children);
            for (ObjectNode n : children) {
                spans.add(n);
            }

            if (req instanceof ContentCachingRequestWrapper crq) {
                JsonNode body = jsonBody(crq.getContentAsByteArray(), crq.getContentType());
                if (body != null) {
                    root.set("request", body);
                }
            }
            if (res instanceof ContentCachingResponseWrapper crs) {
                JsonNode body = jsonBody(crs.getContentAsByteArray(), crs.getContentType());
                if (body != null) {
                    root.set("response", body);
                }
            }

            TRACE_DOC_LOG.info(TRACE_JSON.writeValueAsString(root));
        } catch (Throwable ignore) {
            /* 兜底：文档构造失败只丢这条链路文档，不影响业务与其它日志 */
        }
    }

    /** 深度优先收集子节点为扁平列表（带 depth 与 path，如 0.2.1）。 */
    private static void collectSpans(Transaction parent, int depth, String path, List<ObjectNode> out) {
        if (depth > MAX_DEPTH || out.size() >= MAX_SPANS) {
            return;
        }
        int i = 0;
        for (Message m : parent.getChildren()) {
            if (out.size() >= MAX_SPANS || i >= MAX_CHILDREN_PER_NODE) {
                return;
            }
            boolean isTx = m instanceof Transaction;
            boolean success = m.isSuccess();
            // 跳过 CatFilter 自己打的入口事件（URL.Server / URL.Method）：不是调用节点，只会给树添噪音；失败时保留。
            if (!isTx && success && isEntryEvent(m)) {
                continue;
            }
            String childPath = path + "." + (++i);
            ObjectNode n = TRACE_JSON.createObjectNode();
            n.put("depth", depth);
            n.put("path", childPath);
            n.put("type", m.getType());
            n.put("name", m.getName());
            n.put("success", success);
            if (!success) {
                n.put("status", cut(m.getStatus(), 120));
            }
            if (isTx) {
                n.put("costMs", ((Transaction) m).getDurationInMillis());
            }
            Object data = m.getData();
            if (data != null && ("Sql".equals(m.getType()) || !success)) {
                String s = cut(desensitize(String.valueOf(data)), MAX_SPAN_DATA);
                int at = s.indexOf(" | params=");
                if (at >= 0) {
                    n.put("sql", s.substring(0, at).replaceFirst("^sql=", ""));
                    n.put("params", s.substring(at + " | params=".length()));
                } else {
                    n.put("data", s);
                }
            }
            out.add(n);
            if (isTx) {
                collectSpans((Transaction) m, depth + 1, childPath, out);
            }
        }
    }

    /** 把 json 请求/响应体解析成 JsonNode（脱敏 + 截断）；失败退化为 {"raw":...}，非 json 返回 null。 */
    private static JsonNode jsonBody(byte[] body, String contentType) {
        if (body == null || body.length == 0 || !isJson(contentType)) {
            return null;
        }
        String s = desensitize(new String(body, StandardCharsets.UTF_8));
        if (s.length() > MAX_JSON_BODY) {
            s = s.substring(0, MAX_JSON_BODY);
        }
        try {
            return TRACE_JSON.readTree(s);
        } catch (Throwable e) {
            ObjectNode o = TRACE_JSON.createObjectNode();
            o.put("raw", s);
            return o;
        }
    }

    /** CatFilter 自身打的入口事件（URL.Server / URL.Method），属于元信息而非调用节点。 */
    private static boolean isEntryEvent(Message m) {
        String type = m.getType();
        return CatConstants.TYPE_URL.equals(type) || CatConstants.TYPE_URL_FORWARD.equals(type);
    }

    /** 深度优先拼调用树；每行前缀按层缩进，节点带 类型:名称(耗时) [非成功状态] {data 截断}。 */
    private static void appendChildren(StringBuilder sb, Transaction parent, int depth) {
        if (depth > MAX_DEPTH || sb.length() > MAX_SUMMARY) {
            return;
        }
        String prefix = " ".repeat(depth * 2) + "> ";
        int n = 0;
        for (Message m : parent.getChildren()) {
            if (n++ >= MAX_CHILDREN_PER_NODE || sb.length() > MAX_SUMMARY) {
                sb.append(" ...(more)");
                return;
            }
            boolean isTx = m instanceof Transaction;
            if (!isTx && m.isSuccess() && isEntryEvent(m)) {
                continue;
            }
            sb.append("  ").append(prefix).append(m.getType()).append(':').append(m.getName());
            boolean success = m.isSuccess();
            if (m instanceof Transaction ct) {
                sb.append('(').append(ct.getDurationInMillis()).append("ms)");
            }
            if (!success) {
                sb.append('[').append(cut(m.getStatus(), 60)).append(']');
            }
            // 只有 SQL 节点（data 里是真实 SQL + 参数）和失败节点才带 data，避免摘要被 URL.Server 等事件灌满。
            if ("Sql".equals(m.getType()) || !success) {
                Object data = m.getData();
                if (data != null) {
                    sb.append(" {").append(cut(desensitize(String.valueOf(data)), MAX_NODE_DATA)).append('}');
                }
            }
            if (m instanceof Transaction ct) {
                appendChildren(sb, ct, depth + 1);
            }
        }
    }

    private static String catViewUrl(String traceId) {
        return CAT_LOGVIEW_BASE + "?domain=" + CAT_APP_NAME + "&messageId=" + traceId;
    }

    private static String kibanaUrl(String traceId) {
        return KIBANA_DISCOVER_BASE + "/#/?_a=(query:(language:kuery,query:'catTraceId:%20\"" + traceId
                + "\"'),time:(from:now-24h,to:now))";
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String strProp(String envKey, String sysKey, String def) {
        String v = System.getenv(envKey);
        if (v == null || v.isBlank()) {
            v = System.getProperty(sysKey);
        }
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    private static boolean boolProp(String envKey, String sysKey, boolean def) {
        String v = strProp(envKey, sysKey, null);
        return v == null ? def : Boolean.parseBoolean(v);
    }

    /** /api/sessions/123 → /api/sessions/{num}；/api/mcp/5/toggle → /api/mcp/{num}/toggle。 */
    private static String normalize(String uri) {
        if (uri == null || uri.isEmpty()) {
            return "/";
        }
        String[] segs = uri.split("/");
        StringBuilder sb = new StringBuilder(uri.length());
        for (int i = 0; i < segs.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            String s = segs[i];
            sb.append(!s.isEmpty() && NUMERIC_SEGMENT.matcher(s).matches() ? "{num}" : s);
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    private static boolean isJson(String contentType) {
        return contentType != null && contentType.toLowerCase().contains("json");
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_BODY ? s : s.substring(0, MAX_BODY) + "...(truncated)";
    }

    private static String desensitize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        String out = SENSITIVE_JSON.matcher(s).replaceAll("$1\"***\"");
        out = SENSITIVE_QUERY.matcher(out).replaceAll("$1***");
        return out;
    }

    protected static interface Handler {
        public void handle(Context ctx) throws IOException, ServletException;
    }

    protected static class Context {
        private FilterChain m_chain;

        private List<Handler> m_handlers;

        private int m_index;

        private int m_mode;

        private String m_rootId;

        private String m_parentId;

        private String m_id;

        private ServletRequest m_request;

        private ServletResponse m_response;

        private boolean m_top;

        private String m_type;

        public Context(ServletRequest request, ServletResponse response, FilterChain chain, List<Handler> handlers) {
            m_request = request;
            m_response = response;
            m_chain = chain;
            m_handlers = handlers;
        }

        public String getId() {
            return m_id;
        }

        public void setId(String id) {
            m_id = id;
        }

        public int getMode() {
            return m_mode;
        }

        public void setMode(int mode) {
            m_mode = mode;
        }

        public String getParentId() {
            return m_parentId;
        }

        public void setParentId(String parentId) {
            m_parentId = parentId;
        }

        public ServletRequest getRequest() {
            return m_request;
        }

        public ServletResponse getResponse() {
            return m_response;
        }

        public String getRootId() {
            return m_rootId;
        }

        public void setRootId(String rootId) {
            m_rootId = rootId;
        }

        public String getType() {
            return m_type;
        }

        public void setType(String type) {
            m_type = type;
        }

        public void handle() throws IOException, ServletException {
            if (m_index < m_handlers.size()) {
                Handler handler = m_handlers.get(m_index++);

                handler.handle(this);
            } else {
                m_chain.doFilter(m_request, m_response);
            }
        }

        public boolean isTop() {
            return m_top;
        }

        public void setTop(boolean top) {
            m_top = top;
        }
    }
}
