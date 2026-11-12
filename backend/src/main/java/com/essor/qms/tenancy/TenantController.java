package com.essor.qms.tenancy;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tenants")
@PreAuthorize("hasRole('ADMIN')")
class TenantController {

    record CreateTenant(
            @NotBlank @Size(max = 50) @Pattern(regexp = "^[A-Z0-9_-]+$") String code,
            @NotBlank @Size(max = 200) String name) {
    }

    private final TenantDirectory tenants;

    TenantController(TenantDirectory tenants) {
        this.tenants = tenants;
    }

    @GetMapping
    List<TenantRef> list() {
        return tenants.findAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    TenantRef create(@Valid @RequestBody CreateTenant body) {
        return tenants.create(body.code(), body.name());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    void duplicate() {
        // code de tenant déjà utilisé
    }
}
