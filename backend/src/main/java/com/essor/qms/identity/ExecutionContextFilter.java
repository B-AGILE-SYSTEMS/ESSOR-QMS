package com.essor.qms.identity;

import com.essor.qms.identity.UserProvisioning.ProvisionedUser;
import com.essor.qms.identity.UserProvisioning.ProvisioningRefusedException;
import com.essor.qms.shared.ExecutionContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Après la chaîne de sécurité : provisionne l'utilisateur authentifié et positionne
 * le contexte (utilisateur, tenant) lu par le gestionnaire de transactions pour la RLS.
 */
@Component
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.LOWEST_PRECEDENCE - 10)
class ExecutionContextFilter extends OncePerRequestFilter {

    private final UserProvisioning provisioning;
    private final String tenantClaim;

    ExecutionContextFilter(UserProvisioning provisioning,
                           @Value("${essor.tenancy.tenant-claim:tenant}") String tenantClaim) {
        this.provisioning = provisioning;
        this.tenantClaim = tenantClaim;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        try {
            if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
                PrincipalInfo principal = PrincipalInfo.from(auth, tenantClaim);
                if (principal != null) {
                    ProvisionedUser user;
                    try {
                        user = provisioning.provision(principal);
                    } catch (ProvisioningRefusedException e) {
                        response.sendError(HttpServletResponse.SC_FORBIDDEN);
                        return;
                    }
                    ExecutionContext.set(user.userId(), user.tenant().id());
                    request.setAttribute(PrincipalInfo.class.getName(), principal);
                }
            }
            chain.doFilter(request, response);
        } finally {
            ExecutionContext.clear();
        }
    }
}
