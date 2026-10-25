package com.blogsystem.app.it.trigger.http.asset;

import com.blogsystem.trigger.http.asset.ImageController;
import com.blogsystem.trigger.http.asset.ImageExceptionHandler;
import com.blogsystem.trigger.interceptor.SingleOwnerSessionInterceptor;

import com.blogsystem.infrastructure.security.identity.SaTokenOwnerSession;

import com.blogsystem.domain.asset.image.model.query.ImagePage;
import com.blogsystem.domain.asset.image.model.valobj.ImageStorageException;
import com.blogsystem.domain.identity.gateway.LoginProtection;
import com.blogsystem.domain.identity.gateway.OwnerCredentials;

import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.spring.SaTokenContextForSpringInJakartaServlet;
import com.blogsystem.application.asset.image.service.ImageApplicationService;
import com.blogsystem.application.identity.service.IdentityApplicationFacade;
import com.blogsystem.domain.identity.model.OwnerAccount;
import com.blogsystem.domain.identity.model.SingleOwnerPolicy;
import com.blogsystem.domain.identity.adapter.repository.OwnerAccountRepository;

import java.util.Optional;

import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Actual Sa-Token annotations/interceptor with isolated in-memory sessions, no real users/Redis.
 */
class ImageControllerTest {
    SaTokenConfig oldConfig;
    SaTokenDao oldDao;
    SaTokenContext oldContext;
    StpInterface oldInterface;
    StpLogic oldLogic;
    ImageApplicationService service;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        oldConfig = SaManager.getConfig();
        oldDao = SaManager.getSaTokenDao();
        oldContext = SaManager.getSaTokenContext();
        oldInterface = SaManager.getStpInterface();
        oldLogic = StpUtil.getStpLogic();
        SaManager.setConfig(new SaTokenConfig().setTokenName("Authorization").setIsReadCookie(false));
        SaManager.setSaTokenDao(new SaTokenDaoDefaultImpl());
        SaManager.setSaTokenContext(new SaTokenContextForSpringInJakartaServlet());
        SaManager.setStpInterface(new StpInterface() {
            public List<String> getPermissionList(Object id, String type) {
                return List.of();
            }

            public List<String> getRoleList(Object id, String type) {
                return "1".equals(id.toString()) ? List.of("BLOG_OWNER") : List.of("VISITOR");
            }
        });
        StpUtil.setStpLogic(new StpLogic("login"));
        var accounts = mock(OwnerAccountRepository.class);
        when(accounts.findById(1L)).thenReturn(Optional.of(new OwnerAccount(1, "13900000000", "owner", null, null, null, "fixture", true)));
        when(accounts.findById(2L)).thenReturn(Optional.of(new OwnerAccount(2, "13800000000", "visitor", null, null, null, "fixture", true)));
        var identity = new IdentityApplicationFacade(new SingleOwnerPolicy("13900000000"), accounts, mock(OwnerCredentials.class), new SaTokenOwnerSession(), mock(LoginProtection.class));
        service = mock(ImageApplicationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ImageController(service))
                .setControllerAdvice(new ImageExceptionHandler()).addInterceptors(new SingleOwnerSessionInterceptor(identity), new SaInterceptor()).build();
    }

    @AfterEach
    void restore() {
        SaManager.setConfig(oldConfig);
        SaManager.setSaTokenDao(oldDao);
        SaManager.setSaTokenContext(oldContext);
        SaManager.setStpInterface(oldInterface);
        StpUtil.setStpLogic(oldLogic);
    }

    String token(int user) {
        String token = StpUtil.getStpLogic().createLoginSession(user);
        if (user == 1) StpUtil.getTokenSessionByToken(token).set(SaTokenOwnerSession.PASSWORD_SESSION_MARKER, true);
        return token;
    }

    MockMultipartFile png() {
        return new MockMultipartFile("file", "a.png", "image/png", new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
    }

    @Test
    void anonymousCannotUploadListOrDelete() throws Exception {
        mvc.perform(get("/api/images")).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/images/avatar").file(png())).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/images").contentType("application/json").content("{\"path\":\"blog/a.png\"}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void formerVisitorCannotUploadAvatarOrManageImages() throws Exception {
        String user = token(2);
        mvc.perform(multipart("/api/images/avatar").file(png()).header("Authorization", user)).andExpect(status().isForbidden());
        mvc.perform(get("/api/images").header("Authorization", user)).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/images/article").file(png()).header("Authorization", user)).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/images/cover").file(png()).header("Authorization", user)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/images").header("Authorization", user).contentType("application/json").content("{\"path\":\"blog/a.png\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void adminGetsAllowlistedResultsAndCanDeleteExactlyOneImage() throws Exception {
        String admin = token(1);
        when(service.list(0, 20, "")).thenReturn(new ImagePage(List.of(), 0));
        mvc.perform(get("/api/images").header("Authorization", admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(delete("/api/images").header("Authorization", admin).contentType("application/json").content("{\"path\":\"blog/covers/a.png\"}")).andExpect(status().isOk());
        verify(service).delete("blog/covers/a.png");
    }

    @Test
    void invalidUploadsAndProviderFailuresUseSafeHttpStatuses() throws Exception {
        String admin = token(1);
        mvc.perform(multipart("/api/images/article").file(new MockMultipartFile("file", "a.svg", "image/svg+xml", "<svg/>".getBytes())).header("Authorization", admin)).andExpect(status().isBadRequest());
        when(service.list(0, 20, "")).thenThrow(new ImageStorageException(ImageStorageException.Reason.NOT_CONFIGURED));
        mvc.perform(get("/api/images").header("Authorization", admin)).andExpect(status().isServiceUnavailable());
        doThrow(new ImageStorageException(ImageStorageException.Reason.UNKNOWN_OUTCOME)).when(service).list(0, 20, "");
        mvc.perform(get("/api/images").header("Authorization", admin)).andExpect(status().isGatewayTimeout());
    }

    @Test
    void missingMultipartAndMalformedQueriesAreBadRequestsNotInternalErrors() throws Exception {
        String admin = token(1);
        mvc.perform(multipart("/api/images/article").header("Authorization", admin)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/images?offset=not-a-number").header("Authorization", admin)).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/images").header("Authorization", admin).contentType("application/json").content("broken-json")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
