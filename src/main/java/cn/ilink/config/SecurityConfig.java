package cn.ilink.config;

import cn.ilink.security.RememberMeAuthenticationFilter;
import cn.ilink.service.RememberMeService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

import javax.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           ObjectProvider<RememberMeService> rememberMeServiceProvider)
            throws Exception {
        http
            .authorizeRequests(authorize -> authorize
                .antMatchers("/", "/index.html", "/404.html", "/favicon.ico", "/favicon.svg",
                    "/error", "/error/**",
                    "/api/login", "/api/register", "/api/logout", "/login", "/login.html", "/register", "/register.html",
                    "/forgot-password.html", "/api/password-reset/**", "/terms.html", "/privacy.html").permitAll()
                .antMatchers("/actuator/health", "/actuator/info").permitAll()
                .antMatchers("/css/**", "/js/**", "/lib/**", "/img/**", "/uploads/**").permitAll()

                .antMatchers(HttpMethod.GET, "/api/community/posts").permitAll()
                .antMatchers(HttpMethod.GET, "/api/community/posts/*").permitAll()
                .antMatchers(HttpMethod.GET, "/api/community/posts/*/comments").permitAll()
                .antMatchers("/api/community/**").authenticated()
                .antMatchers(HttpMethod.GET, "/api/teacher/list").permitAll()
                .antMatchers(HttpMethod.GET, "/api/teacher/*").permitAll()
                .antMatchers("/api/teacher/**").authenticated()
                .antMatchers(HttpMethod.GET, "/api/team/list").permitAll()
                .antMatchers(HttpMethod.GET, "/api/team/*").permitAll()
                .antMatchers("/api/team/**").authenticated()
                .antMatchers(HttpMethod.GET, "/api/asset/list").permitAll()
                .antMatchers(HttpMethod.GET, "/api/asset/*").permitAll()
                .antMatchers("/api/asset/**").authenticated()
                .antMatchers(HttpMethod.GET, "/api/competitions").permitAll()
                .antMatchers("/api/user/public/**").permitAll()
                .antMatchers("/api/user/skills/public/**").permitAll()

                .antMatchers("/index.html").permitAll()
                .antMatchers("/competitions.html").permitAll()
                .antMatchers("/community.html").permitAll()
                .antMatchers("/community/article/**").permitAll()
                .antMatchers("/teacher-wall.html").permitAll()
                .antMatchers("/team-market.html").permitAll()
                .antMatchers("/gallery.html").permitAll()
                .antMatchers("/user-profile.html").permitAll()
                .antMatchers("/team-detail.html", "/teacher-detail.html", "/asset-detail.html",
                    "/community-article.html").permitAll()
                .antMatchers("/team-workspace.html").permitAll()

                .antMatchers("/api/user/**").authenticated()
                .antMatchers("/api/upload/**").authenticated()
                .antMatchers("/api/files/**").authenticated()
                .antMatchers("/home", "/home.html", "/profile.html", "/profile-edit.html", "/profile-honors.html",
                    "/profile-posts.html", "/profile-favorites.html", "/profile-article-edit.html", "/profile-asset-edit.html",
                    "/profile-password.html").authenticated()
                .antMatchers("/team-publish.html").authenticated()
                .antMatchers("/admin.html", "/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .permitAll()
            )
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, exception) -> {
                    if (isApiRequest(request)) {
                        writeJsonError(response, 401, "未登录或登录已过期");
                        return;
                    }
                    String target = request.getRequestURI();
                    if (request.getQueryString() != null && !request.getQueryString().isBlank()) {
                        target += "?" + request.getQueryString();
                    }
                    response.sendRedirect(request.getContextPath() + "/login.html?redirect="
                        + URLEncoder.encode(target, StandardCharsets.UTF_8));
                })
                .accessDeniedHandler((request, response, exception) -> {
                    if (isApiRequest(request)) {
                        writeJsonError(response, 403, "无权执行此操作");
                    } else {
                        response.sendError(403);
                    }
                })
            )
            .sessionManagement(session -> session
                .sessionFixation().migrateSession()
            )
            .headers(headers -> {
                headers.frameOptions().sameOrigin();
                headers.referrerPolicy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN);
                headers.contentSecurityPolicy(
                    "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'self'; " +
                    "form-action 'self'; script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; " +
                    "style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net https://cdnjs.cloudflare.com; " +
                    "img-src 'self' data: blob: https:; font-src 'self' data: https://cdnjs.cloudflare.com; " +
                    "connect-src 'self' ws: wss:; worker-src 'self' blob:"
                );
                headers.addHeaderWriter((request, response) -> {
                    String path = request.getRequestURI();
                    response.setHeader("X-Content-Type-Options", "nosniff");
                    response.setHeader("X-Frame-Options", "SAMEORIGIN");
                    response.setHeader("Permissions-Policy",
                        "camera=(), microphone=(), geolocation=(), payment=(), usb=()");
                    if (isFaviconRequest(path) || isStaticResource(path)) {
                        response.setHeader("Cache-Control", "public, max-age=31536000, immutable");
                        response.setHeader("Pragma", "");
                        response.setHeader("Expires", "Tue, 22 Jun 2038 00:00:00 GMT");
                    } else {
                        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
                        response.setHeader("Pragma", "no-cache");
                    }
                });
            })
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .ignoringAntMatchers("/ws/**", "/ws-native/**", "/api/upload/**")
            );

        RememberMeService rememberMeService = rememberMeServiceProvider.getIfAvailable();
        if (rememberMeService != null) {
            http.addFilterBefore(new RememberMeAuthenticationFilter(rememberMeService),
                UsernamePasswordAuthenticationFilter.class);
        }

        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private boolean isFaviconRequest(String path) {
        return "/favicon.svg".equals(path)
            || "/favicon.ico".equals(path)
            || "/uploads/images/favicon.svg".equals(path)
            || "/uploads/images/favicon.png".equals(path);
    }

    /** 静态资源路径：上传文件和 classpath 静态资源，允许浏览器长期缓存 */
    private boolean isStaticResource(String path) {
        return path.startsWith("/uploads/")
            || path.startsWith("/css/")
            || path.startsWith("/js/")
            || path.startsWith("/lib/")
            || path.startsWith("/img/");
    }

    private boolean isApiRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path != null && path.startsWith(request.getContextPath() + "/api/");
    }

    private void writeJsonError(javax.servlet.http.HttpServletResponse response, int status, String message)
            throws java.io.IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + status + ",\"message\":\"" + message + "\"}");
    }
}
