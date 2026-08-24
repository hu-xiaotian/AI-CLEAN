package com.aiclean.config;

import com.aiclean.annotation.RequirePermission;
import com.aiclean.common.JwtUtil;
import com.aiclean.common.R;
import com.aiclean.common.UserContext;
import com.aiclean.service.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * JWT 认证拦截器
 * <p>校验请求头中的 Token，解析后写入 {@link UserContext}；
 * 并校验带有 {@link RequirePermission} 注解的接口，未授权则返回 403。</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class JwtAuthInterceptor implements HandlerInterceptor {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;
    private final PermissionService permissionService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 放行预检请求
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String token = resolveToken(request);
        if (token == null) {
            writeUnauthorized(response, "未登录或登录已过期，请重新登录");
            return false;
        }

        Claims claims = jwtUtil.parseToken(token);
        if (claims == null) {
            writeUnauthorized(response, "登录状态无效或已过期，请重新登录");
            return false;
        }

        // 写入用户上下文
        Long userId = claims.get("userId") == null ? null : Long.valueOf(claims.get("userId").toString());
        String username = claims.getSubject();
        String role = claims.get("role", String.class);
        UserContext.set(new UserContext.CurrentUser(userId, username, role));

        // 接口级权限校验
        if (handler instanceof HandlerMethod) {
            HandlerMethod handlerMethod = (HandlerMethod) handler;
            RequirePermission requirePerm = handlerMethod.getMethodAnnotation(RequirePermission.class);
            if (requirePerm == null) {
                requirePerm = handlerMethod.getBeanType().getAnnotation(RequirePermission.class);
            }
            if (requirePerm != null) {
                if (!hasPermission(userId, role, requirePerm.value())) {
                    writeForbidden(response, "无权限访问：" + requirePerm.value());
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 校验当前用户是否拥有指定权限编码。管理员（admin）默认放行。
     */
    private boolean hasPermission(Long userId, String role, String permCode) {
        if ("admin".equals(role)) {
            return true;
        }
        if (userId == null || permCode == null || permCode.trim().isEmpty()) {
            return false;
        }
        try {
            List<String> codes = permissionService.listPermissionCodesByUserId(userId);
            return codes != null && codes.contains(permCode);
        } catch (Exception e) {
            log.warn("权限校验异常，默认拒绝. userId={}, permCode={}: {}", userId, permCode, e.getMessage());
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 请求结束后清理上下文，避免线程复用导致的数据串用
        UserContext.clear();
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header != null && header.startsWith(PREFIX)) {
            return header.substring(PREFIX.length()).trim();
        }
        // 兼容从查询参数传递 token（如文件下载场景）
        String param = request.getParameter("token");
        if (param != null && !param.isEmpty()) {
            return param.trim();
        }
        return null;
    }

    private void writeUnauthorized(HttpServletResponse response, String msg) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        R<Void> body = R.unauthorized(msg);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private void writeForbidden(HttpServletResponse response, String msg) throws Exception {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        R<Void> body = R.forbidden(msg);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
