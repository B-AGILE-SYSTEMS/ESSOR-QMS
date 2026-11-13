package com.essor.qms.traceability;

import com.essor.qms.shared.ApiException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** API M3b : données de base, lots et généalogie, rappel, plans de contrôle, contrôles, SPC, connecteur webhook. */
@RestController
class TraceabilityController {

    private static final long MAX_IMPORT_BYTES = 5L * 1024 * 1024;

    private final MasterDataService masterData;
    private final LotService lots;
    private final RecallService recalls;
    private final ControlPlanService plans;
    private final InspectionService inspections;
    private final WebhookService webhooks;

    TraceabilityController(MasterDataService masterData, LotService lots, RecallService recalls,
                           ControlPlanService plans, InspectionService inspections, WebhookService webhooks) {
        this.masterData = masterData;
        this.lots = lots;
        this.recalls = recalls;
        this.plans = plans;
        this.inspections = inspections;
        this.webhooks = webhooks;
    }

    // ---------------------------------------------------------------- données de base

    @GetMapping("/api/master-data/articles")
    List<MasterDataService.Article> articles(@RequestParam(required = false) String q) {
        return masterData.articles(q);
    }

    @PostMapping("/api/master-data/articles")
    @ResponseStatus(HttpStatus.CREATED)
    MasterDataService.Article createArticle(@RequestBody MasterDataService.Article body) {
        return masterData.createArticle(body);
    }

    @GetMapping("/api/master-data/partners")
    List<MasterDataService.Partner> partners(@RequestParam(required = false) String type,
                                             @RequestParam(required = false) String q) {
        return masterData.partners(type, q);
    }

    @PostMapping("/api/master-data/partners")
    @ResponseStatus(HttpStatus.CREATED)
    MasterDataService.Partner createPartner(@RequestBody MasterDataService.Partner body) {
        return masterData.createPartner(body);
    }

    @PostMapping(path = "/api/master-data/{kind}/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    MasterDataService.ImportReport importFile(@PathVariable String kind, @RequestPart("file") MultipartFile file,
                                              @RequestParam(defaultValue = "false") boolean dryRun)
            throws IOException {
        if (file.getSize() > MAX_IMPORT_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "too_large", "Fichier trop volumineux (5 Mo maximum)");
        }
        return switch (kind) {
            case "articles" -> masterData.importArticles(file.getBytes(), dryRun);
            case "partners" -> masterData.importPartners(file.getBytes(), dryRun);
            default -> throw ApiException.notFound("Type de données de base");
        };
    }

    // ---------------------------------------------------------------- lots

    @PostMapping("/api/lots")
    @ResponseStatus(HttpStatus.CREATED)
    LotService.Lot createLot(@RequestBody LotService.CreateLot body) {
        return lots.create(body);
    }

    @GetMapping("/api/lots")
    List<LotService.Lot> listLots(@RequestParam(required = false) String q,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) UUID articleId) {
        return lots.list(q, status, articleId);
    }

    @GetMapping("/api/lots/{id}")
    LotService.LotDetail lot(@PathVariable UUID id) {
        return lots.detail(id);
    }

    @PostMapping("/api/lots/{id}/status")
    LotService.Lot status(@PathVariable UUID id, @RequestBody LotService.StatusChange body) {
        return lots.changeStatus(id, body);
    }

    @PostMapping("/api/lots/{id}/links")
    @ResponseStatus(HttpStatus.CREATED)
    LotService.Link link(@PathVariable UUID id, @RequestBody LotService.CreateLink body) {
        return lots.addLink(id, body);
    }

    @GetMapping("/api/lots/{id}/trace")
    Genealogy.Node trace(@PathVariable UUID id, @RequestParam(defaultValue = "down") String direction,
                         @RequestParam(required = false) Integer depth) {
        return recalls.trace(id, direction, depth);
    }

    @GetMapping("/api/lots/{id}/recall")
    Genealogy.Recall recall(@PathVariable UUID id) {
        return recalls.recall(id);
    }

    @PostMapping("/api/lots/{id}/recall/block")
    RecallService.BlockResult block(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        return recalls.block(id, body == null ? null : body.get("reason"));
    }

    @GetMapping("/api/lots/{id}/recall.pdf")
    ResponseEntity<byte[]> recallPdf(@PathVariable UUID id) {
        byte[] pdf = recalls.pdf(id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(recalls.fileName(id)).build().toString())
                .body(pdf);
    }

    // ---------------------------------------------------------------- plans et contrôles

    @GetMapping("/api/control-plans")
    List<ControlPlanService.Plan> plans(@RequestParam(required = false) String point,
                                        @RequestParam(defaultValue = "false") boolean all) {
        return plans.list(point, all);
    }

    @GetMapping("/api/control-plans/{id}")
    ControlPlanService.Plan plan(@PathVariable UUID id) {
        return plans.get(id);
    }

    @PostMapping("/api/control-plans")
    @ResponseStatus(HttpStatus.CREATED)
    ControlPlanService.Plan createPlan(@RequestBody ControlPlanService.PlanInput body) {
        return plans.create(body);
    }

    @PutMapping("/api/control-plans/{id}")
    ControlPlanService.Plan updatePlan(@PathVariable UUID id, @RequestBody ControlPlanService.PlanInput body) {
        return plans.update(id, body);
    }

    @PostMapping("/api/inspections")
    @ResponseStatus(HttpStatus.CREATED)
    InspectionService.Inspection createInspection(@RequestBody InspectionService.CreateInspection body) {
        return inspections.create(body);
    }

    @GetMapping("/api/inspections")
    List<InspectionService.Inspection> listInspections(@RequestParam(required = false) String status,
                                                       @RequestParam(required = false) UUID lotId,
                                                       @RequestParam(required = false) String point) {
        return inspections.list(status, lotId, point);
    }

    @GetMapping("/api/inspections/spc")
    Map<String, Object> spc(@RequestParam UUID controlPlanId, @RequestParam String characteristic,
                            @RequestParam(defaultValue = "50") int limit) {
        return inspections.spc(controlPlanId, characteristic, limit);
    }

    @GetMapping("/api/inspections/{id}")
    InspectionService.Inspection inspection(@PathVariable UUID id) {
        return inspections.get(id);
    }

    @PutMapping("/api/inspections/{id}/results")
    InspectionService.Inspection results(@PathVariable UUID id,
                                         @RequestBody List<InspectionService.ResultInput> body) {
        return inspections.saveResults(id, body);
    }

    @PostMapping("/api/inspections/{id}/decision")
    InspectionService.Inspection decision(@PathVariable UUID id, @RequestBody InspectionService.Decision body) {
        return inspections.decide(id, body);
    }

    // ---------------------------------------------------------------- connecteur webhook

    @GetMapping("/api/connectors/webhook")
    WebhookService.Config webhook() {
        return webhooks.config();
    }

    @PutMapping("/api/connectors/webhook")
    WebhookService.Config saveWebhook(@RequestBody WebhookService.ConfigInput body) {
        return webhooks.save(body);
    }

    @GetMapping("/api/connectors/webhook/deliveries")
    List<WebhookService.Delivery> deliveries(@RequestParam(defaultValue = "50") int limit) {
        return webhooks.deliveries(limit);
    }
}
