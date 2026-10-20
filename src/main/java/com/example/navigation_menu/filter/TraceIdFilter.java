package com.example.navigation_menu.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 给每个请求分配 traceId：优先复用上游传进来的，没有才自己生成。
 * traceId 放进 MDC，日志框架自动带在每一行上；同时回写响应头，方便前端报问题时直接给出。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "traceId";
    public static final String HEADER_TRACE_ID = "X-Trace-Id";
    private static final String HEADER_TRACEPARENT = "traceparent";

    /** 只放行字母数字和短横线，且不超过 64 字符：请求头是外部输入，不能原样写进日志 */
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");
    /** W3C traceparent 的 trace-id 固定 32 位小写十六进制 */
    private static final Pattern TRACEPARENT_TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");
    private static final String INVALID_TRACEPARENT_TRACE_ID = "00000000000000000000000000000000";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        /*
         * doFilter 是同步阻塞的"放行"，不是把 request 变成 response。
         * request 和 response 从头到尾是两个并存的对象：前者只读，后者是等着被写的空壳。
         *
         *   Tomcat 解析完 HTTP 报文 → request 已装好、response 还是空壳
         *        │
         *        │  ① 先写响应头：头一旦提交（下游 flush、或 body 超出缓冲区）就再也加不上了
         *        ▼
         *   chain.doFilter(request, response)   ← 把两个对象递出去，自己挂起等
         *        ▼
         *   下一个 Filter / DispatcherServlet → Controller → Service → Handler → Mapper
         *        │
         *        │  Spring MVC 把 Controller 返回的对象序列化成 JSON，写进 response
         *        ▼
         *   ◀── doFilter 返回，控制权回到这里，此时这次请求已经彻底处理完
         *        ▼
         *   ② finally 里清 MDC：放行前清会把后面整条业务链路的 traceId 抹掉，所以只能在这清
         */
        String traceId = resolveTraceId(request);
        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER_TRACE_ID, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Tomcat 线程会被复用，不清理就会把这次的 traceId 带给下一个请求
            MDC.remove(MDC_KEY);
        }
    }

    private String resolveTraceId(HttpServletRequest request) {
        String traceIdFromTraceparent = parseTraceparent(request.getHeader(HEADER_TRACEPARENT));
        if (traceIdFromTraceparent != null) {
            return traceIdFromTraceparent;
        }

        String fromHeader = request.getHeader(HEADER_TRACE_ID);
        if (fromHeader != null) {
            if (SAFE_TRACE_ID.matcher(fromHeader).matches()) {
                return fromHeader;
            }
            // 只记长度，不记原值 —— 原值正是要拦下来的东西
            log.warn("rejected unsafe trace id from header, length={}", fromHeader.length());
        }

        return UUID.randomUUID().toString().replace("-", "");
    }

    /** W3C traceparent 形如 00-&lt;32 位 trace-id&gt;-&lt;16 位 parent-id&gt;-&lt;2 位 flags&gt;，取第二段 */
    private String parseTraceparent(String traceparent) {
        if (traceparent == null) {
            return null;
        }
        String[] parts = traceparent.split("-");
        if (parts.length != 4) {
            return null;
        }
        String traceId = parts[1];
        if (!TRACEPARENT_TRACE_ID.matcher(traceId).matches()
                || INVALID_TRACEPARENT_TRACE_ID.equals(traceId)) {
            return null;
        }
        return traceId;
    }
}
