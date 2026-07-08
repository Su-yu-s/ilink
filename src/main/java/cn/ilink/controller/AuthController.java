package cn.ilink.controller;

import cn.ilink.common.Result;
import cn.ilink.dto.LoginRequest;
import cn.ilink.dto.RegisterRequest;
import cn.ilink.entity.User;
import cn.ilink.security.LoginAttemptService;
import cn.ilink.service.HomeStatsService;
import cn.ilink.service.UserService;
import cn.ilink.util.PasswordPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.savedrequest.DefaultSavedRequest;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Controller
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserService userService;
    private final LoginAttemptService loginAttemptService;
    private final HomeStatsService homeStatsService;

    /** 读取登录前访问的地址，登录成功后跳回原页面。 */
    private final RequestCache requestCache = new HttpSessionRequestCache();

    public AuthController(UserService userService, LoginAttemptService loginAttemptService,
                          @Autowired(required = false) HomeStatsService homeStatsService) {
        this.userService = userService;
        this.loginAttemptService = loginAttemptService;
        this.homeStatsService = homeStatsService;
    }

    @GetMapping("/login")
    public String loginPage(Model model, HttpSession session) {
        model.addAttribute("currentUser", session.getAttribute("user"));
        return "login";
    }

    @PostMapping("/api/login")
    @ResponseBody
    public ResponseEntity<Result<?>> login(
        @RequestBody LoginRequest loginRequest,
        HttpSession session,
        HttpServletRequest request,
        HttpServletResponse servletResponse
    ) {
        try {
            if (loginRequest == null) {
                loginRequest = new LoginRequest();
            }

            String clientKey = resolveLoginAttemptKey(request, loginRequest);
            if (loginAttemptService.isBlocked(clientKey)) {
                return json(Result.fail(401,
                    "登录尝试次数过多，请 " + loginAttemptService.remainingLockMinutes() + " 分钟后再试"));
            }

            normalizeIdentifier(loginRequest);

            String principal = firstNonBlank(
                loginRequest.getPhoneNumber(),
                loginRequest.getStudentId(),
                loginRequest.getIdentifier()
            );
            if (principal == null) {
                loginAttemptService.loginFailed(clientKey);
                return json(Result.fail(401, "用户名或密码错误"));
            }

            User user = userService.login(loginRequest);
            if (user == null) {
                loginAttemptService.loginFailed(clientKey);
                return json(Result.fail(401, "用户名或密码错误"));
            }

            Authentication authentication = new UsernamePasswordAuthenticationToken(
                user.getUsername(),
                null,
                buildAuthorities(user)
            );
            SecurityContextHolder.getContext().setAuthentication(authentication);

            String oldSessionId = session.getId();
            session.invalidate();
            session = request.getSession(true);
            session.setAttribute("originalSessionId", oldSessionId);

            user.setPassword(null);
            session.setAttribute("user", user);

            String redirectAfterLogin = resolveRedirectAfterLogin(request, servletResponse);
            loginAttemptService.loginSucceeded(clientKey);
            return json(Result.ok("登录成功", user).withExtra("redirectAfterLogin", redirectAfterLogin));
        } catch (Exception e) {
            log.warn("登录异常", e);
            return json(Result.fail("系统异常，请稍后重试"));
        }
    }

    @GetMapping("/register")
    public String registerPage(Model model, HttpSession session) {
        model.addAttribute("currentUser", session.getAttribute("user"));
        return "register";
    }

    @PostMapping("/api/register")
    @ResponseBody
    public ResponseEntity<Result<?>> register(@RequestBody RegisterRequest registerRequest,
                                              HttpServletRequest request) {
        String clientIp = request.getHeader("X-Forwarded-For");
        if (clientIp == null || clientIp.isBlank()) {
            clientIp = request.getRemoteAddr();
        }
        if (!loginAttemptService.tryRegister(clientIp)) {
            return Result.badRequest("注册过于频繁，请 1 分钟后再试").toResponseEntity();
        }

        String regPhone = registerRequest.getPhoneNumber();
        boolean phoneEmpty = regPhone == null || regPhone.trim().isEmpty();
        if (phoneEmpty
            && registerRequest.getStudentId() == null
            && registerRequest.getIdentifier() != null
            && !registerRequest.getIdentifier().trim().isEmpty()) {
            String id = registerRequest.getIdentifier().trim();
            if (id.matches("^1[3-9]\\d{9}$")) {
                registerRequest.setPhoneNumber(id);
            } else if (id.matches("^\\d{5,15}$")) {
                try {
                    registerRequest.setStudentId(Long.parseLong(id));
                } catch (NumberFormatException e) {
                    return Result.badRequest("学号/工号格式错误").toResponseEntity();
                }
            } else {
                return Result.badRequest("手机号或学号/工号格式不正确").toResponseEntity();
            }
        }

        String pwd = registerRequest.getPassword();
        if (!PasswordPolicy.isValid(pwd)) {
            return Result.badRequest(PasswordPolicy.message()).toResponseEntity();
        }

        if (registerRequest.getPhoneNumber() != null && !registerRequest.getPhoneNumber().trim().isEmpty()) {
            if (!registerRequest.getPhoneNumber().matches("^1[3-9]\\d{9}$")) {
                return Result.badRequest("手机号格式错误").toResponseEntity();
            }
        }

        if (registerRequest.getStudentId() != null) {
            if (!String.valueOf(registerRequest.getStudentId()).matches("^\\d{5,15}$")) {
                return Result.badRequest("学号/工号格式错误").toResponseEntity();
            }
        }

        if ((registerRequest.getPhoneNumber() == null || registerRequest.getPhoneNumber().trim().isEmpty())
            && registerRequest.getStudentId() == null) {
            return Result.badRequest("手机号或学号/工号必须填写一项").toResponseEntity();
        }

        // 验证角色/身份：注册时必须选择学生或教师
        String role = registerRequest.getRole();
        if (role == null || (!"STUDENT".equals(role) && !"TEACHER".equals(role))) {
            return Result.badRequest("请选择身份（学生或教师）").toResponseEntity();
        }
        registerRequest.setRole(role.toUpperCase());

        boolean success = userService.register(registerRequest);
        if (success) {
            return Result.ok("注册成功", null).toResponseEntity();
        }
        return Result.badRequest("注册失败，手机号或学号/工号已存在").toResponseEntity();
    }

    @GetMapping("/api/logout")
    public String logoutGet(HttpSession session, HttpServletResponse response) {
        session.invalidate();
        SecurityContextHolder.clearContext();
        return "redirect:/login.html";
    }

    @PostMapping("/api/logout")
    @ResponseBody
    public ResponseEntity<Result<?>> logoutPost(HttpSession session) {
        session.invalidate();
        SecurityContextHolder.clearContext();
        return Result.ok("登出成功", null).toResponseEntity();
    }

    @GetMapping("/")
    public String index(Model model, HttpSession session) {
        model.addAttribute("currentUser", session.getAttribute("user"));
        Map<String, Long> stats = resolveIndexStats();
        model.addAttribute("userCount", stats.get("userCount"));
        model.addAttribute("teamCount", stats.get("teamCount"));
        model.addAttribute("assetCount", stats.get("assetCount"));
        model.addAttribute("teacherCount", stats.get("teacherCount"));
        return "index";
    }

    @GetMapping("/home")
    public String home() {
        return "redirect:/profile.html";
    }

    @GetMapping("/error/404")
    public String notFound() {
        return "404";
    }

    private ResponseEntity<Result<?>> json(Result<?> result) {
        return ResponseEntity.ok()
            .header("Content-Type", "application/json;charset=UTF-8")
            .body(result);
    }

    private void normalizeIdentifier(LoginRequest loginRequest) {
        String identifier = loginRequest.getIdentifier();
        if (identifier == null || identifier.isBlank()) {
            return;
        }
        identifier = identifier.trim();
        loginRequest.setIdentifier(identifier);
        if (identifier.matches("^1[3-9]\\d{9}$")) {
            loginRequest.setPhoneNumber(identifier);
        } else if (identifier.matches("^\\d+$")) {
            loginRequest.setStudentId(identifier);
        }
    }

    private List<GrantedAuthority> buildAuthorities(User user) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + user.getRole()));
        switch (user.getRole()) {
            case "ADMIN":
                authorities.add(new SimpleGrantedAuthority("ACCESS_ADMIN_PANEL"));
                authorities.add(new SimpleGrantedAuthority("MANAGE_USERS"));
                break;
            case "TEACHER":
                authorities.add(new SimpleGrantedAuthority("ACCESS_TEACHER_FEATURES"));
                break;
            case "STUDENT":
                authorities.add(new SimpleGrantedAuthority("ACCESS_STUDENT_FEATURES"));
                break;
            default:
                break;
        }
        return authorities;
    }

    private String resolveRedirectAfterLogin(HttpServletRequest request, HttpServletResponse servletResponse) {
        String redirectAfterLogin = "/index.html";
        SavedRequest saved = requestCache.getRequest(request, servletResponse);
        if (saved == null) {
            return redirectAfterLogin;
        }
        try {
            if (saved instanceof DefaultSavedRequest) {
                DefaultSavedRequest dr = (DefaultSavedRequest) saved;
                String uri = dr.getRequestURI();
                if (uri != null && uri.startsWith("/") && !uri.startsWith("//")) {
                    String q = dr.getQueryString();
                    redirectAfterLogin = uri + (q != null && !q.isEmpty() ? "?" + q : "");
                }
            } else {
                String full = saved.getRedirectUrl();
                if (full != null && !full.isBlank()) {
                    java.net.URI u = java.net.URI.create(full);
                    String path = u.getPath();
                    String ctx = request.getContextPath();
                    if (ctx != null && !ctx.isEmpty() && path != null && path.startsWith(ctx)) {
                        path = path.substring(ctx.length());
                        if (path.isEmpty()) {
                            path = "/";
                        }
                    }
                    if (path != null && path.startsWith("/") && !path.startsWith("//")) {
                        String q = u.getRawQuery();
                        redirectAfterLogin = path + (q != null && !q.isEmpty() ? "?" + q : "");
                    }
                }
            }
        } catch (IllegalArgumentException ignored) {
            // 保持默认跳转。
        } finally {
            requestCache.removeRequest(request, servletResponse);
        }
        return redirectAfterLogin;
    }

    private static String resolveLoginAttemptKey(HttpServletRequest request, LoginRequest loginRequest) {
        String ip = request.getRemoteAddr();
        String account = loginRequest.getIdentifier();
        if (account == null || account.isBlank()) {
            account = loginRequest.getPhoneNumber();
        }
        if (account == null || account.isBlank()) {
            account = loginRequest.getStudentId();
        }
        if (account != null && !account.isBlank()) {
            return ip + ":" + account.trim();
        }
        return ip;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private Map<String, Long> resolveIndexStats() {
        if (homeStatsService != null) {
            return homeStatsService.resolveIndexStats();
        }
        return Map.of(
            "userCount", resolveUserCount(),
            "teamCount", 0L,
            "assetCount", 0L,
            "teacherCount", 0L
        );
    }

    private long resolveUserCount() {
        try {
            return userService.count();
        } catch (Exception e) {
            log.warn("获取首页用户数量失败", e);
            return 0L;
        }
    }
}
