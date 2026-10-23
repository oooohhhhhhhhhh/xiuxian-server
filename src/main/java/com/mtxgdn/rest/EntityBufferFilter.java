package com.mtxgdn.rest;

import com.mtxgdn.util.GameLogger;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.http.server.Request;

import java.io.ByteArrayInputStream;

/**
 * 把请求体预读进内存，供后续过滤器/资源重复读取。
 *
 * <p><b>为什么不再用 {@code System.out}</b>：这里原本每处理一个 POST/PUT 就
 * {@code System.out.println("[EntityBuffer] 缓冲 N bytes")}。它有两层问题：</p>
 * <ol>
 *   <li><b>噪音</b>：每个写请求一行，正常运行时把真正的告警淹没在刷屏里；</li>
 *   <li><b>不进日志文件</b>：{@code System.out} 只到控制台，{@code GameLogger}
 *       才写文件，所以事后排查根本找不到这些行。</li>
 * </ol>
 * <p>改成 DEBUG 级别的 {@code GameLogger}：默认 INFO 下不输出，
 * 需要时调 {@code logging.level=DEBUG} 就能在日志文件里看到。</p>
 */
@Provider
@Priority(Priorities.ENTITY_CODER - 100)
public class EntityBufferFilter implements ContainerRequestFilter {

    private static final GameLogger LOG = GameLogger.getLogger(EntityBufferFilter.class);

    private static final int MAX_POST_SIZE = 65536;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String method = requestContext.getMethod();
        if (!"POST".equalsIgnoreCase(method) && !"PUT".equalsIgnoreCase(method)) {
            return;
        }

        try {
            Object grizzlyRequestObj = requestContext.getProperty(Request.class.getName());
            if (grizzlyRequestObj instanceof Request grizzlyRequest) {
                Buffer buffer = grizzlyRequest.getPostBody(MAX_POST_SIZE);
                if (buffer != null) {
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    requestContext.setEntityStream(new ByteArrayInputStream(bytes));
                    LOG.debug("缓冲请求体 " + bytes.length + " bytes: "
                            + requestContext.getMethod() + " " + requestContext.getUriInfo().getPath());
                }
            }
        } catch (Exception e) {
            // 保持原有语义：预读失败不中断请求，但至少要走日志文件而不是 stdout
            LOG.warn("缓冲请求体失败: " + e.getMessage());
        }
    }
}
