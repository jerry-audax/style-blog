package com.blogsystem.app.it.trigger.http.identity;

import com.blogsystem.trigger.http.asset.ImageController;
import com.blogsystem.trigger.http.asset.ImageExceptionHandler;
import com.blogsystem.trigger.http.identity.AuthController;
import com.blogsystem.trigger.interceptor.SingleOwnerSessionInterceptor;

import com.blogsystem.infrastructure.security.identity.SaTokenOwnerSession;

import com.blogsystem.application.identity.service.IdentityApplicationFacade;

import com.blogsystem.domain.identity.gateway.LoginProtection;
import com.blogsystem.domain.identity.gateway.OwnerCredentials;
import com.blogsystem.domain.identity.model.OwnerAccount;
import com.blogsystem.domain.identity.model.SingleOwnerPolicy;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.spring.SaTokenContextForSpringInJakartaServlet;
import com.blogsystem.domain.identity.adapter.repository.OwnerAccountRepository;
import com.blogsystem.application.administration.service.AdministrationApplicationFacade;
import com.blogsystem.trigger.http.administration.AdminController;
import com.blogsystem.application.asset.image.service.ImageApplicationService;
import com.blogsystem.domain.asset.image.model.query.ImagePage;
import com.blogsystem.trigger.http.support.GlobalExceptionHandler;
import com.blogsystem.application.content.service.ContentApplicationFacade;
import com.blogsystem.trigger.http.content.ContentController;
import com.blogsystem.trigger.http.content.SitePublicationController;
import com.blogsystem.trigger.http.content.SitePublicationExceptionHandler;
import com.blogsystem.application.content.service.SitePublicationApplicationService;
import com.blogsystem.domain.content.gateway.SitePublicationGateway;
import com.blogsystem.domain.content.query.SitePublicationView;
import com.blogsystem.types.model.PageResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;


import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OwnerSessionHttpTest {
    @Test
    void publicAndOwnerContractsRemainStableAfterModuleSplit() throws Exception {
        org.junit.jupiter.api.Assertions.assertEquals("com.blogsystem.trigger.http.content", ContentController.class.getPackageName());
        mvc.perform(get("/api/content/article/list"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.records").isArray());
        mvc.perform(get("/api/admin/dashboard")).andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousAdminAccessKeepsHttp401() throws Exception {
        mvc.perform(get("/api/admin/dashboard")).andExpect(status().isUnauthorized());
    }
    SaTokenConfig oldConfig;
    SaTokenDao oldDao;
    SaTokenContext oldContext;
    StpInterface oldInterface;
    StpLogic oldLogic;
    MockMvc mvc;
    ImageApplicationService images;
    AdministrationApplicationFacade admin;
    ContentApplicationFacade content;
    SitePublicationGateway publicationRepository;

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
        // Both accounts are BLOG_OWNER to prove that role membership cannot bypass the owner invariant.
        SaManager.setStpInterface(new StpInterface() {
            public List<String> getRoleList(Object id, String type) {
                return List.of("BLOG_OWNER");
            }

            public List<String> getPermissionList(Object id, String type) {
                return List.of("admin:user:list", "admin:log:list", "content:article:write");
            }
        });
        StpUtil.setStpLogic(new StpLogic("login"));
        var accounts = mock(OwnerAccountRepository.class);
        when(accounts.findById(42)).thenReturn(Optional.of(new OwnerAccount(42, "13900000000", "owner", "博主", null, null, "hash", true)));
        when(accounts.findById(1)).thenReturn(Optional.of(new OwnerAccount(1, "13800000000", "former-admin", null, null, null, "hash", true)));
        var identity = new IdentityApplicationFacade(new SingleOwnerPolicy("13900000000"), accounts, mock(OwnerCredentials.class), new SaTokenOwnerSession(), mock(LoginProtection.class));
        images = mock(ImageApplicationService.class);
        admin = mock(AdministrationApplicationFacade.class);
        content = mock(ContentApplicationFacade.class);
        when(content.listArticles(1, null, null, 1, 10)).thenReturn(new PageResult<>(List.of(), 1, 10, 0));
        when(images.list(0, 20, "")).thenReturn(new ImagePage(List.of(), 0));
        when(admin.dashboard()).thenReturn(new com.blogsystem.domain.administration.query.DashboardSummary(0, 0, 0, 0, 0, List.of()));
        when(admin.logs(null, 1L, 10L)).thenReturn(new com.blogsystem.types.model.PageResult<>(List.of(), 1, 10, 0));
        publicationRepository = mock(SitePublicationGateway.class);
        when(publicationRepository.status()).thenReturn(new SitePublicationView(true, "CURRENT", null, null, null, null, null));
        when(publicationRepository.retry()).thenReturn(new SitePublicationView(true, "WAITING", null, null, null, null, null));
        var publication = new SitePublicationApplicationService(publicationRepository);
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(identity), new AdminController(admin), new ImageController(images), new ContentController(content), new SitePublicationController(publication))
                .setControllerAdvice(new ImageExceptionHandler(), new GlobalExceptionHandler(), new SitePublicationExceptionHandler())
                .addInterceptors(new SingleOwnerSessionInterceptor(identity), new SaInterceptor()).build();
    }

    @AfterEach
    void restore() {
        SaManager.setConfig(oldConfig);
        SaManager.setSaTokenDao(oldDao);
        SaManager.setSaTokenContext(oldContext);
        SaManager.setStpInterface(oldInterface);
        StpUtil.setStpLogic(oldLogic);
    }

    @Test
    void anonymousReadsRemainPublicAndPaginationIsCompatibleButWritesRequireLogin() throws Exception {
        mvc.perform(get("/api/content/article/list").param("status", "0"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.current").value(1))
                .andExpect(jsonPath("$.data.size").value(10)).andExpect(jsonPath("$.data.pages").value(0))
                .andExpect(jsonPath("$.data.records").isArray());
        verify(content).listArticles(1, null, null, 1, 10);
        mvc.perform(post("/api/content/article").contentType("application/json")
                        .content("{\"title\":\"new\",\"contentMd\":\"body\",\"status\":1}"))
                .andExpect(status().isUnauthorized());
        verify(content, never()).saveArticle(any());
    }

    @Test
    void oldAdminCannotWriteAndOwnerRequestMapsToPureCommand() throws Exception {
        String oldToken = StpUtil.getStpLogic().createLoginSession(1);
        String body = "{\"title\":\"new\",\"contentMd\":\"<p>body</p>\",\"status\":1}";
        mvc.perform(post("/api/content/article").header("Authorization", oldToken)
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        verify(content, never()).saveArticle(any());
        when(content.saveArticle(any())).thenReturn(7L);
        String token = StpUtil.getStpLogic().createLoginSession(42);
        StpUtil.getTokenSessionByToken(token).set(SaTokenOwnerSession.PASSWORD_SESSION_MARKER, true);
        mvc.perform(post("/api/content/article").header("Authorization", token)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(7));
        verify(content).saveArticle(argThat(command -> command.revision().body().format()
                == com.blogsystem.domain.content.model.ContentFormat.HTML));
    }

    @Test
    void domainValidationReturns400InsteadOfGeneric500() throws Exception {
        String token = StpUtil.getStpLogic().createLoginSession(42);
        StpUtil.getTokenSessionByToken(token).set(SaTokenOwnerSession.PASSWORD_SESSION_MARKER, true);
        mvc.perform(post("/api/content/article").header("Authorization", token).contentType("application/json")
                        .content("{\"title\":\"new\",\"contentMd\":\"body\",\"status\":1,\"isTop\":9}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        verify(content, never()).saveArticle(any());
    }

    @Test
    void anonymousMeLogsAndImagesUseActual401() throws Exception {
        for (String path : List.of("/api/auth/me", "/api/admin/logs", "/api/images"))
            mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(admin, images);
    }

    @Test
    void formerAdminIsDeniedEverywhereIncludingAvatarAndMe() throws Exception {
        String token = StpUtil.getStpLogic().createLoginSession(1);
        for (String path : List.of("/api/auth/me", "/api/admin/dashboard", "/api/admin/logs", "/api/images"))
            mvc.perform(get(path).header("Authorization", token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/images").param("Authorization", token)).andExpect(status().isForbidden());
        verifyNoInteractions(admin, images);
    }

    @Test
    void ownerCanReadAccountLogsAndImageList() throws Exception {
        String token = StpUtil.getStpLogic().createLoginSession(42);
        StpUtil.getTokenSessionByToken(token).set(SaTokenOwnerSession.PASSWORD_SESSION_MARKER, true);
        mvc.perform(get("/api/auth/me").header("Authorization", token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.owner").value(true));
        mvc.perform(get("/api/admin/logs").header("Authorization", token)).andExpect(status().isOk());
        mvc.perform(get("/api/images").header("Authorization", token)).andExpect(status().isOk());
        verify(images).list(0, 20, "");
        verify(admin).logs(null, 1L, 10L);
    }

    @Test
    void expiredTokenUsesActual401() throws Exception {
        mvc.perform(get("/api/auth/me").header("Authorization", "invalid-fixture")).andExpect(status().isUnauthorized());
    }

    @Test
    void legacyOwnerSessionIsDeniedUntilPasswordAuthentication() throws Exception {
        String token = StpUtil.getStpLogic().createLoginSession(42);
        mvc.perform(get("/api/auth/me").header("Authorization", token)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/images").header("Authorization", token)).andExpect(status().isUnauthorized());
        verifyNoInteractions(images, admin);
    }

    @Test
    void publicationControlsRejectAnonymousAndOldAdminButAllowPasswordOwner() throws Exception {
        mvc.perform(get("/api/admin/publication/status")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/publication/retry")).andExpect(status().isUnauthorized());
        String oldToken = StpUtil.getStpLogic().createLoginSession(1);
        mvc.perform(post("/api/admin/publication/retry").header("Authorization", oldToken)).andExpect(status().isForbidden());
        String token = StpUtil.getStpLogic().createLoginSession(42);
        StpUtil.getTokenSessionByToken(token).set(SaTokenOwnerSession.PASSWORD_SESSION_MARKER, true);
        mvc.perform(get("/api/admin/publication/status").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.phase").value("CURRENT"));
        mvc.perform(post("/api/admin/publication/retry").header("Authorization", token))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.phase").value("WAITING"));
    }

    @Test
    void unavailablePublisherReturns503InsteadOfGlobal500() throws Exception {
        when(publicationRepository.retry()).thenThrow(new com.blogsystem.domain.content.model.SitePublicationUnavailable());
        String token = StpUtil.getStpLogic().createLoginSession(42);
        StpUtil.getTokenSessionByToken(token).set(SaTokenOwnerSession.PASSWORD_SESSION_MARKER, true);
        mvc.perform(post("/api/admin/publication/retry").header("Authorization", token))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(503));
    }
}
