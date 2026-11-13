package com.essor.qms.traceability;

import java.util.ArrayList;
import java.util.List;

/**
 * Carte X̄/R : moyenne et étendue par sous-groupe, limites (A2, D3, D4), σ estimé = R̄/d2, Cp/Cpk,
 * alertes « point hors limites » et « 7 points consécutifs du même côté de la ligne centrale ».
 */
final class Spc {

    /** Constantes pour n = 2..10 (index n-2). */
    private static final double[] A2 = {1.880, 1.023, 0.729, 0.577, 0.483, 0.419, 0.373, 0.337, 0.308};
    private static final double[] D3 = {0, 0, 0, 0, 0, 0.076, 0.136, 0.184, 0.223};
    private static final double[] D4 = {3.267, 2.574, 2.282, 2.114, 2.004, 1.924, 1.864, 1.816, 1.777};
    private static final double[] D2 = {1.128, 1.693, 2.059, 2.326, 2.534, 2.704, 2.847, 2.970, 3.078};
    static final int RUN = 7;

    record Point(double mean, double range) {
    }

    record Limits(double lcl, double cl, double ucl) {
    }

    record Alert(String type, int index, String message) {
    }

    record Result(int subgroupSize, List<Point> points, Limits xbar, Limits r, Double sigma, Double cp, Double cpk,
                  List<Alert> alerts) {
    }

    private Spc() {
    }

    static boolean supported(int n) {
        return n >= 2 && n <= 10;
    }

    /** {@code subgroups} : tous de taille n (2..10). Limites nulles s'il n'y a aucun sous-groupe. */
    static Result compute(List<List<Double>> subgroups, Double tolMin, Double tolMax) {
        List<Point> points = subgroups.stream().map(Spc::point).toList();
        if (points.isEmpty()) {
            return new Result(0, points, null, null, null, null, null, List.of());
        }
        int n = subgroups.get(0).size();
        int k = n - 2;
        double xbb = points.stream().mapToDouble(Point::mean).average().orElse(0);
        double rbar = points.stream().mapToDouble(Point::range).average().orElse(0);
        Limits x = new Limits(round(xbb - A2[k] * rbar), round(xbb), round(xbb + A2[k] * rbar));
        Limits r = new Limits(round(D3[k] * rbar), round(rbar), round(D4[k] * rbar));
        Double sigma = rbar > 0 ? rbar / D2[k] : null;
        Double cp = null;
        Double cpk = null;
        if (sigma != null) {
            if (tolMin != null && tolMax != null) {
                cp = round((tolMax - tolMin) / (6 * sigma));
            }
            Double up = tolMax == null ? null : (tolMax - xbb) / (3 * sigma);
            Double lo = tolMin == null ? null : (xbb - tolMin) / (3 * sigma);
            cpk = up == null ? (lo == null ? null : round(lo)) : round(lo == null ? up : Math.min(up, lo));
        }
        return new Result(n, points, x, r, sigma == null ? null : round(sigma), cp, cpk, alerts(points, x, r));
    }

    private static List<Alert> alerts(List<Point> points, Limits x, Limits r) {
        List<Alert> alerts = new ArrayList<>();
        int run = 0;
        int side = 0;
        for (int i = 0; i < points.size(); i++) {
            Point p = points.get(i);
            if (p.mean() > x.ucl() || p.mean() < x.lcl()) {
                alerts.add(new Alert("hors_limites", i, "Moyenne hors limites de contrôle (point " + (i + 1) + ")"));
            }
            if (p.range() > r.ucl() || p.range() < r.lcl()) {
                alerts.add(new Alert("etendue_hors_limites", i, "Étendue hors limites (point " + (i + 1) + ")"));
            }
            int s = Double.compare(p.mean(), x.cl());
            run = s != 0 && s == side ? run + 1 : (s == 0 ? 0 : 1);
            side = s;
            if (run == RUN) {
                alerts.add(new Alert("sept_points_meme_cote", i,
                        RUN + " points consécutifs du même côté de la moyenne (jusqu'au point " + (i + 1) + ")"));
            }
        }
        return List.copyOf(alerts);
    }

    private static Point point(List<Double> values) {
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        return new Point(round(mean), round(max - min));
    }

    static double round(double v) {
        return Math.round(v * 10000d) / 10000d;
    }
}
