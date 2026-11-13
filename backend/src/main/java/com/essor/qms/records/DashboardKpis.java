package com.essor.qms.records;

import java.util.Map;

/**
 * Indicateurs fournis au tableau de bord ({@code GET /api/dashboard/summary}) par les autres modules ; appelé dans
 * la transaction (contexte RLS) de la requête. Clés reprises : {@code regulatoryCompliance},
 * {@code calibrationsDue15}, {@code calibrationsOverdue}.
 */
public interface DashboardKpis {

    Map<String, Object> kpis();
}
