package com.aigateway.interceptor;

import com.aigateway.common.UserContext;
import com.aigateway.controller.GiftCodeController;
import com.aigateway.controller.RateLimitController;
import com.aigateway.entity.User;
import com.aigateway.mapper.UserMapper;
import com.aigateway.util.JwtUtil;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证真实控制器方法的权限声明，以及角色变更后的即时拒绝。 */
class LoginInterceptorTest {
    private final JwtUtil jwt = mock(JwtUtil.class);
    private final UserMapper users = mock(UserMapper.class);
    private final LoginInterceptor interceptor = new LoginInterceptor(jwt, users);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void cleanContext() {
        UserContext.clear();
    }

    private void login() {
        request.addHeader("Authorization", "Bearer valid-token");
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("7");
        when(jwt.parseToken("valid-token")).thenReturn(claims);
    }

    private HandlerMethod importHandler() throws Exception {
        return new HandlerMethod(mock(GiftCodeController.class),
                GiftCodeController.class.getMethod("importCodes", Map.class));
    }

    @Test
    void anonymousCannotImport() throws Exception {
        assertFalse(interceptor.preHandle(request, response, importHandler()));
        assertEquals(401, response.getStatus());
        verifyNoInteractions(users);
    }

    @Test
    void ordinaryMissingDeletedAndUnknownRoleCannotImport() throws Exception {
        login();
        User ordinary = new User(); ordinary.setRole(0);
        User unknown = new User();
        User deleted = new User(); deleted.setRole(1); deleted.setDeleted(1);
        when(users.selectById(7L)).thenReturn(ordinary, null, unknown, deleted);
        for (int i = 0; i < 4; i++) {
            MockHttpServletResponse denied = new MockHttpServletResponse();
            assertFalse(interceptor.preHandle(request, denied, importHandler()));
            assertEquals(403, denied.getStatus());
            assertNull(UserContext.getUserId());
        }
    }

    @Test
    void administratorAllowedThenDemotionTakesEffectWithSameToken() throws Exception {
        login();
        User admin = new User(); admin.setRole(1);
        User ordinary = new User(); ordinary.setRole(0);
        when(users.selectById(7L)).thenReturn(admin, ordinary);
        HandlerMethod handler = importHandler();
        assertTrue(interceptor.preHandle(request, response, handler));
        assertEquals(7L, UserContext.getUserId());
        interceptor.afterCompletion(request, response, handler, null);
        assertNull(UserContext.getUserId());
        assertFalse(interceptor.preHandle(request, response, handler));
        assertEquals(403, response.getStatus());
    }

    @Test
    void limitUpdateRequiresAdminButLimitReadAndSeizeRemainAccessible() throws Exception {
        login();
        User ordinary = new User(); ordinary.setRole(0);
        when(users.selectById(7L)).thenReturn(ordinary);
        HandlerMethod update = new HandlerMethod(mock(RateLimitController.class),
                RateLimitController.class.getMethod("update", com.aigateway.dto.RateLimitUpdateRequest.class));
        assertFalse(interceptor.preHandle(request, response, update));
        assertEquals(403, response.getStatus());
        HandlerMethod read = new HandlerMethod(mock(RateLimitController.class),
                RateLimitController.class.getMethod("current"));
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), read));
        interceptor.afterCompletion(request, response, read, null);
        HandlerMethod seize = new HandlerMethod(mock(GiftCodeController.class),
                GiftCodeController.class.getMethod("seize"));
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), seize));
        verify(users, times(1)).selectById(7L);
    }
}
