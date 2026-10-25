package com.blogsystem.app.it.trigger.http.support;

import com.blogsystem.trigger.interceptor.SingleOwnerSessionInterceptor;

import com.blogsystem.infrastructure.security.identity.SaTokenOwnerSession;

import com.blogsystem.domain.identity.gateway.LoginProtection;
import com.blogsystem.domain.identity.gateway.OwnerCredentials;
import com.blogsystem.domain.identity.model.OwnerAccount;
import com.blogsystem.domain.identity.model.SingleOwnerPolicy;

import java.util.List;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import java.util.Optional;

import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.spring.SaTokenContextForSpringInJakartaServlet;
import com.blogsystem.application.identity.service.IdentityApplicationFacade;
import com.blogsystem.domain.identity.adapter.repository.OwnerAccountRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;

/** Isolated real single-owner session boundary; no production password or token. */
public final class CatalogMvcTestSupport implements AutoCloseable {
    final SaTokenConfig config=SaManager.getConfig();final SaTokenDao dao=SaManager.getSaTokenDao();
    final SaTokenContext context=SaManager.getSaTokenContext();final StpInterface roles=SaManager.getStpInterface();final StpLogic logic=StpUtil.getStpLogic();
    final SingleOwnerSessionInterceptor owner;
    private final IdentityApplicationFacade identity;
    public CatalogMvcTestSupport(){
        SaManager.setConfig(new SaTokenConfig().setTokenName("Authorization").setIsReadCookie(false));
        SaManager.setSaTokenDao(new SaTokenDaoDefaultImpl());SaManager.setSaTokenContext(new SaTokenContextForSpringInJakartaServlet());
        SaManager.setStpInterface(new StpInterface(){public List<String> getPermissionList(Object id,String type){return List.of();}public List<String> getRoleList(Object id,String type){return List.of("BLOG_OWNER");}});
        StpUtil.setStpLogic(new StpLogic("login"));
        var accounts=mock(OwnerAccountRepository.class);
        when(accounts.findById(1L)).thenReturn(Optional.of(new OwnerAccount(1,"13900000000","owner",null,null,null,"fixture",true)));
        when(accounts.findById(2L)).thenReturn(Optional.of(new OwnerAccount(2,"13800000000","old-admin",null,null,null,"fixture",true)));
        identity=new IdentityApplicationFacade(new SingleOwnerPolicy("13900000000"),accounts,mock(OwnerCredentials.class),new SaTokenOwnerSession(),mock(LoginProtection.class));
        owner=new SingleOwnerSessionInterceptor(identity);
    }
    public MockMvc build(Object[] controllers,Object... advice){return MockMvcBuilders.standaloneSetup(controllers).setControllerAdvice(advice).addInterceptors(owner,new SaInterceptor()).build();}
    public IdentityApplicationFacade identity(){return identity;}
    public String token(int id){String token=StpUtil.getStpLogic().createLoginSession(id);StpUtil.getTokenSessionByToken(token).set(SaTokenOwnerSession.PASSWORD_SESSION_MARKER,true);return token;}
    public void close(){SaManager.setConfig(config);SaManager.setSaTokenDao(dao);SaManager.setSaTokenContext(context);SaManager.setStpInterface(roles);StpUtil.setStpLogic(logic);}
}
