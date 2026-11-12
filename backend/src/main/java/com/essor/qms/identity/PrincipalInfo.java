package com.essor.qms.identity;

import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Informations d'identité extraites d'une session OIDC (BFF) ou d'un jeton Bearer (mobile). */
record PrincipalInfo(String subject, String username, String displayName, String tenantCode, List<String> roles) {

    static PrincipalInfo from(Authentication auth, String tenantClaim) {
        Map<String, Object> claims;
        if (auth instanceof JwtAuthenticationToken jwt) {
            claims = jwt.getToken().getClaims();
        } else if (auth.getPrincipal() instanceof OidcUser oidc) {
            claims = oidc.getClaims();
        } else {
            return null;
        }
        Object sub = claims.get("sub");
        if (sub == null) {
            return null;
        }
        String username = str(claims.get("preferred_username"), sub.toString());
        List<String> roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .sorted()
                .toList();
        return new PrincipalInfo(sub.toString(), username, str(claims.get("name"), username),
                str(claims.get(tenantClaim), null), roles);
    }

    private static String str(Object value, String fallback) {
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }
}
