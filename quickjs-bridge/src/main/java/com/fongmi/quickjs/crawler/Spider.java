package com.fongmi.quickjs.crawler;

import com.fongmi.quickjs.bean.Res;
import com.fongmi.quickjs.method.Console;
import com.fongmi.quickjs.method.Global;
import com.fongmi.quickjs.method.Local;
import com.fongmi.quickjs.utils.Async;
import com.fongmi.quickjs.utils.JSUtil;
import com.fongmi.quickjs.utils.Module;
import com.github.catvod.utils.Asset;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.UriUtil;
import com.github.catvod.utils.Util;
import com.whl.quickjs.wrapper.JSArray;
import com.whl.quickjs.wrapper.JSObject;
import com.whl.quickjs.wrapper.QuickJSContext;

import org.json.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.commons.lang3.StringUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class Spider {

    private static final Logger log = LoggerFactory.getLogger(Spider.class);

    public String siteKey;

    private final ExecutorService executor;
    private final ClassLoader dex;
    private final String api;

    private QuickJSContext ctx;
    private JSObject jsObject;
    private Global global;
    private boolean cat;

    public Spider(String api, ClassLoader dex) {
        this.executor = Executors.newSingleThreadExecutor();
        this.api = api;
        this.dex = dex;
    }

    private <T> Future<T> submit(Callable<T> callable) {
        return executor.submit(callable);
    }

    private Object call(String func, Object... args) throws Exception {
        return submit(() -> Async.run(jsObject, func, args)).get().get();
    }

    public void init(String extend) {
        try {
            initializeJS();
            call("init", submit(() -> getExt(extend)).get());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public String homeContent(boolean filter) throws Exception {
        return (String) call("home", filter);
    }

    public String homeVideoContent() throws Exception {
        return (String) call("homeVod");
    }

    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        JSObject obj = submit(() -> JSUtil.toObject(ctx, extend)).get();
        return (String) call("category", tid, pg, filter, obj);
    }

    public String detailContent(List<String> ids) throws Exception {
        return (String) call("detail", ids.get(0));
    }

    public String searchContent(String key, boolean quick) throws Exception {
        return (String) call("search", key, quick);
    }

    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return (String) call("search", key, quick, pg);
    }

    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        JSArray array = submit(() -> JSUtil.toArray(ctx, vipFlags)).get();
        return (String) call("play", flag, id, array);
    }

    public String liveContent(String url) throws Exception {
        return (String) call("live", url);
    }

    public boolean manualVideoCheck() throws Exception {
        return Boolean.TRUE.equals(call("sniffer"));
    }

    public boolean isVideoFormat(String url) throws Exception {
        return Boolean.TRUE.equals(call("isVideo", url));
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        return "catvod".equals(params.get("from")) ? proxy2(params) : proxy1(params);
    }

    public String action(String action) throws Exception {
        return (String) call("action", action);
    }

    public void destroy() {
        try {
            call("destroy");
        } catch (Throwable e) {
            e.printStackTrace();
        }
        try {
            releaseJS();
        } catch (Throwable e) {
            e.printStackTrace();
        } finally {
            executor.shutdownNow();
        }
    }

    private void releaseJS() throws Exception {
        submit(() -> {
            if (global != null) global.destroy();
            if (jsObject != null) jsObject.release();
            if (ctx != null) ctx.destroy();
            return null;
        }).get();
    }

    private void initializeJS() throws Exception {
        submit(() -> {
            createCtx();
            createFun();
            createObj();
            return null;
        }).get();
    }

    private void createCtx() {
        ctx = QuickJSContext.create();
        ctx.setConsole(new Console());
        ctx.evaluate(Asset.read("js/lib/http.js"));
        ctx.getGlobalObject().setProperty("local", Local.class);
        ctx.setModuleLoader(new QuickJSContext.BytecodeModuleLoader() {
            @Override
            public String moduleNormalizeName(String baseModuleName, String moduleName) {
                // JNI NewStringUTF 会损坏 emoji 等非 BMP 字符；相对路径一律相对原始 api 解析。
                String base = baseModuleName;
                if (isMangledModuleUrl(base) || !StringUtils.startsWith(base, "http")) {
                    base = encodeModuleUrl(api);
                }
                return UriUtil.resolve(base, moduleName);
            }

            @Override
            public byte[] getModuleBytecode(String moduleName) {
                // 失败时返回 null 而不是抛异常：异常穿透 JNI 模块回调会让原生层在
                // 挂起异常状态下继续执行，属未定义行为。返回 null 由原生层抛干净的 QuickJSException。
                try {
                    String url = repairModuleUrl(moduleName);
                    String content = Module.get().fetch(url);
                    if (content == null || content.isEmpty() || Module.looksLikeNonJs(content)) {
                        log.warn("[quickjs] 模块内容无效: {} (fetched={})", url,
                                content == null ? "null" : content.length());
                        return null;
                    }
                    byte[] bytecode = ctx.compileModule(content, url);
                    if (bytecode == null || bytecode.length == 0) {
                        log.warn("[quickjs] 模块编译返回空: {} (sourceLen={})", url, content.length());
                        return null;
                    }
                    return bytecode;
                } catch (Throwable e) {
                    log.warn("[quickjs] 模块编译失败: {} -> {}", moduleName, e.getMessage());
                    return null;
                }
            }
        });
    }

    /**
     * QuickJS/JNI 路径禁止直接塞入未编码的 emoji。上游 NewStringUTF 会把 🖥 变成 ð¥，
     * 随后 HTTP 404「404 page not found」再被 compileModule → expecting ';'。
     */
    private static String encodeModuleUrl(String url) {
        if (url == null || url.isEmpty()) return url;
        try {
            // 仅编码 path/query 中的非 ASCII；已编码的 %XX 保持不变
            StringBuilder sb = new StringBuilder(url.length() + 16);
            for (int i = 0; i < url.length(); ) {
                int cp = url.codePointAt(i);
                i += Character.charCount(cp);
                if (cp <= 0x7F) {
                    sb.append((char) cp);
                } else {
                    byte[] bytes = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
                    for (byte b : bytes) {
                        sb.append('%');
                        String hex = Integer.toHexString(b & 0xFF).toUpperCase();
                        if (hex.length() == 1) sb.append('0');
                        sb.append(hex);
                    }
                }
            }
            return sb.toString();
        } catch (Throwable e) {
            return url;
        }
    }

    /** 检测 UTF-8 被按 Latin-1 解读后的典型乱码（如 🖥 → ð¥） */
    private static boolean isMangledModuleUrl(String url) {
        if (url == null || url.isEmpty()) return true;
        // Latin-1 高位控制/扩展字符出现在 http URL path 中，几乎一定是编码损坏
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c >= 0x80 && c <= 0xFF) return true;
        }
        return false;
    }

    private String repairModuleUrl(String moduleName) {
        if (moduleName == null || moduleName.isEmpty()) return encodeModuleUrl(api);
        if (isMangledModuleUrl(moduleName)) {
            // 从损坏的 URL 里尽量取出相对文件名，相对正确 api 重解析
            String file = moduleName;
            int slash = Math.max(moduleName.lastIndexOf('/'), moduleName.lastIndexOf('\\'));
            if (slash >= 0 && slash + 1 < moduleName.length()) {
                file = moduleName.substring(slash + 1);
            }
            return UriUtil.resolve(encodeModuleUrl(api), file);
        }
        return encodeModuleUrl(moduleName);
    }

    /** 对齐 TV：只从 spider.jar ClassLoader 加载 com.github.catvod.js.Function。 */
    private void createFun() {
        try {
            global = Global.create(ctx, executor);
            Class<?> clz = dex.loadClass("com.github.catvod.js.Function");
            clz.getDeclaredConstructor(QuickJSContext.class).newInstance(ctx);
        } catch (Throwable e) {
            log.warn("[quickjs] 加载 jar Function 失败: {}: {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }

    private void createObj() {
        String spider = "__JS_SPIDER__";
        String global = "globalThis." + spider;
        String content = Module.get().fetch(api);
        if (content == null || content.isEmpty() || Module.looksLikeNonJs(content)) {
            throw new IllegalStateException("JS api 内容为空，请检查网络或 api 地址: " + api);
        }
        cat = content.contains("__jsEvalReturn");
        // 模块名必须百分号编码：桌面 JVM 上 QuickJS→JNI NewStringUTF 无法可靠传递 emoji
        String moduleApi = encodeModuleUrl(api);
        ctx.evaluateModule(content.replace(spider, global), moduleApi);
        ctx.evaluateModule(String.format(Asset.read("js/lib/spider.js"), moduleApi));
        jsObject = (JSObject) ctx.getProperty(ctx.getGlobalObject(), spider);
    }

    private Object getExt(String ext) {
        if (!cat) return Json.isObj(ext) ? ctx.parse(ext) : ext;
        JSObject obj = ctx.createNewJSObject();
        obj.setProperty("stype", 3);
        obj.setProperty("skey", siteKey);
        if (!Json.isObj(ext)) obj.setProperty("ext", ext);
        else obj.setProperty("ext", (JSObject) ctx.parse(ext));
        return obj;
    }

    private Object[] proxy1(Map<String, String> params) throws Exception {
        JSObject obj = submit(() -> JSUtil.toObject(ctx, params)).get();
        JSArray proxy = (JSArray) call("proxy", obj);
        String json = submit(proxy::stringify).get();
        JSONArray array = new JSONArray(json);
        Map<String, String> headers = array.length() > 3 ? Json.toMap(array.optString(3)) : null;
        boolean base64 = array.length() > 4 && array.optInt(4) == 1;
        Object[] result = new Object[4];
        result[0] = array.optInt(0);
        result[1] = array.optString(1);
        result[2] = getStream(array.opt(2), base64);
        result[3] = headers;
        return result;
    }

    private Object[] proxy2(Map<String, String> params) throws Exception {
        String url = params.get("url");
        String header = params.get("header");
        JSArray array = submit(() -> JSUtil.toArray(ctx, Arrays.asList(url.split("/")))).get();
        Object object = submit(() -> ctx.parse(header)).get();
        String proxy = (String) call("proxy", array, object);
        Res res = Res.objectFrom(proxy);
        Object[] result = new Object[3];
        result[0] = res.getCode();
        result[1] = res.getContentType();
        result[2] = res.getStream();
        return result;
    }

    private ByteArrayInputStream getStream(Object o, boolean base64) {
        if (o instanceof byte[]) {
            return new ByteArrayInputStream((byte[]) o);
        } else {
            String content = o.toString();
            if (base64 && content.contains("base64,")) content = content.split("base64,")[1];
            return new ByteArrayInputStream(base64 ? Util.decode(content) : content.getBytes());
        }
    }
}
