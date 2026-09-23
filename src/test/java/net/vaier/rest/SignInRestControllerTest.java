package net.vaier.rest;

import net.vaier.application.AddSignInProviderUseCase;
import net.vaier.application.GetSignInProvidersUseCase;
import net.vaier.application.GetSignInProvidersUseCase.SignInOverview;
import net.vaier.domain.IdentityProvider;
import net.vaier.domain.ProviderSource;
import net.vaier.domain.ProviderStanding;
import net.vaier.domain.SignInApplyOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SignInRestControllerTest {

    @Mock GetSignInProvidersUseCase getSignInProvidersUseCase;
    @Mock AddSignInProviderUseCase addSignInProviderUseCase;

    @InjectMocks SignInRestController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void get_describesEachProviderForTheSettingsForm_andNeverASecret() throws Exception {
        when(getSignInProvidersUseCase.getSignInProviders()).thenReturn(new SignInOverview(List.of(
            new ProviderStanding(IdentityProvider.GOOGLE, ProviderSource.SETTINGS, "g-id"),
            new ProviderStanding(IdentityProvider.GITHUB, ProviderSource.ENVIRONMENT, "gh-id")),
            "https://dex.example.com/callback", true));

        mockMvc.perform(get("/settings/sign-in"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.redirectUri").value("https://dex.example.com/callback"))
            .andExpect(jsonPath("$.firstRunDoorOpen").value(true))
            .andExpect(jsonPath("$.providers[0].id").value("google"))
            .andExpect(jsonPath("$.providers[0].name").value("Google"))
            .andExpect(jsonPath("$.providers[0].consoleUrl").value("https://console.cloud.google.com/apis/credentials"))
            .andExpect(jsonPath("$.providers[0].source").value("SETTINGS"))
            .andExpect(jsonPath("$.providers[0].clientId").value("g-id"))
            .andExpect(jsonPath("$.providers[1].source").value("ENVIRONMENT"))
            .andExpect(content().string(not(containsString("ecret"))));
    }

    @Test
    void put_addsTheProviderNamedInThePath_andAnswersTheOutcome_orRefusesAnUnknownOne() throws Exception {
        when(addSignInProviderUseCase.addSignInProvider(IdentityProvider.GITHUB, "gh-id", "gh-secret"))
            .thenReturn(new SignInApplyOutcome(false, "dex-init could not render it"));

        mockMvc.perform(put("/settings/sign-in/github").contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientId\":\"gh-id\",\"clientSecret\":\"gh-secret\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.applied").value(false))
            .andExpect(jsonPath("$.message").value("dex-init could not render it"));

        mockMvc.perform(put("/settings/sign-in/local").contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientId\":\"x\",\"clientSecret\":\"y\"}"))
            .andExpect(status().isBadRequest());
    }
}
