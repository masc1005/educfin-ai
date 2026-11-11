package com.educfin.integration;

import com.educfin.domain.user.Email;
import com.educfin.domain.user.UserId;
import com.educfin.application.port.out.UserRepository;
import com.educfin.presentation.dto.request.LoginRequest;
import com.educfin.presentation.dto.request.RefreshTokenRequest;
import com.educfin.presentation.dto.request.RegisterRequest;
import com.educfin.presentation.dto.response.TokenResponse;
import com.educfin.presentation.dto.response.UserResponse;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de integração ponta a ponta com PostgreSQL real (Testcontainers),
 * migrations do Flyway, segurança HTTP e persistência JPA.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("TRUNCATE TABLE users");
    }

    @Test
    @DisplayName("fluxo completo: cadastro -> login -> /me -> refresh -> /me com novo token")
    void fullAuthFlow() throws Exception {
        var registerJson = objectMapper.writeValueAsString(
                new RegisterRequest("Maria Silva", "Maria@Example.com", "Senha123"));

        String registerBody = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Maria Silva"))
                .andExpect(jsonPath("$.email").value("maria@example.com"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        UserResponse registered = objectMapper.readValue(registerBody, UserResponse.class);

        var persisted = userRepository.findByEmail(Email.of("maria@example.com")).orElseThrow();
        assertThat(persisted.getPasswordHash()).startsWith("$2a$").isNotEqualTo("Senha123");

        var loginJson = objectMapper.writeValueAsString(
                new LoginRequest("maria@example.com", "Senha123"));

        String loginBody = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        TokenResponse tokens = objectMapper.readValue(loginBody, TokenResponse.class);

        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(registered.id().toString()))
                .andExpect(jsonPath("$.email").value("maria@example.com"))
                .andExpect(jsonPath("$.createdAt").value(registered.createdAt().toString()));

        var refreshJson = objectMapper.writeValueAsString(new RefreshTokenRequest(tokens.refreshToken()));

        String refreshBody = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshJson))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        TokenResponse newTokens = objectMapper.readValue(refreshBody, TokenResponse.class);
        assertThat(newTokens.accessToken()).isNotEqualTo(tokens.accessToken());

        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + newTokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(registered.id().toString()));
    }

    @Test
    @DisplayName("cadastro com e-mail duplicado retorna 409 Problem Details")
    void duplicateEmailReturns409() throws Exception {
        var json = objectMapper.writeValueAsString(
                new RegisterRequest("Maria", "maria@example.com", "Senha123"));

        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("E-mail já está em uso"));
    }

    @Test
    @DisplayName("payload inválido retorna 400 com mapa de campos")
    void validationErrorsReturn400() throws Exception {
        var json = objectMapper.writeValueAsString(new RegisterRequest("", "invalido", "123"));

        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    @Test
    @DisplayName("violação de regra de domínio (senha sem número) retorna 422")
    void domainRuleViolationReturns422() throws Exception {
        var json = objectMapper.writeValueAsString(
                new RegisterRequest("Maria", "maria@example.com", "somenteletras"));

        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    @DisplayName("credenciais inválidas retornam 401")
    void badCredentialsReturn401() throws Exception {
        var json = objectMapper.writeValueAsString(new LoginRequest("ninguem@example.com", "Senha123"));

        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("endpoint protegido rejeita requisição sem token e rejeita refresh token usado como Bearer")
    void protectedEndpointRejectsMissingOrWrongTokenType() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new RegisterRequest("Maria", "maria@example.com", "Senha123"))));

        String loginBody = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("maria@example.com", "Senha123"))))
                .andReturn().getResponse().getContentAsString();
        TokenResponse tokens = objectMapper.readValue(loginBody, TokenResponse.class);

        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.refreshToken()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest(tokens.accessToken()))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("usuário desativado após emitir o refresh token não consegue renovar")
    void deactivatedUserCannotRefresh() throws Exception {
        String registerBody = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest("Maria", "maria@example.com", "Senha123"))))
                .andReturn().getResponse().getContentAsString();
        UserResponse registered = objectMapper.readValue(registerBody, UserResponse.class);

        String loginBody = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("maria@example.com", "Senha123"))))
                .andReturn().getResponse().getContentAsString();
        TokenResponse tokens = objectMapper.readValue(loginBody, TokenResponse.class);

        var user = userRepository.findById(UserId.of(registered.id())).orElseThrow();
        user.deactivate(Instant.now());
        userRepository.save(user);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest(tokens.refreshToken()))))
                .andExpect(status().isUnauthorized());
    }
}
