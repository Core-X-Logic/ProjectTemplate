package com.mycompanyname.zero.notification.email;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.HashMap;
import java.util.Map;

/**
 * Renders the HTML bodies of the transactional emails from Thymeleaf templates under
 * {@code templates/email/}. The rendered string is handed to an {@link EmailSender}.
 */
@Service
@RequiredArgsConstructor
public class EmailTemplateService {

    private final SpringTemplateEngine templateEngine;

    @Value("${zero.email.app-name:Zero Platform}")
    private String appName;

    // Varsayilan artik application.yml'de ve 5173 (Vite). Buradaki fallback yalnizca
    // property hic tanimlanmamissa devreye girer; 4200 (Angular) kalintisiydi.
    @Value("${zero.app.base-url:http://localhost:5173}")
    private String baseUrl;

    /**
     * Renders an arbitrary email template. {@code appName} is always injected; caller variables are
     * added on top.
     */
    public String render(String templateName, Map<String, Object> variables) {
        Context context = new Context();
        context.setVariable("appName", appName);
        variables.forEach(context::setVariable);
        return templateEngine.process(templateName, context);
    }

    public String welcome(String name, String username) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("name", nullToEmpty(name));
        variables.put("username", username);
        variables.put("loginUrl", baseUrl + "/login");
        return render("email/welcome", variables);
    }

    public String passwordReset(String name, String resetCode) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("name", nullToEmpty(name));
        variables.put("resetCode", resetCode);
        variables.put("resetUrl", baseUrl + "/account/reset-password?code=" + resetCode);
        return render("email/password-reset", variables);
    }

    public String emailConfirmation(String name, String confirmationCode) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("name", nullToEmpty(name));
        variables.put("confirmationCode", confirmationCode);
        variables.put("confirmationUrl", baseUrl + "/account/confirm-email?code=" + confirmationCode);
        return render("email/email-confirmation", variables);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
