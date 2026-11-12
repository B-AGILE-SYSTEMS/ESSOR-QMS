package com.essor.qms.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

class KeycloakRolesTest {

    @Test
    void ne_garde_que_les_roles_realm_connus() {
        var claims = Map.<String, Object>of("realm_access",
                Map.of("roles", List.of("offline_access", "ADMIN", "READER", "uma_authorization")));
        assertThat(KeycloakRoles.fromClaims(claims)).map(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN", "ROLE_READER");
    }

    @Test
    void sans_realm_access_aucun_role() {
        assertThat(KeycloakRoles.fromClaims(Map.of("sub", "x"))).isEmpty();
        assertThat(KeycloakRoles.fromClaims(null)).isEmpty();
        assertThat(KeycloakRoles.fromClaims(Map.of("realm_access", "pas une map"))).isEmpty();
    }
}
