package com.example.navigation_menu.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import net.logstash.logback.argument.StructuredArguments;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 访问日志：每个请求收尾时打一条 INFO，回答"哪些接口被调了、多慢、返回什么状态"。
 *
 * 排在 TraceIdFilter 之后。用 +2 而不是 +1，是因为 webMvcObservationFilter 占着 +1，
 * 同档会变成平局、先后不由自己决定（详见可观测性-链路追踪与MDC 里"谁来生成 traceId"一节）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
@Slf4j
public class AccessLogFilter extends OncePerRequestFilter {

    private static final String HEADER_FORWARDED_FOR = "X-Forwarded-For";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            // 耗时和状态码都要等 doFilter 返回才准 —— 状态码是 Controller 那一步才决定的
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            String method = request.getMethod();
            // 只记路径不记 query，避免把参数里的敏感信息带进日志平台
            String path = request.getRequestURI();
            int status = response.getStatus();
            String clientIp = resolveClientIp(request);

            // 同一批值写两遍是有原因的：kv() 只对 JSON 那路生效 —— logstash-logback-encoder
            // 没给 pattern 提供取结构化字段的转换器，实测控制台只会打印出光秃秃的 "access log"。
            // 所以控制台靠 {} 占位符，JSON 靠 kv()，两边各取所需。
            log.info("access, method={}, path={}, status={}, durationMs={}, clientIp={}",
                    method, path, status, durationMs, clientIp,
                    StructuredArguments.kv("eventType", "access"),
                    StructuredArguments.kv("method", method),
                    StructuredArguments.kv("path", path),
                    StructuredArguments.kv("status", status),
                    StructuredArguments.kv("durationMs", durationMs),
                    StructuredArguments.kv("clientIp", clientIp));
        }
    }

    /**
     * 取调用方 IP：有网关时真实客户端 IP 在 X-Forwarded-For 的第一段，没有就用直连地址。
     * 注意这个头是外部可伪造的，只有前面确实是自己可信的网关时才采信它。
     */
    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader(HEADER_FORWARDED_FOR);
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            int comma = forwardedFor.indexOf(',');
            return comma > 0 ? forwardedFor.substring(0, comma).trim() : forwardedFor.trim();
        }
        return request.getRemoteAddr();
    }
}
