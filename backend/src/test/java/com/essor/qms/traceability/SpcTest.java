package com.essor.qms.traceability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Carte X̄/R et capabilité sur des valeurs calculées à la main. */
class SpcTest {

    @Test
    void limitesEtCapabiliteSousGroupesDeCinq() {
        // 3 sous-groupes n=5 : moyennes 10, 10.2, 9.8 ; étendues 0.4, 0.4, 0.4
        List<List<Double>> sg = List.of(
                List.of(9.8, 10.0, 10.2, 10.0, 10.0),
                List.of(10.0, 10.2, 10.4, 10.2, 10.2),
                List.of(9.6, 9.8, 10.0, 9.8, 9.8));
        Spc.Result r = Spc.compute(sg, 9.0, 11.0);
        assertThat(r.subgroupSize()).isEqualTo(5);
        assertThat(r.points()).extracting(Spc.Point::mean).containsExactly(10.0, 10.2, 9.8);
        assertThat(r.xbar().cl()).isCloseTo(10.0, within(1e-9));
        assertThat(r.r().cl()).isCloseTo(0.4, within(1e-9));
        // A2(5) = 0.577 -> 10 ± 0.2308 ; D4 = 2.114 ; D3 = 0
        assertThat(r.xbar().ucl()).isCloseTo(10.2308, within(1e-4));
        assertThat(r.xbar().lcl()).isCloseTo(9.7692, within(1e-4));
        assertThat(r.r().ucl()).isCloseTo(0.8456, within(1e-4));
        assertThat(r.r().lcl()).isZero();
        // σ = 0.4 / 2.326 = 0.17197 ; Cp = 2 / (6σ) = 1.9383 ; centré : Cpk = Cp
        assertThat(r.sigma()).isCloseTo(0.172, within(1e-3));
        assertThat(r.cp()).isCloseTo(1.9383, within(1e-3));
        assertThat(r.cpk()).isCloseTo(1.9383, within(1e-3));
        assertThat(r.alerts()).isEmpty();
    }

    @Test
    void cpkDecentre() {
        List<List<Double>> sg = List.of(List.of(10.4, 10.6), List.of(10.6, 10.4));
        Spc.Result r = Spc.compute(sg, 9.0, 11.0);
        // X̄̄ = 10.5, R̄ = 0.2, σ = 0.2/1.128 = 0.17730 ; Cpk = (11-10.5)/(3σ) = 0.94
        assertThat(r.cpk()).isCloseTo(0.94, within(1e-3));
        assertThat(r.cp()).isCloseTo(1.88, within(1e-3));
    }

    @Test
    void alertesHorsLimitesEtSeptPointsMemeCote() {
        List<List<Double>> sg = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            sg.add(List.of(10.1, 10.2, 10.1));
        }
        for (int i = 0; i < 7; i++) {
            sg.add(List.of(9.9, 9.8, 9.9));
        }
        sg.add(List.of(12.0, 12.1, 12.0));
        Spc.Result r = Spc.compute(sg, 9.0, 11.0);
        assertThat(r.alerts()).extracting(Spc.Alert::type)
                .contains("sept_points_meme_cote", "hors_limites");
        assertThat(r.alerts()).filteredOn(a -> a.type().equals("hors_limites"))
                .extracting(Spc.Alert::index).contains(14);
        assertThat(r.alerts()).filteredOn(a -> a.type().equals("sept_points_meme_cote"))
                .extracting(Spc.Alert::index).contains(6);
    }

    @Test
    void sansSousGroupe() {
        Spc.Result r = Spc.compute(List.of(), 1.0, 2.0);
        assertThat(r.points()).isEmpty();
        assertThat(r.xbar()).isNull();
    }
}
