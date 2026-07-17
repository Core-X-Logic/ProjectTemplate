package com.mycompanyname.zero.notification.email;

/**
 * Abstraction over outbound email. Exactly one implementation is active at runtime:
 * {@code SmtpEmailSender} when {@code spring.mail.host} is configured, otherwise
 * {@code LoggingEmailSender}.
 */
public interface EmailSender {

    /**
     * Sends an HTML email. The {@code from} address is resolved by the implementation.
     *
     * @param to       recipient address
     * @param subject  message subject
     * @param htmlBody rendered HTML body
     */
    void send(String to, String subject, String htmlBody);
}
