package com.blogsystem.it.media;

import cn.dev33.satoken.stp.StpUtil;
import com.blogsystem.app.it.trigger.http.support.CatalogMvcTestSupport;
import com.blogsystem.application.content.service.ArticleCommandService;
import com.blogsystem.application.content.service.ContentApplicationFacade;
import com.blogsystem.application.identity.service.IdentityApplicationFacade;
import com.blogsystem.domain.content.adapter.repository.ArticleEngagementRepository;
import com.blogsystem.domain.content.adapter.repository.ContentQueryRepository;
import com.blogsystem.domain.content.adapter.repository.TaxonomyRepository;
import com.blogsystem.domain.identity.gateway.LoginProtection;
import com.blogsystem.domain.identity.gateway.OwnerCredentials;
import com.blogsystem.domain.identity.model.SingleOwnerPolicy;
import com.blogsystem.infrastructure.adapter.repository.content.MybatisArticleRepository;
import com.blogsystem.infrastructure.adapter.repository.identity.MybatisOwnerAccountRepository;
import com.blogsystem.infrastructure.security.identity.SaTokenOwnerSession;
import com.blogsystem.trigger.http.content.ContentController;
import com.blogsystem.trigger.http.identity.AuthController;
import com.blogsystem.trigger.http.support.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real HTTP controllers, transaction proxies and owned PostgreSQL rows; no running server/provider. */
class MediaReferenceWriteApiIntegrationTest {
    private static final String URL = "/file/blog/images/api.png";
    private static final UUID ASSET = UUID.fromString("00000000-0000-0000-0000-000000000861");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CONFLICT = "资源引用已变化或尚未登记，请先核对资源后重试";
    private static final String UNAVAILABLE = "资源引用保护暂不可用，请稍后重试";

    @ParameterizedTest @ValueSource(strings = {"```html\n<img\n```", "示例 `<img` 只显示代码", "# 示例\n\n    <img\n"})
    void markdownCodeExamplesCanBePublishedWithoutPhantomAssets(String markdown) throws Exception {
        try (var f = new ArticleMediaWriteFixture(); var auth = new CatalogMvcTestSupport();
             var context = transactions(f.base.dataSource)) {
            var mvc = articles(f, auth, context);
            long id = savedId(mvc, article(auth.token(1), null, null, 1, markdown));
            String html = f.base.jdbc.queryForObject("SELECT content_html FROM article WHERE id=?", String.class, id);
            assertTrue(html.contains("&lt;img"));
            assertTrue(org.jsoup.Jsoup.parse(html).select("img").isEmpty());
            assertEquals(0, f.base.trace.containing("from media_asset"));
        }
    }

    @Test void unclosedMediaMarkupOutsideCodeStillReturns409() throws Exception {
        try (var f = new ArticleMediaWriteFixture(); var auth = new CatalogMvcTestSupport();
             var context = transactions(f.base.dataSource)) {
            var mvc = articles(f, auth, context);
            mvc.perform(article(auth.token(1), null, null, 0, "# 正文\n\n<img src='/file/blog/images/api.png'"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));
            assertEquals(0, f.base.jdbc.queryForObject("SELECT count(*) FROM article", Integer.class));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"UNKNOWN", "DELETE_PENDING", "ACTIVE"})
    void indentedHtmlRequestUsesActualHtmlProtection(String state) throws Exception {
        try (var f = new ArticleMediaWriteFixture(); var auth = new CatalogMvcTestSupport();
             var context = transactions(f.base.dataSource)) {
            var mvc = articles(f, auth, context);
            if (!state.equals("UNKNOWN")) {
                f.base.insert(ASSET, "blog/images/api.png");
                f.base.jdbc.update("UPDATE media_asset SET status=? WHERE id=?", state, ASSET);
            }
            String html = "    <img src='" + URL + "'>";
            var request = article(auth.token(1), null, null, 1, html);
            f.base.trace.statements.clear();
            if (!state.equals("ACTIVE")) {
                mvc.perform(request).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));
                assertEquals(0, f.base.jdbc.queryForObject("SELECT count(*) FROM article", Integer.class));
            } else {
                long id = savedId(mvc, request);
                assertEquals(html, f.base.jdbc.queryForObject("SELECT content_html FROM article WHERE id=?", String.class, id));
                assertTrue(f.base.trace.statements.stream().anyMatch(sql -> sql.contains("from media_asset") && sql.contains("for update")));
            }
        }
    }

    @ParameterizedTest @ValueSource(ints = {0, 1})
    void articleSaveAndPublicationRejectRetainedDeletingMedia(int status) throws Exception {
        try (var f = new ArticleMediaWriteFixture(); var auth = new CatalogMvcTestSupport();
             var context = transactions(f.base.dataSource)) {
            var mvc = articles(f, auth, context);
            f.base.insert(ASSET, "blog/images/api.png");
            String token = auth.token(1);
            long id = savedId(mvc, article(token, null, URL, 0));
            f.base.jdbc.update("UPDATE media_asset SET status='DELETE_PENDING' WHERE id=?", ASSET);
            var rejected = mvc.perform(article(token, id, URL, status))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409))
                    .andExpect(jsonPath("$.message").value(CONFLICT)).andReturn().getResponse().getContentAsString();
            assertFalse(rejected.contains(URL));
            assertEquals(0, f.base.jdbc.queryForObject("SELECT status FROM article WHERE id=?", Integer.class, id));
            assertEquals(1, f.base.jdbc.queryForObject("SELECT count(*) FROM article_tag WHERE article_id=?", Integer.class, id));
            assertTrue(f.events.isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(ints = {0, 1})
    void articleSaveAndPublicationReturnSuccessForRegisteredAssets(int status) throws Exception {
        try (var f = new ArticleMediaWriteFixture(); var auth = new CatalogMvcTestSupport();
             var context = transactions(f.base.dataSource)) {
            var mvc = articles(f, auth, context);
            f.base.insert(ASSET, "blog/images/api.png");
            long id = savedId(mvc, article(auth.token(1), null, URL, status));
            assertTrue(id > 0);
            assertEquals(status, f.base.jdbc.queryForObject("SELECT status FROM article WHERE id=?", Integer.class, id));
            assertEquals(1L, f.base.jdbc.queryForObject("SELECT author_id FROM article WHERE id=?", Long.class, id));
        }
    }

    @ParameterizedTest @ValueSource(ints = {0, 1})
    void articleProtectionDatabaseFailureReturns503WithoutWriting(int status) throws Exception {
        try (var f = new ArticleMediaWriteFixture(); var auth = new CatalogMvcTestSupport();
             var context = transactions(f.base.dataSource)) {
            var mvc = articles(f, auth, context);
            f.base.jdbc.execute("ALTER TABLE media_asset RENAME TO unavailable_media_asset");
            var rejected = mvc.perform(article(auth.token(1), null, URL, status))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(503))
                    .andExpect(jsonPath("$.message").value(UNAVAILABLE)).andReturn().getResponse().getContentAsString();
            assertFalse(rejected.contains("SELECT")); assertFalse(rejected.contains(URL));
            assertEquals(0, f.base.jdbc.queryForObject("SELECT count(*) FROM article", Integer.class));
            assertEquals(0, f.base.jdbc.queryForObject("SELECT count(*) FROM article_tag", Integer.class));
        }
    }

    @Test void ownerProfileConflictKeepsAllOriginalFields() throws Exception {
        try (var f = new OwnerMediaWriteFixture(); var auth = new CatalogMvcTestSupport();
             var context = transactions(f.base.dataSource)) {
            f.base.insert(ASSET, "blog/images/api.png"); f.account(URL);
            var mvc = owner(f, auth, context);
            var before = f.base.jdbc.queryForMap("SELECT * FROM sys_user WHERE id=1");
            f.base.jdbc.update("UPDATE media_asset SET status='DELETE_PENDING' WHERE id=?", ASSET);
            var rejected = mvc.perform(profile(auth.token(1), "新昵称", URL))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409))
                    .andExpect(jsonPath("$.message").value(CONFLICT)).andReturn().getResponse().getContentAsString();
            assertFalse(rejected.contains(URL));
            assertEquals(before, f.base.jdbc.queryForMap("SELECT * FROM sys_user WHERE id=1"));
        }
    }

    @Test void ownerProfileProtectionDatabaseFailureReturns503() throws Exception {
        try (var f = new OwnerMediaWriteFixture(); var auth = new CatalogMvcTestSupport();
             var context = transactions(f.base.dataSource)) {
            f.account(null);
            var mvc = owner(f, auth, context);
            f.base.jdbc.execute("ALTER TABLE media_asset RENAME TO unavailable_media_asset");
            mvc.perform(profile(auth.token(1), "不应写入", URL))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(503))
                    .andExpect(jsonPath("$.message").value(UNAVAILABLE));
            assertEquals("原昵称", f.base.jdbc.queryForObject("SELECT nickname FROM sys_user WHERE id=1", String.class));
            assertNull(f.base.jdbc.queryForObject("SELECT avatar FROM sys_user WHERE id=1", String.class));
        }
    }

    @Test void ownerProfileSuccessContainsTheDurablySavedAvatar() throws Exception {
        try (var f = new OwnerMediaWriteFixture(); var auth = new CatalogMvcTestSupport();
             var context = transactions(f.base.dataSource)) {
            f.account(null); f.base.insert(ASSET, "blog/images/api.png");
            var mvc = owner(f, auth, context);
            mvc.perform(profile(auth.token(1), "已保存", URL)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.avatar").value(URL))
                    .andExpect(jsonPath("$.data.nickname").value("已保存"));
            assertEquals(URL, f.base.jdbc.queryForObject("SELECT avatar FROM sys_user WHERE id=1", String.class));
        }
    }

    @Test void articleAuthenticationAndValidationContractsRemainUnchanged() throws Exception {
        try (var f = new ArticleMediaWriteFixture(); var auth = new CatalogMvcTestSupport();
             var context = transactions(f.base.dataSource)) {
            var mvc = articles(f, auth, context);
            mvc.perform(article(null, null, null, 0)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
            mvc.perform(article(auth.token(2), null, null, 0)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
            mvc.perform(post("/api/content/article").header("Authorization", auth.token(1)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"\",\"contentMd\":\"正文\",\"status\":0}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
            assertEquals(0, f.base.jdbc.queryForObject("SELECT count(*) FROM article", Integer.class));
        }
    }

    @Test void ownerProfileAuthenticationContractsRemainUnchanged() throws Exception {
        try (var f = new OwnerMediaWriteFixture(); var auth = new CatalogMvcTestSupport();
             var context = transactions(f.base.dataSource)) {
            f.account(null);
            var mvc = owner(f, auth, context);
            mvc.perform(profile(null, "匿名", null)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
            mvc.perform(profile(auth.token(2), "非博主", null)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
            assertEquals("原昵称", f.base.jdbc.queryForObject("SELECT nickname FROM sys_user WHERE id=1", String.class));
        }
    }

    private static AnnotationConfigApplicationContext transactions(DataSource source) {
        var context = new AnnotationConfigApplicationContext();
        context.register(TransactionConfig.class);
        context.registerBean(DataSource.class, () -> source);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        return context;
    }
    private static MockMvc articles(ArticleMediaWriteFixture f, CatalogMvcTestSupport auth, AnnotationConfigApplicationContext context) {
        context.registerBean(MybatisArticleRepository.class, () -> f.articles);
        context.registerBean(ArticleCommandService.class, () -> f.commands(context.getBean(MybatisArticleRepository.class)));
        context.refresh();
        var facade = new ContentApplicationFacade(context.getBean(ArticleCommandService.class), context.getBean(MybatisArticleRepository.class),
                mock(ContentQueryRepository.class), mock(TaxonomyRepository.class), mock(ArticleEngagementRepository.class),
                () -> StpUtil.getLoginIdAsLong(), Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"), ZoneOffset.UTC));
        return auth.build(new Object[]{new ContentController(facade)}, new GlobalExceptionHandler());
    }
    private static MockMvc owner(OwnerMediaWriteFixture f, CatalogMvcTestSupport auth, AnnotationConfigApplicationContext context) {
        context.registerBean(MybatisOwnerAccountRepository.class, () -> f.owners);
        context.refresh();
        var identity = new IdentityApplicationFacade(new SingleOwnerPolicy("13900000000"), context.getBean(MybatisOwnerAccountRepository.class),
                mock(OwnerCredentials.class), new SaTokenOwnerSession(), mock(LoginProtection.class));
        return auth.build(new Object[]{new AuthController(identity)}, new GlobalExceptionHandler());
    }
    private static MockHttpServletRequestBuilder article(String token, Long id, String cover, int status) throws Exception {
        return article(token, id, cover, status, "<p>正文</p>");
    }
    private static MockHttpServletRequestBuilder article(String token, Long id, String cover, int status, String text) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("id", id); body.put("title", "文章"); body.put("contentMd", text);
        body.put("coverUrl", cover); body.put("status", status); body.put("tagIds", List.of(1));
        var request = post("/api/content/article").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
        if (token != null) request.header("Authorization", token);
        return request;
    }
    private static MockHttpServletRequestBuilder profile(String token, String nickname, String avatar) throws Exception {
        var body = new LinkedHashMap<String, Object>(); body.put("nickname", nickname); body.put("avatar", avatar);
        var request = put("/api/auth/profile").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
        if (token != null) request.header("Authorization", token);
        return request;
    }
    private static long savedId(MockMvc mvc, MockHttpServletRequestBuilder request) throws Exception {
        var response = mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).path("data").path("id").longValue();
    }
    @TestConfiguration @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfig {}
}
