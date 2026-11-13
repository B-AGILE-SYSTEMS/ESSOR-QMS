package com.essor.qms.traceability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.essor.qms.shared.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Cas connus des tableaux I et II-A d'ISO 2859-1 (niveau II, simple, normal). */
class Iso2859Test {

    @ParameterizedTest
    @CsvSource({"2,A", "8,A", "9,B", "15,B", "16,C", "50,D", "51,E", "90,E", "150,F", "280,G", "281,H", "500,H",
            "1200,J", "3200,K", "10000,L", "35000,M", "150000,N", "500000,P", "500001,Q"})
    void lettreCodeSelonEffectif(int lotSize, String letter) {
        assertThat(Iso2859.letter(lotSize)).isEqualTo(letter);
    }

    @ParameterizedTest(name = "lot {0}, NQA {1} -> n={2} Ac={3} Re={4}")
    @CsvSource({
            "1000, 2.5, 80, 5, 6",       // J, 2,5 : 80 / 5-6
            "1000, 1.0, 80, 2, 3",       // J, 1,0
            "1000, 0.65, 80, 1, 2",
            "1000, 6.5, 80, 10, 11",
            "2000, 1.0, 125, 3, 4",      // K
            "5000, 2.5, 200, 10, 11",    // L
            "5000, 4.0, 200, 14, 15",
            "100, 4.0, 20, 2, 3",        // F
            "100, 0.65, 20, 0, 1",       // F, 0,65 : 0-1
            "100, 1.0, 13, 0, 1",        // F, 1,0 : ↑ -> E (13) 0-1
            "200, 0.65, 20, 0, 1",       // G, 0,65 : ↑ -> F
            "200, 1.0, 50, 1, 2",        // G, 1,0 : ↓ -> H (50) 1-2
            "40, 2.5, 5, 0, 1",          // D, 2,5 : ↑ -> C (5) 0-1
            "40, 1.0, 13, 0, 1",         // D, 1,0 : ↓ -> E (13)
            "500000, 6.5, 200, 21, 22",  // P, 6,5 : ↑↑↑ -> L
            "500000, 4.0, 315, 21, 22",  // P, 4,0 : ↑↑ -> M
            "10, 0.65, 10, 0, 1"})       // B, 0,65 : ↓ jusqu'à F (20) > lot : 100 %
    void tailleEtCriteresAcceptation(int lotSize, String nqa, int n, int ac, int re) {
        Iso2859.Plan p = Iso2859.plan(lotSize, nqa);
        assertThat(p.sampleSize()).isEqualTo(n);
        assertThat(p.ac()).isEqualTo(ac);
        assertThat(p.re()).isEqualTo(re);
    }

    @Test
    void nqaAvecVirguleEtNombre() {
        assertThat(Iso2859.plan(1000, "2,5").nqa()).isEqualTo("2.5");
        assertThat(Iso2859.plan(1000, 1).nqa()).isEqualTo("1.0");
        assertThat(Iso2859.plan(1000, "2.5").planLetter()).isEqualTo("J");
    }

    @Test
    void nqaNonPriseEnChargeRefusee() {
        assertThatThrownBy(() -> Iso2859.plan(1000, "10")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Iso2859.letter(1)).isInstanceOf(ApiException.class);
    }
}
