package dev.worldcup.api;

import dev.worldcup.infrastructure.JsonCodec;
import dev.worldcup.shared.Failure;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiGuard extends OncePerRequestFilter {
    static final String REQUEST_ID = "worldcup.requestId";
    private static final int MAX_BODY = 65536;
    private final PublicAddress address;
    private final JsonCodec json;
    public ApiGuard(PublicAddress address, JsonCodec json) { this.address = address; this.json = json; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ID, requestId);
        response.setHeader("X-Request-Id", requestId);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        if (!request.getRequestURI().startsWith("/api/")) { chain.doFilter(request, response); return; }
        if (!"GET".equals(request.getMethod()) && !"POST".equals(request.getMethod())) {
            response.setHeader("Allow", "GET, POST");
            reject(response, 405, requestId); return;
        }
        if ("POST".equals(request.getMethod())) {
            String origin = request.getHeader("Origin");
            String key = request.getHeader("Idempotency-Key");
            if ((origin != null && !origin.equals(address.origin())) || "cross-site".equals(request.getHeader("Sec-Fetch-Site"))
                    || key == null || key.isBlank() || key.length() > 128) { reject(response, 400, requestId); return; }
            byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) { reject(response, 413, requestId); return; }
            if (body.length > 0) {
                String contentType = request.getContentType();
                if (contentType == null || !contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT).equals("application/json")) {
                    reject(response, 415, requestId); return;
                }
            }
            chain.doFilter(new BufferedRequest(request, body), response);
        } else chain.doFilter(request, response);
    }
    private void reject(HttpServletResponse response, int status, String requestId) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(json.write(ApiErrors.body(Failure.Code.INVALID_INPUT, requestId)));
    }
    private static final class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        BufferedRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }
        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }
        @Override public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous JSON only"); }
            };
        }
    }
}
