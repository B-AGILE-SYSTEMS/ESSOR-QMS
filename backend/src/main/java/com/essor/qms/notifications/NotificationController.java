package com.essor.qms.notifications;

import com.essor.qms.notifications.NotificationRules.Rule;
import com.essor.qms.notifications.NotificationService.Notification;
import com.essor.qms.shared.ApiException;
import com.essor.qms.shared.ExecutionContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
@Validated
class NotificationController {

    record Page(List<Notification> items, long total, long unread, int page, int size) {
    }

    record RuleInput(@NotNull NotificationService.EventType event, @NotNull NotificationService.Channel channel,
                     boolean enabled) {
    }

    private final JdbcClient jdbc;
    private final NotificationRules rules;
    private final SseHub sse;

    NotificationController(JdbcClient jdbc, NotificationRules rules, SseHub sse) {
        this.jdbc = jdbc;
        this.rules = rules;
        this.sse = sse;
    }

    @GetMapping("/notifications")
    @Transactional(readOnly = true)
    Page list(@RequestParam(required = false) Boolean unread,
              @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
              @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        String filter = Boolean.TRUE.equals(unread) ? " and not read" : "";
        List<Notification> items = jdbc.sql("""
                select id, type, title, record_id, link, read, created_at,
                       (select r.type_code from record r where r.id = notification.record_id) as record_type
                from notification
                where recipient_id = app_current_user()""" + filter + """
                 order by created_at desc, id limit :size offset :offset
                """).param("size", size).param("offset", (long) page * size).query(Notification.class).list();
        long total = jdbc.sql("select count(*) from notification where recipient_id = app_current_user()" + filter)
                .query(Long.class).single();
        long unreadCount = jdbc.sql("select count(*) from notification where recipient_id = app_current_user() and not read")
                .query(Long.class).single();
        return new Page(items, total, unreadCount, page, size);
    }

    @PostMapping("/notifications/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void read(@PathVariable UUID id) {
        int n = jdbc.sql("update notification set read = true where id = :id and recipient_id = app_current_user()")
                .param("id", id).update();
        if (n == 0) {
            throw ApiException.notFound("Notification");
        }
    }

    @PostMapping("/notifications/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void readAll() {
        jdbc.sql("update notification set read = true where recipient_id = app_current_user() and not read").update();
    }

    @GetMapping(path = "/notifications/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream() {
        UUID user = ExecutionContext.userId();
        if (user == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "Authentification requise");
        }
        return sse.open(user);
    }

    @GetMapping("/notification-rules")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    List<Rule> getRules() {
        return rules.all();
    }

    @PutMapping("/notification-rules")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    List<Rule> putRules(@RequestBody @Size(max = 50) List<@Valid RuleInput> body) {
        return rules.replace(body.stream().map(r -> new Rule(r.event(), r.channel(), r.enabled())).toList());
    }
}
