package com.essor.qms.partners;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** API évaluation clients et fournisseurs (contrat M4). */
@RestController
class PartnerEvaluationController {

    private final PartnerEvaluationService service;
    private final EvaluationGrids grids;

    PartnerEvaluationController(PartnerEvaluationService service, EvaluationGrids grids) {
        this.service = service;
        this.grids = grids;
    }

    @GetMapping("/api/evaluation-grids")
    @Transactional
    List<EvaluationGrids.Grid> grids() {
        return grids.all();
    }

    @PostMapping("/api/evaluation-grids")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    EvaluationGrids.Grid createGrid(@RequestBody EvaluationGrids.GridInput in) {
        return grids.put(in.code(), in);
    }

    @PutMapping("/api/evaluation-grids/{code}")
    @Transactional
    EvaluationGrids.Grid putGrid(@PathVariable String code, @RequestBody EvaluationGrids.GridInput in) {
        return grids.put(code, in);
    }

    @GetMapping("/api/partners/{id}/evaluations")
    List<Map<String, Object>> evaluations(@PathVariable UUID id) {
        return service.evaluations(id);
    }

    @PostMapping("/api/partners/{id}/evaluations")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> evaluate(@PathVariable UUID id, @RequestBody PartnerEvaluationService.EvaluationInput in) {
        return service.evaluate(id, in);
    }

    @GetMapping("/api/partners/{id}/scorecard")
    Map<String, Object> scorecard(@PathVariable UUID id) {
        return service.scorecard(id);
    }

    @PostMapping("/api/evaluation-campaigns")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> campaign(@RequestBody PartnerEvaluationService.CampaignInput in) {
        return service.campaign(in);
    }

    /** Évaluations à faire (campagnes) ou toutes : {@code ?status=a_faire|terminee}. */
    @GetMapping("/api/partner-evaluations")
    List<Map<String, Object>> todo(@RequestParam(required = false) String status) {
        return service.todo(status);
    }
}
