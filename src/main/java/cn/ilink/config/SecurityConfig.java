package cn.ilink.config;

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
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeRequests(authorize -> authorize
                .antMatchers("/", "/index.html", "/404.html", "/favicon.ico", "/favicon.svg",
                    "/api/login", "/api/register", "/login", "/login.html", "/register", "/register.html",
                    "/forgot-password.html", "/terms.html", "/privacy.html").permitAll()
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
                .antMatchers("/team-workspace.html").permitAll()

                .antMatchers("/api/user/**").authenticated()
                .antMatchers("/api/upload/**").authenticated()
                .antMatchers("/api/files/**").authenticated()
                .antMatchers("/home", "/home.html", "/profile.html", "/profile-edit.html", "/profile-honors.html",
                    "/profile-posts.html", "/profile-favorites.html", "/profile-article-edit.html", "/profile-asset-edit.html").authenticated()
                .antMatchers("/team-publish.html", "/team-detail.html").authenticated()
                .antMatchers("/teacher-detail.html").authenticated()
                .antMatchers("/asset-detail.html").authenticated()
                .antMatchers("/community-article.html").authenticated()
                .antMatchers("/admin.html", "/api/admin/**").hasRole("ADMIN")
                .anyRequest().permitAll()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/api/logout")
                .logoutSuccessUrl("/login")
                .permitAll()
            )
            .sessionManagement(session -> session
                .sessionFixation().migrateSession()
            )
            .headers(headers -> {
                headers.frameOptions().sameOrigin();
                headers.referrerPolicy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN);
                headers.addHeaderWriter((request, response) -> {
                    String path = request.getRequestURI();
                    response.setHeader("X-Content-Type-Options", "nosniff");
                    response.setHeader("X-Frame-Options", "SAMEORIGIN");
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
}
