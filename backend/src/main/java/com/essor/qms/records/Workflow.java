package com.essor.qms.records;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Automate d'états d'un type de fiche (JSON maison) :
 * {@code {"initial","states":[{code,label,final}],"transitions":[{code,label,from,to,roles,requires,childrenIn}]}}.
 * {@code childrenIn} (optionnel) : toutes les fiches enfants doivent être dans l'un de ces statuts.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record Workflow(String initial, List<State> states, List<Transition> transitions) {

    static final Set<String> ROLES = Set.of("ADMIN", "QUALITY_MANAGER", "AUDITOR", "FIELD_USER", "READER");
    private static final Pattern CODE = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,29}$");
    private static final int MAX_ITEMS = 50;

    record State(String code, String label, @JsonProperty("final") boolean isFinal) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Transition(String code, String label, String from, String to, List<String> roles,
                      List<String> requires, List<String> childrenIn) {

        List<String> requiresOrEmpty() {
            return requires == null ? List.of() : requires;
        }

        List<String> childrenInOrEmpty() {
            return childrenIn == null ? List.of() : childrenIn;
        }
    }

    Optional<State> state(String code) {
        return states.stream().filter(s -> s.code().equals(code)).findFirst();
    }

    String label(String status) {
        return state(status).map(State::label).orElse(status);
    }

    boolean isFinal(String status) {
        return state(status).map(State::isFinal).orElse(false);
    }

    List<Transition> from(String status) {
        return transitions.stream().filter(t -> t.from().equals(status)).toList();
    }

    @JsonIgnore
    Set<String> finalStates() {
        Set<String> s = new HashSet<>();
        states.forEach(st -> {
            if (st.isFinal()) {
                s.add(st.code());
            }
        });
        return s;
    }

    /** Erreurs de cohérence, en français ; liste vide si le workflow est valide. */
    List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (states == null || states.isEmpty() || states.size() > MAX_ITEMS) {
            errors.add("states : de 1 à " + MAX_ITEMS + " états attendus");
            return errors;
        }
        if (transitions == null || transitions.size() > MAX_ITEMS * 2) {
            errors.add("transitions : liste attendue (" + MAX_ITEMS * 2 + " au plus)");
            return errors;
        }
        Set<String> codes = new HashSet<>();
        for (State s : states) {
            if (s == null || s.code() == null || !CODE.matcher(s.code()).matches()) {
                errors.add("état : code invalide");
            } else if (!codes.add(s.code())) {
                errors.add("état " + s.code() + " : code en double");
            }
            if (s != null && (s.label() == null || s.label().isBlank() || s.label().length() > 100)) {
                errors.add("état " + (s.code() == null ? "?" : s.code()) + " : libellé obligatoire (100 caractères max)");
            }
        }
        if (initial == null || !codes.contains(initial)) {
            errors.add("initial : doit désigner un état existant");
        }
        if (states.stream().noneMatch(s -> s != null && s.isFinal())) {
            errors.add("au moins un état final est requis");
        }
        Set<String> keys = new HashSet<>();
        for (Transition t : transitions) {
            validateTransition(t, codes, keys, errors);
        }
        return errors;
    }

    private static void validateTransition(Transition t, Set<String> states, Set<String> keys, List<String> errors) {
        if (t == null || t.code() == null || !CODE.matcher(t.code()).matches()) {
            errors.add("transition : code invalide");
            return;
        }
        String name = "transition " + t.code();
        if (t.label() == null || t.label().isBlank() || t.label().length() > 100) {
            errors.add(name + " : libellé obligatoire (100 caractères max)");
        }
        if (!states.contains(t.from()) || !states.contains(t.to())) {
            errors.add(name + " : from et to doivent désigner des états existants");
        }
        if (!keys.add(t.code() + "|" + t.from())) {
            errors.add(name + " : définie deux fois depuis le même état");
        }
        if (t.roles() == null || t.roles().isEmpty() || !ROLES.containsAll(t.roles())) {
            errors.add(name + " : roles doit lister au moins un rôle parmi " + ROLES);
        }
        for (String r : t.requiresOrEmpty()) {
            if (r == null || !CODE.matcher(r).matches()) {
                errors.add(name + " : champ requis invalide");
            }
        }
        for (String c : t.childrenInOrEmpty()) {
            if (c == null || !CODE.matcher(c).matches()) {
                errors.add(name + " : childrenIn invalide");
            }
        }
    }
}
