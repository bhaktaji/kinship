package com.kinship.auth;

import com.kinship.common.api.GlobalExceptionHandler;
import com.kinship.common.config.AppProperties;
import com.kinship.common.ratelimit.RateLimitService;
import com.kinship.common.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerValidationTest {

    private MockMvc mvc() {
        // Only invalid requests reach this test: validation runs before any collaborator is used.
        AuthController controller = new AuthController(null, null, null,
                new AppProperties(null, null, null, null, null));
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void rejectsInvalidRegistrationBody() throws Exception {
        mvc().perform(post("/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email","password":"short","display_name":"A"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details").exists());
    }

    @Test
    void rejectsMissingCredentials() throws Exception {
        mvc().perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
}
