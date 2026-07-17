/**
 * Outbound email API of the notification module. Exposed as a named interface so other modules
 * (e.g. identity account flows) can depend on {@code EmailSender}/{@code EmailTemplateService}
 * without reaching into notification internals.
 */
@NamedInterface("email")
package com.mycompanyname.zero.notification.email;

import org.springframework.modulith.NamedInterface;
