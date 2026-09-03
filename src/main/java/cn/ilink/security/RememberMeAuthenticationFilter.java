package cn.ilink.security;

import cn.ilink.entity.User;
import cn.ilink.service.RememberMeService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.List;

public class RememberMeAuthenticationFilter extends OncePerRequestFilter {
    private final RememberMeService rememberMeService;

    public RememberMeAuthenticationFilter(RememberMeService rememberMeService) {
        this.rememberMeService = rememberMeService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/css/")
            || path.startsWith("/js/")
            || path.startsWith("/lib/")
            || path.startsWith("/img/")
            || path.startsWith("/uploads/")
            || path.startsWith("/actuator/")
            || path.startsWith("/ws/")
            || path.startsWith("/ws-native/")
            || "/favicon.ico".equals(path)
            || "/favicon.svg".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        boolean hasUser = session != null && session.getAttribute("user") instanceof User;
        if (!hasUser && SecurityContextHolder.getContext().getAuthentication() == null
                && !request.getRequestURI().startsWith("/api/login")
                && !request.getRequestURI().startsWith("/api/logout")) {
            User user = rememberMeService.authenticateAndRotate(request, response);
            if (user != null) {
                UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(user.getUsername(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
                request.getSession(true).setAttribute("user", user);
            }
        }
        filterChain.doFilter(request, response);
    }
}
