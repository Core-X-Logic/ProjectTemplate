package com.mycompanyname.zero.shared;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The bound on how much of a table one export may materialise (W5-3).
 *
 * <p>Separate from {@code config.RequestLimitProperties}, which answers "how large may a request
 * be?". This
 * answers the opposite direction: how large may the RESPONSE this server builds for itself be. The
 * two exports here had no such bound at all — {@code /api/users/export} read the caller's entire
 * scope and resolved every user's roles, {@code /api/audit-logs/export} read every row matching the
 * filter, and both then held the whole result AND an Apache POI workbook of it in the heap at once.
 * A request body cannot reach 1 MB without someone sending 1 MB; an export reaches whatever size the
 * table happens to be, so the caller does not have to be hostile for it to be fatal — it only has to
 * be late in the product's life.
 *
 * @see BoundedExport
 */
@Component
@ConfigurationProperties(prefix = "zero.export")
@Getter
@Setter
public class ExportLimitProperties {

    /**
     * Largest number of rows any single export may return.
     *
     * <p>10 000 because that is roughly where an xlsx stops being a thing an operator opens and
     * starts being a thing they should have queried: POI's in-memory workbook costs on the order of
     * a kilobyte per row, so ten thousand is a few tens of MB of transient heap per concurrent
     * export, and one Tomcat thread can still finish it. It is not a statement about what the
     * database can return — Postgres would happily stream ten million — it is the point past which
     * the answer belongs in a report, not in a synchronous download.
     *
     * <p>Passing it REFUSES the export rather than truncating it. Truncation was considered and
     * rejected: a silently shortened export is a file that looks complete, reconciles against
     * nothing, and is discovered to be wrong only by whoever trusted it. A 400 that names the limit
     * is recoverable; a wrong spreadsheet in someone's audit evidence is not.
     *
     * <p>Raising this is a decision about heap, not about product scope. Two exports of a raised
     * limit run concurrently before any of it is written to the socket.
     */
    private int maxRows = 10000;

    /**
     * Rejected at STARTUP, not at request time.
     *
     * <p>This check used to live in {@code BoundedExport.fetch}, where it was reached once per
     * export call. {@code EXPORT_MAX_ROWS=0} therefore produced a deployment that booted green,
     * passed every probe, and then answered every single export with an HTTP 500 and an
     * {@code log.error("Unhandled exception")} line — for as long as the deployment ran. That is the
     * shape this repository keeps paying for: a misconfiguration that is invisible where it is made
     * and expensive where it is discovered, plus a steady stream of ERROR lines that buries the next
     * real fault. A context that refuses to start names the property once, at the moment someone can
     * still fix it.
     *
     * <p>Not clamped to 1 either: silently rewriting a configured value is how a deployment ends up
     * with an effective limit nobody chose.
     *
     * <p>Same shape as {@code config.CorsProperties.validate()} — {@code @PostConstruct}, {@code
     * IllegalStateException}, and a message that names the property, the offending value and the
     * consequence.
     */
    @PostConstruct
    void validate() {
        if (maxRows < 1) {
            throw new IllegalStateException(
                    "zero.export.max-rows must be at least 1, but is configured as " + maxRows
                            + ". A non-positive limit does not disable the exports — it leaves "
                            + "/api/users/export and /api/audit-logs/export answering every request "
                            + "with an HTTP 500 and an ERROR line. Set EXPORT_MAX_ROWS to the largest "
                            + "number of rows one download may return (the default is 10000), or "
                            + "remove the override.");
        }
    }
}
