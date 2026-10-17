package cn.ilink.controller;

import cn.ilink.common.Result;
import cn.ilink.dto.PublicUserProfileVO;
import cn.ilink.entity.Asset;
import cn.ilink.entity.CommunityPost;
import cn.ilink.entity.User;
import cn.ilink.mapper.AssetMapper;
import cn.ilink.mapper.CommunityPostMapper;
import cn.ilink.service.UserService;
import cn.ilink.service.AccountDeletionService;
import cn.ilink.util.I18nMessageResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

class UserControllerI18nTest {

    @AfterEach
    void clearContext() {
        LocaleContextHolder.resetLocaleContext();
        SecurityContextHolder.clearContext();
    }

    @Test
    void accountDeletionPasswordErrorUsesSelectedEnglishLocale() {
        UserService userService = mock(UserService.class);
        User currentUser = new User();
        currentUser.setId(17L);
        currentUser.setRole("STUDENT");
        when(userService.checkPassword(17L, "wrong-password")).thenReturn(false);

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user", currentUser);
        UserController controller = new UserController();
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "i18nMessages", new I18nMessageResolver(messageSource()));
        LocaleContextHolder.setLocale(Locale.ENGLISH);

        Result<?> result = controller.deleteAccount(
            Map.of("password", "wrong-password", "confirmText", "注销"), session).getBody();

        assertEquals(400, result.getCode());
        assertEquals("The password is incorrect", result.getMessage());
    }

    @Test
    void publicProfileDoesNotExposePhoneEmailOrInternalUsername() {
        UserService userService = mock(UserService.class);
        CommunityPostMapper communityPostMapper = mock(CommunityPostMapper.class);
        AssetMapper assetMapper = mock(AssetMapper.class);
        User user = new User();
        user.setId(27L);
        user.setUsername("18954061592");
        user.setPhoneNumber("18954061592");
        user.setEmail("student@example.edu");
        user.setRole("STUDENT");
        when(userService.getById(27L)).thenReturn(user);
        given(communityPostMapper.selectPage(any(Page.class), any()))
            .willReturn(new Page<CommunityPost>(1, 20));
        given(assetMapper.selectPage(any(Page.class), any()))
            .willReturn(new Page<Asset>(1, 20));

        UserController controller = new UserController();
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "communityPostMapper", communityPostMapper);
        ReflectionTestUtils.setField(controller, "assetMapper", assetMapper);

        Result<?> response = controller.getPublicProfile(27L).getBody();

        assertEquals(200, response.getCode());
        PublicUserProfileVO publicProfile = (PublicUserProfileVO) response.getData();
        assertEquals("同学", publicProfile.getDisplayName());
        String responseJson;
        try {
            responseJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(publicProfile);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError("public profile should serialize", e);
        }
        assertFalse(responseJson.contains("18954061592"));
        assertFalse(responseJson.contains("student@example.edu"));
        assertFalse(responseJson.contains("username"));
    }

    @Test
    void accountDeletionRequiresExactConfirmationAndKeepsSessionOnRejectedRequest() {
        UserService userService = mock(UserService.class);
        AccountDeletionService deletionService = mock(AccountDeletionService.class);
        User currentUser = new User();
        currentUser.setId(17L);
        currentUser.setRole("STUDENT");
        when(userService.checkPassword(17L, "correct-password")).thenReturn(true);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user", currentUser);
        UserController controller = new UserController();
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "accountDeletionService", deletionService);

        Result<?> result = controller.deleteAccount(
            Map.of("password", "correct-password", "confirmText", "注销账号"), session).getBody();

        assertEquals(400, result.getCode());
        verify(deletionService, never()).findBlockingTeams(17L);
        verify(deletionService, never()).deleteAccount(17L);
        assertFalse(session.isInvalid());
    }

    @Test
    void accountDeletionBlockedTeamsAreReportedWithoutDeletingAccount() {
        UserService userService = mock(UserService.class);
        AccountDeletionService deletionService = mock(AccountDeletionService.class);
        User currentUser = new User();
        currentUser.setId(17L);
        currentUser.setRole("STUDENT");
        when(userService.checkPassword(17L, "correct-password")).thenReturn(true);
        when(deletionService.findBlockingTeams(17L)).thenReturn(java.util.List.of("星火队"));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user", currentUser);
        UserController controller = new UserController();
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "accountDeletionService", deletionService);
        ReflectionTestUtils.setField(controller, "i18nMessages", new I18nMessageResolver(messageSource()));
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);

        Result<?> result = controller.deleteAccount(
            Map.of("password", "correct-password", "confirmText", "注销"), session).getBody();

        assertEquals(409, result.getCode());
        assertEquals("你仍是以下团队的创建者：星火队。请先转让或解散团队后再注销账号。", result.getMessage());
        verify(deletionService, never()).deleteAccount(17L);
        assertFalse(session.isInvalid());
    }

    @Test
    void accountDeletionPurgesAccountAndInvalidatesCurrentSessionAfterConfirmation() {
        UserService userService = mock(UserService.class);
        AccountDeletionService deletionService = mock(AccountDeletionService.class);
        User currentUser = new User();
        currentUser.setId(17L);
        currentUser.setRole("STUDENT");
        when(userService.checkPassword(17L, "correct-password")).thenReturn(true);
        when(deletionService.findBlockingTeams(17L)).thenReturn(java.util.List.of());
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user", currentUser);
        UserController controller = new UserController();
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "accountDeletionService", deletionService);

        Result<?> result = controller.deleteAccount(
            Map.of("password", "correct-password", "confirmText", "注销"), session).getBody();

        assertEquals(200, result.getCode());
        assertEquals("账号已注销", result.getMessage());
        verify(deletionService).deleteAccount(17L);
        assertTrue(session.isInvalid());
    }

    private static ResourceBundleMessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }
}
