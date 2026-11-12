package com.essor.qms.identity;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Traduit les rôles realm Keycloak ({@code realm_access.roles}) en autorités {@code ROLE_*}. */
final class KeycloakRoles {

    static final Set<String> KNOWN = Set.of("ADMIN", "QUALITY_MANAGER", "AUDITOR", "FIELD_USER", "READER");

    private KeycloakRoles() {
    }

    static List<GrantedAuthority> fromClaims(Map<String, Object> claims) {
        if (claims == null || !(claims.get("realm_access") instanceof Map<?, ?> realmAccess)) {
            return List.of();
        }
        if (!(realmAccess.get("roles") instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .map(String::valueOf)
                .filter(KNOWN::contains)
                .sorted()
                .<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
    }
}
