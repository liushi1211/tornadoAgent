package com.tornado.adapter.web;

import com.dianping.cat.Cat;

import java.io.File;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * cat-client 3.0.1 启动期【文件式】初始化（必须在 {@code main()} 第一行、{@code SpringApplication.run} 之前调用）。
 *
 * <p>为什么不能用 {@link Cat#initializeByDomain} 的临时文件方案：3.0.1 里 {@code DefaultClientConfigManager} 走的是
 * plexus 无参 {@code initialize()}（{@code DefaultClientConfigManager.java:149}），它【硬编码】只读
 * {@code Cat.getCatHome() + "/client.xml"}，完全忽略 {@code Cat.initialize} 通过 ctx 传入的
 * {@code cat-client-config-file} 属性（全 jar 里只有 setAttribute、没有 getAttribute）。所以临时文件方案根本不生效，
 * 现象就是日志反复打印 {@code ".../client.xml not found"} + {@code "CAT client was disabled due to no CAT servers configured!"}。
 *
 * <p>而 {@code Cat} 的初始化又是【懒加载 + s_init 一次性锁死】：进程内任何更早触碰 Cat 的代码（典型是启动期一条 SQL
 * 触发 CatMybatisInterceptor）都会用错误的默认路径完成初始化并禁用客户端，事后无法挽回。
 *
 * <p>正解：把一份合法的 {@code client.xml}（带 {@code <server>}）真实写到 {@code getCatHome()} 解析出的目录，
 * 并用 {@code CAT_HOME} 系统属性把该目录钉死为绝对路径（默认值 {@code /data/appdatas/cat/} 在 Windows 上会按
 * 当前盘符根解析、随工作目录漂移、很脆）。这样无论谁抢先触发懒加载，{@code DefaultClientConfigManager} 读到的都是
 * 这份带 server 的文件 → {@code ClientConfigValidator} 看到 servers 非空 → enabled=true → 链路正常上报。
 *
 * <p>端口要点：数据口 2280（宿主直映射到容器），路由拉取口 http-port=8070（宿主 8070→容器 8080）。
 * http-port 绝不能用 8080——那是应用自身 Tomcat 的口，会打到应用自己导致路由拉取失败。
 */
public final class CatBootstrap {

    private CatBootstrap() {
    }

    /**
     * 生成 client.xml + 设定 CAT_HOME + 抢跑初始化。读不到配置时用与 application.yml 一致的默认值。
     * CAT 任何异常都吞掉，绝不影响主程序启动。
     */
    public static void initFromSystemProperties() {
        if (!boolProp("CAT_ENABLED", "cat.enabled", true)) {
            return;
        }
        String host = strProp("CAT_SERVER_HOST", "cat.server.host", "127.0.0.1");
        int tcpPort = intProp("CAT_SERVER_TCP_PORT", "cat.server.tcp-port", 2280);
        int httpPort = intProp("CAT_SERVER_HTTP_PORT", "cat.server.http-port", 8070);
        try {
            // 1) 选一个绝对、可写的 CAT_HOME 目录（放在 java.io.tmpdir 下，跨工作目录稳定）
            Path catHome = Path.of(System.getProperty("java.io.tmpdir"), "cat-client-home");
            Files.createDirectories(catHome);
            File clientXml = catHome.resolve("client.xml").toFile();

            // 2) 写一份合法的 client.xml：mode=client，含 <server>（ip/port/http-port），domain 由 app.properties 提供、
            //    DefaultClientConfigManager 会 loadConfigFromEnviroment() 读出 app.name 并 merge 进来。
            String xml = buildClientXml(host, tcpPort, httpPort);
            try (Writer w = new OutputStreamWriter(Files.newOutputStream(clientXml.toPath()), StandardCharsets.UTF_8)) {
                w.write(xml);
            }

            // 3) 把 getCatHome() 钉到该目录（getCatHome 读 CAT_HOME 系统属性；env→system，未设 env 时 system 生效）。
            //    必须以 / 结尾无关（getCatHome 会自动补），这里给绝对路径。
            System.setProperty("CAT_HOME", catHome.toAbsolutePath().toString());

            // 4) 抢跑一次懒加载，让 DefaultClientConfigManager 读到上面的 client.xml 并定型（s_init=true）。
            //    此时 client.xml 存在且含 server → validator 不再 disable。
            Cat.newTransaction("CatBootstrap", "warmup").complete();
            System.out.println("[CAT] client.xml at " + clientXml.getAbsolutePath()
                    + " (server " + host + ":" + tcpPort + ", http-port " + httpPort + "); CAT_HOME=" + catHome);
        } catch (Throwable t) {
            System.err.println("[CAT] bootstrap failed, 链路不会上报（不影响业务）: " + t);
        }
    }

    private static String buildClientXml(String host, int tcpPort, int httpPort) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<config mode=\"client\">\n"
                + "    <servers>\n"
                + "        <server ip=\"" + host + "\" port=\"" + tcpPort + "\" http-port=\"" + httpPort + "\"/>\n"
                + "    </servers>\n"
                + "</config>\n";
    }

    private static String strProp(String envKey, String sysKey, String def) {
        String v = System.getenv(envKey);
        if (v == null || v.isBlank()) {
            v = System.getProperty(sysKey);
        }
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    private static int intProp(String envKey, String sysKey, int def) {
        String v = strProp(envKey, sysKey, null);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean boolProp(String envKey, String sysKey, boolean def) {
        String v = strProp(envKey, sysKey, null);
        return v == null ? def : Boolean.parseBoolean(v);
    }
}
