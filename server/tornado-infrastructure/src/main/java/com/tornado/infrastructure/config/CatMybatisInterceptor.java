package com.tornado.infrastructure.config;

import com.dianping.cat.Cat;
import com.dianping.cat.message.Message;
import com.dianping.cat.message.Transaction;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * CAT SQL 链路埋点：拦截 MyBatis {@link Executor} 的 query/update，为每条 SQL 建 {@code Sql} 事务，
 * name 用 {@code Mapper.method}（可聚合、不含变量），SQL 命令类型记为 Event。
 *
 * <p>默认关闭（{@code cat.enabled=true} 生效），且所有 CAT 调用兜底，异常绝不影响业务 SQL。
 * 作为 {@code @Component} 由 MyBatis-Plus starter 自动装入 SqlSessionFactory 的插件链。
 */
@Component
@ConditionalOnProperty(prefix = "cat", name = "enabled", havingValue = "true")
@Intercepts({
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class})
})
public class CatMybatisInterceptor implements Interceptor {

    /** SQL 文本折行/连续空白压平，避免撑爆单条 message 且便于在 LogView 里阅读。 */
    private static final Pattern WS = Pattern.compile("\\s+");

    /** 单条 addData 上限，远低于 cat-client maxMessageLength(默认5000)，防截断丢链路细节。 */
    private static final int MAX_DATA = 2000;

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Object[] args = invocation.getArgs();
        MappedStatement ms = args.length > 0 && args[0] instanceof MappedStatement m ? m : null;
        Object param = args.length > 1 ? args[1] : null;
        String name = resolveName(ms);
        Transaction t = safeNew(name);
        safeSqlData(t, ms, param);
        try {
            Object result = invocation.proceed();
            safeStatus(t, Message.SUCCESS);
            return result;
        } catch (Throwable e) {
            safeFail(t, e);
            throw e;
        } finally {
            safeComplete(t);
        }
    }

    /** com.x.y.ChatMessageMapper.selectList → ChatMessageMapper.selectList */
    private String resolveName(MappedStatement ms) {
        if (ms == null) {
            return "unknown";
        }
        String id = ms.getId();
        String[] parts = id.split("\\.");
        if (parts.length >= 2) {
            return parts[parts.length - 2] + "." + parts[parts.length - 1];
        }
        return id;
    }

    @Override
    public Object plugin(Object target) {
        return target instanceof Executor ? Plugin.wrap(target, this) : target;
    }

    @Override
    public void setProperties(Properties properties) {
        /* noop */
    }

    // ---------- CAT 兜底封装 ----------

    private Transaction safeNew(String name) {
        try {
            return Cat.newTransaction("Sql", name);
        } catch (Throwable ignore) {
            return null;
        }
    }

    /** 把真实 SQL 文本 + 绑定参数挂到 Sql 事务的 data 上（LogView 点该节点可见）。全程兜底，失败不影响业务。 */
    private void safeSqlData(Transaction t, MappedStatement ms, Object param) {
        if (t == null || ms == null) {
            return;
        }
        try {
            BoundSql bs = ms.getBoundSql(param);
            String sql = WS.matcher(bs.getSql()).replaceAll(" ").trim();
            String data = "sql=" + truncate(sql);
            String params = renderParams(ms.getConfiguration(), bs, param);
            if (!params.isEmpty()) {
                data = data + " | params=" + truncate(params);
            }
            t.addData(data);
        } catch (Throwable ignore) {
            /* CAT 兜底：拿不到 SQL 不影响业务与主链路 */
        }
    }

    /** 依 parameterMappings 顺序把占位符实际值渲染成 [v1, v2, ...]（尽力而为，取不到用 ? 占位）。 */
    private String renderParams(Configuration cfg, BoundSql bs, Object param) {
        List<ParameterMapping> mappings = bs.getParameterMappings();
        if (mappings == null || mappings.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ParameterMapping pm : mappings) {
            String prop = pm.getProperty();
            Object value;
            try {
                if (bs.hasAdditionalParameter(prop)) {
                    value = bs.getAdditionalParameter(prop); // foreach 展开项等
                } else {
                    MetaObject mo = cfg.newMetaObject(param);
                    value = mo.getValue(prop);
                }
            } catch (Throwable e) {
                value = "?"; // 单标量/取不到值时不阻塞，占位即可
            }
            sb.append(value).append(", ");
        }
        if (sb.length() > 2) {
            sb.setLength(sb.length() - 2);
        }
        return sb.toString();
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_DATA ? s : s.substring(0, MAX_DATA) + "...(truncated)";
    }

    private void safeStatus(Transaction t, String status) {
        if (t != null) {
            try {
                t.setStatus(status);
            } catch (Throwable ignore) {
                /* noop */
            }
        }
    }

    private void safeFail(Transaction t, Throwable e) {
        if (t != null) {
            try {
                t.setStatus(e);
                Cat.logError(e);
            } catch (Throwable ignore) {
                /* noop */
            }
        }
    }

    private void safeComplete(Transaction t) {
        if (t != null) {
            try {
                t.complete();
            } catch (Throwable ignore) {
                /* noop */
            }
        }
    }
}
