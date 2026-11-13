package com.essor.qms.records;

import java.time.LocalDate;

/**
 * Tâche quotidienne d'un autre module, exécutée par la tâche planifiée M1 des échéances, une fois par tenant, dans
 * sa transaction et son contexte RLS. Rend le nombre d'alertes publiées.
 */
public interface DailyTask {

    int run(LocalDate today);
}
