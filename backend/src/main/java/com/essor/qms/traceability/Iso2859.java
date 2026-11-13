package com.essor.qms.traceability;

import com.essor.qms.shared.ApiException;
import java.math.BigDecimal;
import java.util.List;

/**
 * ISO 2859-1 : niveau général II, plan simple, contrôle normal. Tableau I (lettres-codes) et tableau II-A
 * (taille d'échantillon, Ac/Re) pour les NQA 0,65 ; 1,0 ; 1,5 ; 2,5 ; 4,0 ; 6,5. Les flèches du tableau
 * renvoient au premier plan au-dessous (↓) ou au-dessus (↑), dont on prend la taille d'échantillon.
 * Si l'échantillon est au moins égal à l'effectif du lot : contrôle à 100 %.
 */
final class Iso2859 {

    /** Plan calculé. {@code letter} : lettre-code initiale (tableau I) ; {@code planLetter} : après flèches. */
    record Plan(int lotSize, String letter, String planLetter, int sampleSize, int ac, int re, String nqa) {
    }

    static final List<String> NQA = List.of("0.65", "1.0", "1.5", "2.5", "4.0", "6.5");

    private static final int[] LOT_MAX = {8, 15, 25, 50, 90, 150, 280, 500, 1200, 3200, 10000, 35000, 150000,
            500000, Integer.MAX_VALUE};
    private static final String LETTERS = "ABCDEFGHJKLMNPQR";
    private static final int[] SAMPLE = {2, 3, 5, 8, 13, 20, 32, 50, 80, 125, 200, 315, 500, 800, 1250, 2000};
    private static final int DOWN = -1;
    private static final int UP = -2;
    // Ac par ligne (lettre A..R) et colonne (NQA) ; Re = Ac + 1. DOWN / UP : flèches.
    private static final int[][] AC = {
            {DOWN, DOWN, DOWN, DOWN, DOWN, 0},
            {DOWN, DOWN, DOWN, DOWN, 0, UP},
            {DOWN, DOWN, DOWN, 0, UP, DOWN},
            {DOWN, DOWN, 0, UP, DOWN, 1},
            {DOWN, 0, UP, DOWN, 1, 2},
            {0, UP, DOWN, 1, 2, 3},
            {UP, DOWN, 1, 2, 3, 5},
            {DOWN, 1, 2, 3, 5, 7},
            {1, 2, 3, 5, 7, 10},
            {2, 3, 5, 7, 10, 14},
            {3, 5, 7, 10, 14, 21},
            {5, 7, 10, 14, 21, UP},
            {7, 10, 14, 21, UP, UP},
            {10, 14, 21, UP, UP, UP},
            {14, 21, UP, UP, UP, UP},
            {21, UP, UP, UP, UP, UP}};

    private Iso2859() {
    }

    static String letter(int lotSize) {
        if (lotSize < 2) {
            throw ApiException.badRequest("Effectif du lot insuffisant pour l'échantillonnage (2 minimum)");
        }
        for (int i = 0; i < LOT_MAX.length; i++) {
            if (lotSize <= LOT_MAX[i]) {
                return String.valueOf(LETTERS.charAt(i));
            }
        }
        throw new IllegalStateException();
    }

    static String normalizeNqa(Object nqa) {
        if (nqa == null) {
            throw ApiException.badRequest("NQA obligatoire pour l'échantillonnage ISO 2859-1");
        }
        try {
            BigDecimal v = new BigDecimal(nqa.toString().replace(',', '.').strip());
            for (String n : NQA) {
                if (new BigDecimal(n).compareTo(v) == 0) {
                    return n;
                }
            }
        } catch (NumberFormatException e) {
            // message ci-dessous
        }
        throw ApiException.badRequest("NQA non prise en charge : 0,65 ; 1,0 ; 1,5 ; 2,5 ; 4,0 ou 6,5");
    }

    static Plan plan(int lotSize, Object nqa) {
        String n = normalizeNqa(nqa);
        int col = NQA.indexOf(n);
        String letter = letter(lotSize);
        int row = LETTERS.indexOf(letter);
        while (AC[row][col] < 0) {
            row += AC[row][col] == DOWN ? 1 : -1;
        }
        int ac = AC[row][col];
        int size = Math.min(SAMPLE[row], lotSize);
        return new Plan(lotSize, letter, String.valueOf(LETTERS.charAt(row)), size, ac, ac + 1, n);
    }
}
