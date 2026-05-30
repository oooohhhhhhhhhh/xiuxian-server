package com.mtxgdn.rest;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.http.server.Request;

import java.io.ByteArrayInputStream;

@Provider
@Priority(Priorities.ENTITY_CODER - 100)
public class EntityBufferFilter implements ContainerRequestFilter {

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
                    System.out.println("[EntityBuffer] 缓冲 " + bytes.length + " bytes");
                }
            }
        } catch (Exception e) {
            System.out.println("[EntityBuffer] 缓冲请求体失败: " + e.getMessage());
        }
    }
}
