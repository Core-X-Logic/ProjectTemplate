package com.mycompanyname.zero.shared;

import com.mycompanyname.zero.shared.domain.DomainException;
import com.mycompanyname.zero.shared.domain.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;

/**
 * The single place any export in this application is allowed to fetch its rows (W5-3).
 *
 * <p>One mechanism for both exports on purpose. Each of them had grown its own unbounded fetch, and
 * a per-export bound would have meant the next one arrives with a third — or, more likely, with
 * none, since nothing about writing an export reminds anyone that the set is unbounded.
 *
 * <p><b>Why {@code maxRows + 1} and not a {@code count(*)}.</b> The probe is the whole design. A
 * separate count is a second query against the same predicate, is not consistent with the fetch
 * unless both run in one snapshot, and still leaves the fetch itself unbounded. Asking the database
 * for one row MORE than is allowed answers the only question that matters — "is there more?" — in
 * the same statement that returns the data, and it distinguishes a set that is EXACTLY at the limit
 * (legal, returned in full) from one that is over it (refused) without ever materialising the
 * oversized set. The bound is a {@code Pageable}, so it reaches the database as
 * {@code fetch first N rows only}; trimming a fully-read list in Java would defeat the point
 * entirely, since the memory has already been spent by then.
 *
 * <p><b>Why the caller supplies the fetch and not the query.</b> The two exports read different
 * things through different repositories — one pages ids and hydrates them in a second query
 * (identity's rows carry a collection that cannot be fetch-joined under a {@code LIMIT}), the other
 * runs a specification. The only thing they share is the bound, so the bound is what lives here.
 */
@Component
@RequiredArgsConstructor
public class BoundedExport {

    private final ExportLimitProperties properties;

    /**
     * Runs {@code fetcher} against a {@code Pageable} bounded to one row past the limit, and refuses
     * the export if that extra row came back.
     *
     * @param subject what is being exported, for the refusal message — a fixed constant at each call
     *                site, never anything the caller sent. The message tells the operator which
     *                export refused and what to do about it; echoing the rejected filter back would
     *                hand a caller a way to write their own input into someone else's log line.
     * @param order   the sort the export is expected to come back in. It is part of the bound, not a
     *                detail: "the first N rows" is only a meaningful answer to an ordered query, and
     *                an export that is refused today must be refused deterministically tomorrow.
     * @param fetcher issues exactly ONE query with the given {@code Pageable} applied by the
     *                database. Returning a {@code Page} here would add a count query on the very
     *                path this class exists to keep cheap.
     */
    public <T> List<T> fetch(String subject, Sort order, Function<Pageable, List<T>> fetcher) {
        // Validated at startup by ExportLimitProperties.validate(), not here: a request-time check
        // turns a misconfiguration into a 500 per call instead of a context that refuses to boot.
        int maxRows = properties.getMaxRows();
        Pageable probe = PageRequest.of(0, maxRows + 1, order);
        List<T> rows = fetcher.apply(probe);
        if (rows.size() > probe.getPageSize()) {
            // The fetcher was handed a Pageable and did not apply it — the bound never reached the
            // database, so the allocation this class exists to prevent has already happened by the
            // time we get here. Partial defence only: a fetcher that ignores the Pageable over a set
            // of exactly maxRows + 1 rows returns exactly maxRows + 1 and slips past this. What
            // actually measures the bound is ExportRowBoundIT, which reads the emitted SQL.
            throw new IllegalStateException(
                    "The " + subject + " export fetcher returned " + rows.size() + " rows for a "
                            + "Pageable of " + probe.getPageSize() + ": it did not apply the Pageable "
                            + "it was given, so the row bound never reached the database.");
        }
        if (rows.size() > maxRows) {
            throw new DomainException(ErrorCode.VALIDATION,
                    "The " + subject + " export matches more than " + maxRows + " rows, which is the "
                            + "maximum this endpoint returns (zero.export.max-rows). Narrow the "
                            + "filter — for example by a shorter date range — and try again.");
        }
        return rows;
    }
}
