package com.essor.qms.tenancy;

import java.util.UUID;

public record TenantRef(UUID id, String code, String name) {
}
