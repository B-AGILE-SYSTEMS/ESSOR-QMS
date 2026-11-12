package com.essor.qms.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** E-mail par SMTP ({@code ESSOR_SMTP_*}) ; désactivé si aucun hôte n'est configuré. Aucune adresse dans les logs. */
@Component
class MailNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(MailNotifier.class);

    private final ObjectProvider<JavaMailSender> sender;
    private final boolean enabled;
    private final String from;
    private final String publicUrl;

    MailNotifier(ObjectProvider<JavaMailSender> sender,
                 @Value("${spring.mail.host:}") String host,
                 @Value("${essor.smtp.from:essor-qms@localhost}") String from,
                 @Value("${essor.security.post-logout-redirect-uri:}") String publicUrl) {
        this.sender = sender;
        this.enabled = StringUtils.hasText(host);
        this.from = from;
        this.publicUrl = publicUrl;
    }

    boolean isEnabled() {
        return enabled && sender.getIfAvailable() != null;
    }

    void send(String to, String title) {
        JavaMailSender mail = sender.getIfAvailable();
        if (!enabled || mail == null) {
            return;
        }
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(from);
        m.setTo(to);
        m.setSubject("[ESSOR QMS] " + title);
        m.setText(title + "\n\n" + publicUrl);
        try {
            mail.send(m);
        } catch (MailException e) {
            LOG.warn("Échec d'envoi d'une notification par e-mail : {}", e.getClass().getSimpleName());
        }
    }
}
