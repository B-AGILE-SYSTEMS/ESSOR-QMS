package com.essor.qms;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** Vérifie les frontières Spring Modulith (tenancy, identity, shared) : pas de cycle, pas d'accès interne. */
class ModularityTest {

    @Test
    void frontieresDesModulesRespectees() {
        ApplicationModules.of(EssorQmsApplication.class).verify();
    }
}
