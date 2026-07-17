package com.mycompanyname.zero.notification.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Fallback {@link EmailSender} used when no mail host is configured ({@code spring.mail.host} empty).
 * It logs the message instead of delivering it, so local development and tests run without an SMTP
 * server.
 */
@Component
@ConditionalOnExpression("'${spring.mail.host:}' == ''")
@Slf4j
public class LoggingEmailSender implements EmailSender {

    @Override
    public void send(String to, String subject, String htmlBody) {
        log.info("[email:log] no mail host configured, not delivering — to={} subject='{}'", to, subject);
        if (log.isDebugEnabled()) {
            log.debug("[email:log] body:\n{}", htmlBody);
        }
    }
}
