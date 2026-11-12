package com.essor.qms.identity;

import com.essor.qms.shared.ExecutionContext;
import com.essor.qms.tenancy.SiteRef;
import com.essor.qms.tenancy.TenantDirectory;
import com.essor.qms.tenancy.TenantRef;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class MeController {

    record Me(String username, String displayName, TenantRef tenant, List<SiteRef> sites, List<String> roles) {
    }

    private final TenantDirectory tenants;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    MeController(TenantDirectory tenants, JdbcClient jdbc, TransactionTemplate tx) {
        this.tenants = tenants;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @GetMapping("/api/me")
    Me me(HttpServletRequest request) {
        PrincipalInfo p = (PrincipalInfo) request.getAttribute(PrincipalInfo.class.getName());
        if (p == null || ExecutionContext.tenantId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        TenantRef tenant = tenants.findById(ExecutionContext.tenantId()).orElseThrow();
        List<SiteRef> sites = tx.execute(s -> jdbc.sql("""
                select s.id, s.code, s.name from site s
                join user_site us on us.site_id = s.id and us.tenant_id = s.tenant_id
                where us.user_id = app_current_user()
                order by s.code
                """).query(SiteRef.class).list());
        return new Me(p.username(), p.displayName(), tenant, sites, p.roles());
    }
}
