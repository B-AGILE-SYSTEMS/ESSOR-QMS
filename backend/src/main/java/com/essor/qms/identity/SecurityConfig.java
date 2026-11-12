package com.essor.qms.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Deux chaînes :
 * <ul>
 *   <li>Bearer JWT (mobile) : sans état, sans CSRF, sur toute requête portant {@code Authorization: Bearer} ;</li>
 *   <li>BFF (web) : oauth2Login, session {@code SESSION}, CSRF par cookie {@code XSRF-TOKEN} /
 *       en-tête {@code X-XSRF-TOKEN}. Le navigateur ne voit jamais de jeton.</li>
 * </ul>
 */
@Configuration
@EnableMethodSecurity
class SecurityConfig {

    private static final String[] PUBLIC = {"/api/health", "/actuator/health/**", "/error"};

    @Bean
    @Order(1)
    SecurityFilterChain bearerChain(HttpSecurity http) throws Exception {
        http.securityMatcher(request -> {
                    String h = request.getHeader("Authorization");
                    return h != null && h.regionMatches(true, 0, "Bearer ", 0, 7);
                })
                .authorizeHttpRequests(a -> a.requestMatchers(PUBLIC).permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(c -> c.disable())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtConverter())));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain bffChain(HttpSecurity http, LogoutSuccessHandler logoutSuccessHandler) throws Exception {
        http.authorizeHttpRequests(a -> a.requestMatchers(PUBLIC).permitAll()
                        .requestMatchers("/oauth2/**", "/login/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2Login(o -> o.userInfoEndpoint(u -> u.userAuthoritiesMapper(oidcAuthoritiesMapper())))
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), new AntPathRequestMatcher("/api/**")))
                .csrf(c -> c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .logout(l -> l.logoutUrl("/logout").logoutSuccessHandler(logoutSuccessHandler));
        return http.build();
    }

    static JwtAuthenticationConverter jwtConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> KeycloakRoles.fromClaims(jwt.getClaims()));
        converter.setPrincipalClaimName("preferred_username");
        return converter;
    }

    static GrantedAuthoritiesMapper oidcAuthoritiesMapper() {
        return authorities -> {
            Set<GrantedAuthority> mapped = new HashSet<>();
            for (GrantedAuthority a : authorities) {
                if (a instanceof OidcUserAuthority oidc) {
                    mapped.addAll(KeycloakRoles.fromClaims(oidc.getIdToken().getClaims()));
                    if (oidc.getUserInfo() != null) {
                        mapped.addAll(KeycloakRoles.fromClaims(oidc.getUserInfo().getClaims()));
                    }
                }
            }
            return mapped;
        };
    }

    /**
     * Déconnexion : invalide la session puis renvoie vers la déconnexion Keycloak.
     * Appel XHR (Accept JSON) : 200 {@code {"logoutUrl": ...}} que le front suit ; sinon redirection 302.
     */
    @Bean
    LogoutSuccessHandler logoutSuccessHandler(
            @Value("${essor.security.end-session-uri:}") String endSessionUri,
            @Value("${essor.security.post-logout-redirect-uri:/}") String postLogoutRedirect,
            @Value("${spring.security.oauth2.client.registration.keycloak.client-id:essor-bff}") String clientId) {
        return (request, response, authentication) -> {
            String target = postLogoutRedirect;
            if (StringUtils.hasText(endSessionUri)) {
                StringBuilder url = new StringBuilder(endSessionUri)
                        .append("?client_id=").append(enc(clientId))
                        .append("&post_logout_redirect_uri=").append(enc(postLogoutRedirect));
                if (authentication != null && authentication.getPrincipal() instanceof OidcUser oidc) {
                    url.append("&id_token_hint=").append(enc(oidc.getIdToken().getTokenValue()));
                }
                target = url.toString();
            }
            String accept = request.getHeader("Accept");
            if (accept != null && accept.contains("application/json")) {
                response.setStatus(200);
                response.setContentType("application/json");
                response.getWriter().write("{\"logoutUrl\":\"" + target.replace("\"", "%22") + "\"}");
            } else {
                response.sendRedirect(target);
            }
        };
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    /** Gestion CSRF pour SPA (doc Spring Security 6) : en-tête brut accepté, paramètre masqué (BREACH). */
    static final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {
        private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
        private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
            xor.handle(request, response, csrfToken);
            csrfToken.get();
        }

        @Override
        public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
            String header = request.getHeader(csrfToken.getHeaderName());
            return StringUtils.hasText(header)
                    ? plain.resolveCsrfTokenValue(request, csrfToken)
                    : xor.resolveCsrfTokenValue(request, csrfToken);
        }
    }

    /** Force l'émission du cookie XSRF-TOKEN à chaque réponse. */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) {
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }
}
