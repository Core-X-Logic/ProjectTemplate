package com.mycompanyname.zero.notification.email;

import com.mycompanyname.zero.settings.SettingManager;
import com.mycompanyname.zero.shared.domain.DomainException;
import com.mycompanyname.zero.shared.domain.ErrorCode;
import com.mycompanyname.zero.shared.tenant.TenantContext;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

/**
 * SMTP-backed {@link EmailSender}, active only when {@code spring.mail.host} is non-empty.
 *
 * <p>The envelope from-address and display name are resolved from the hierarchical settings module
 * ({@code App.Email.DefaultFromAddress} / {@code App.Email.DefaultFromDisplayName}, CONTRACT-phase2
 * §6) via {@link SettingManager}, honouring a tenant-level override for the current tenant. The
 * {@code @Value} bindings act only as a last-resort fallback if a setting cannot be resolved.
 */
@Component
@ConditionalOnExpression("'${spring.mail.host:}' != ''")
@Slf4j
public class SmtpEmailSender implements EmailSender {

    private static final String SETTING_FROM_ADDRESS = "App.Email.DefaultFromAddress";
    private static final String SETTING_FROM_DISPLAY_NAME = "App.Email.DefaultFromDisplayName";

    private final JavaMailSender mailSender;
    private final SettingManager settingManager;
    private final String fromAddressFallback;
    private final String fromDisplayNameFallback;

    public SmtpEmailSender(JavaMailSender mailSender,
                           SettingManager settingManager,
                           @Value("${zero.email.from-address:noreply@zero.local}") String fromAddressFallback,
                           @Value("${zero.email.from-display-name:Zero}") String fromDisplayNameFallback) {
        this.mailSender = mailSender;
        this.settingManager = settingManager;
        this.fromAddressFallback = fromAddressFallback;
        this.fromDisplayNameFallback = fromDisplayNameFallback;
    }

    @Override
    public void send(String to, String subject, String htmlBody) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(resolve(SETTING_FROM_ADDRESS, fromAddressFallback),
                    resolve(SETTING_FROM_DISPLAY_NAME, fromDisplayNameFallback));
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(message);
            log.debug("Sent email to {} with subject '{}'", to, subject);
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new DomainException(ErrorCode.INTERNAL, "Failed to send email to " + to);
        }
    }

    /** Settings take priority; the configured {@code @Value} fallback is used only if unresolved. */
    private String resolve(String settingName, String fallback) {
        try {
            String value = settingManager.getOrDefault(settingName, TenantContext.getTenantId(), null);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        } catch (RuntimeException ex) {
            log.debug("Falling back to configured value for {} ({})", settingName, ex.getMessage());
        }
        return fallback;
    }
}
