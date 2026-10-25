package com.blogsystem.trigger.http.identity;

import com.blogsystem.trigger.http.identity.AuthController;

import org.mockito.Mockito;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import com.blogsystem.application.identity.service.IdentityApplicationFacade;
import com.blogsystem.trigger.http.support.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RetiredAuthenticationRoutesTest {
    @Test
    void smsRegistrationAndRecoveryRoutesNoLongerExist() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new AuthController(mock(IdentityApplicationFacade.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        for (String route : new String[]{"sms-code", "register", "login", "password/reset"}) {
            mvc.perform(post("/api/auth/" + route).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"19140838906\",\"bizType\":\"LOGIN\",\"code\":\"123456\",\"password\":\"fixture-password\",\"confirmPassword\":\"fixture-password\",\"newPassword\":\"fixture-password\"}"))
                    .andExpect(status().isNotFound());
        }
    }
}
