package com.blogsystem.app.it.trigger.http.music;

import com.blogsystem.trigger.http.music.MusicAdminController;
import com.blogsystem.trigger.http.music.MusicExceptionHandler;
import com.blogsystem.trigger.http.music.MusicPublicController;
import com.blogsystem.trigger.interceptor.SingleOwnerSessionInterceptor;

import com.blogsystem.infrastructure.security.identity.SaTokenOwnerSession;

import com.blogsystem.domain.identity.gateway.LoginProtection;
import com.blogsystem.domain.identity.gateway.OwnerCredentials;
import com.blogsystem.domain.identity.model.OwnerAccount;
import com.blogsystem.domain.identity.model.SingleOwnerPolicy;
import com.blogsystem.domain.music.model.MusicAccount;
import com.blogsystem.domain.music.model.MusicException;

import cn.dev33.satoken.SaManager;
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

import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.spring.SaTokenContextForSpringInJakartaServlet;
import com.blogsystem.application.identity.service.IdentityApplicationFacade;
import com.blogsystem.domain.identity.adapter.repository.OwnerAccountRepository;
import com.blogsystem.application.music.service.MusicApplicationService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MusicControllerTest {
    SaTokenConfig oldConfig;
    SaTokenDao oldDao;
    SaTokenContext oldContext;
    StpInterface oldInterface;
    StpLogic oldLogic;
    MusicApplicationService service;
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

            // A non-owner BLOG_OWNER account must still be rejected by SingleOwnerSessionInterceptor.
            public List<String> getRoleList(Object id, String type) {
                return List.of("BLOG_OWNER");
            }
        });
        StpUtil.setStpLogic(new StpLogic("login"));
        var accounts = mock(OwnerAccountRepository.class);
        when(accounts.findById(1L)).thenReturn(Optional.of(new OwnerAccount(1, "13900000000", "owner", null, null, null, "fixture", true)));
        when(accounts.findById(2L)).thenReturn(Optional.of(new OwnerAccount(2, "13800000000", "old-admin", null, null, null, "fixture", true)));
        var identity = new IdentityApplicationFacade(new SingleOwnerPolicy("13900000000"), accounts, mock(OwnerCredentials.class), new SaTokenOwnerSession(), mock(LoginProtection.class));
        service = mock(MusicApplicationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new MusicAdminController(service), new MusicPublicController(service))
                .setControllerAdvice(new MusicExceptionHandler())
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

    String token(int id) {
        String token = StpUtil.getStpLogic().createLoginSession(id);
        StpUtil.getTokenSessionByToken(token).set(SaTokenOwnerSession.PASSWORD_SESSION_MARKER, true);
        return token;
    }

    @Test
    void anonymousCannotCheckAuthorizeSyncOrRevoke() throws Exception {
        mvc.perform(get("/api/admin/music/status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/music/playlist")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/music/authorize")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/music/sync")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/admin/music/authorization")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/music/catalog")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/admin/music/tracks/fixture")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/admin/music/tracks/fixture").contentType("application/json")
                        .content("{\"title\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void oldAdminCannotAccessOwnerMusic() throws Exception {
        String old = token(2);
        mvc.perform(get("/api/admin/music/status").header("Authorization", old)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/music/authorize").header("Authorization", old)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/music/sync").header("Authorization", old)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/music/authorization").header("Authorization", old)).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void ownerCanCheckAndRevokeWithoutExposingSecretsOrCacheableQr() throws Exception {
        String owner = token(1);
        when(service.status()).thenReturn(new MusicApplicationService.Status(true, true, "939817038", null));
        mvc.perform(get("/api/admin/music/status").header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.data.authorized").value(true));
        when(service.authorize()).thenReturn(new MusicAccount.Authorization(false, "https://163cn.tv/fixture", Instant.parse("2026-10-03T00:00:00Z")));
        mvc.perform(post("/api/admin/music/authorize").header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.data.accessToken").doesNotExist());
        when(service.revoke()).thenReturn(new MusicAccount.Revocation(false, false));
        mvc.perform(delete("/api/admin/music/authorization").header("Authorization", owner)).andExpect(status().isOk());
        verify(service).revoke();
    }

    @Test
    void anonymousPublicReadHasOnlyPlaylistMetadataNotOwnerRights() throws Exception {
        when(service.publicPlaylist()).thenReturn(Optional.of(new MusicApplicationService.PublicPlaylist("939817038", "博客歌单", Instant.now(),
                List.of(new MusicApplicationService.PublicSong("1", "歌曲", "歌手", "")))));
        mvc.perform(get("/api/music/playlist")).andExpect(status().isOk()).andExpect(jsonPath("$.data.playlistId").value("939817038"))
                .andExpect(jsonPath("$.data.songs[0].ownerPlayable").doesNotExist()).andExpect(jsonPath("$.data.accessToken").doesNotExist())
                .andExpect(jsonPath("$.data.authorizationUrl").doesNotExist());
        verify(service).publicPlaylist();
        verifyNoMoreInteractions(service);
    }

    @Test
    void providerAuthExpiryDoesNotReturnBlog401AndErrorsAreSafe() throws Exception {
        String owner = token(1);
        when(service.synchronize()).thenThrow(new MusicException(MusicException.Reason.NOT_AUTHORIZED));
        mvc.perform(post("/api/admin/music/sync").header("Authorization", owner)).andExpect(status().isConflict());
        when(service.status()).thenThrow(new RuntimeException("accessToken=do-not-export"));
        mvc.perform(get("/api/admin/music/status").header("Authorization", owner)).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("音乐操作失败，请稍后检查状态"));
    }
}
