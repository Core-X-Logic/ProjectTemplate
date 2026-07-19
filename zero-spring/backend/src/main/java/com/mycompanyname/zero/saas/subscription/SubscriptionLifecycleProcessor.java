package com.mycompanyname.zero.saas.subscription;

import com.mycompanyname.zero.saas.edition.Edition;
import com.mycompanyname.zero.saas.edition.EditionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Advances subscriptions whose deadlines have passed (rows S6-S10 of the transition table on
 * {@link SubscriptionStatus}).
 *
 * <p><b>What this class does and does not decide.</b> It only selects the subscriptions that are
 * due and names the reason; the resulting status, the guard and the {@code subscription_events} row
 * all come from {@link SubscriptionService}. That is what keeps the job from being a second,
 * divergent implementation of the state machine: a job that sets status flags itself will sooner or
 * later disagree with the domain rules, and the disagreement surfaces as customers wrongly cut off.
 *
 * <p><b>Trial has no grace (S6).</b> A trial goes straight to {@code EXPIRED}; the grace window
 * applies to paid periods only. Granting grace to trials in the job's pre-filter but not in the
 * domain is exactly the kind of divergence described above.
 *
 * <p><b>Ordering matters.</b> The passes run period → grace → downgrade, so a subscription whose
 * edition grants no grace can expire and land on its free downgrade edition within a single run.
 *
 * <p>Each subscription is advanced in its own transaction (the service is {@code @Transactional} and
 * this method is not), so one bad row cannot abort the whole batch.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionLifecycleProcessor {

    public static final String ACTOR = "lifecycle-job";
    public static final String REASON_TRIAL_ENDED = "TRIAL_ENDED";
    public static final String REASON_PERIOD_ENDED = "PERIOD_ENDED";
    public static final String REASON_GRACE_ENDED = "GRACE_ENDED";

    private final SubscriptionRepository subscriptionRepository;
    private final EditionRepository editionRepository;
    private final SubscriptionService subscriptionService;
    private final Clock clock;

    /** Processes everything due at the current {@link Clock} instant. */
    public int processDueSubscriptions() {
        return processDueSubscriptions(clock.instant());
    }

    /**
     * Processes everything due at {@code asOf}.
     *
     * @return the number of subscriptions whose status actually changed
     */
    public int processDueSubscriptions(Instant asOf) {
        int changed = 0;
        changed += expireElapsedTrials(asOf);
        changed += endElapsedPeriods(asOf);
        changed += expireElapsedGraceWindows(asOf);
        changed += downgradeExpiredSubscriptions();
        if (changed > 0) {
            log.info("Subscription lifecycle run at {} advanced {} subscription(s)", asOf, changed);
        }
        return changed;
    }

    /** S6: {@code TRIALING -> EXPIRED}. A trial never enters the grace window. */
    private int expireElapsedTrials(Instant asOf) {
        int changed = 0;
        List<Subscription> due = subscriptionRepository
                .findByStatusAndTrialEndAtLessThanEqualOrderByIdAsc(SubscriptionStatus.TRIALING, asOf);
        for (Subscription subscription : due) {
            changed += advance(subscription, SubscriptionStatus.EXPIRED, REASON_TRIAL_ENDED);
        }
        return changed;
    }

    /** S7/S8: {@code ACTIVE -> GRACE} when the edition grants one, otherwise straight to EXPIRED. */
    private int endElapsedPeriods(Instant asOf) {
        int changed = 0;
        List<Subscription> due = subscriptionRepository
                .findByStatusAndCurrentPeriodEndAtLessThanEqualOrderByIdAsc(SubscriptionStatus.ACTIVE, asOf);
        for (Subscription subscription : due) {
            int graceDays = editionRepository.findById(subscription.getEditionId())
                    .map(Edition::getGraceDayCount)
                    .orElse(0);
            SubscriptionStatus target = graceDays > 0
                    ? SubscriptionStatus.GRACE
                    : SubscriptionStatus.EXPIRED;
            changed += advance(subscription, target, REASON_PERIOD_ENDED);
        }
        return changed;
    }

    /** S9: {@code GRACE -> EXPIRED}. */
    private int expireElapsedGraceWindows(Instant asOf) {
        int changed = 0;
        List<Subscription> due = subscriptionRepository
                .findByStatusAndGraceEndAtLessThanEqualOrderByIdAsc(SubscriptionStatus.GRACE, asOf);
        for (Subscription subscription : due) {
            changed += advance(subscription, SubscriptionStatus.EXPIRED, REASON_GRACE_ENDED);
        }
        return changed;
    }

    /** S10: an expired subscription falls back to its edition's free downgrade target, if any. */
    private int downgradeExpiredSubscriptions() {
        int changed = 0;
        for (Subscription subscription : subscriptionRepository
                .findByStatusOrderByIdAsc(SubscriptionStatus.EXPIRED)) {
            try {
                Optional<Subscription> downgraded =
                        subscriptionService.downgradeToExpiringEdition(subscription.getTenantId(), ACTOR);
                if (downgraded.isPresent()) {
                    changed++;
                }
            } catch (RuntimeException ex) {
                log.error("Downgrade of the expired subscription of tenant {} failed: {}",
                        subscription.getTenantId(), ex.getMessage(), ex);
            }
        }
        return changed;
    }

    private int advance(Subscription subscription, SubscriptionStatus target, String reason) {
        try {
            subscriptionService.transition(subscription.getTenantId(), target, reason, ACTOR);
            return 1;
        } catch (RuntimeException ex) {
            log.error("Lifecycle transition to {} for tenant {} failed: {}",
                    target, subscription.getTenantId(), ex.getMessage(), ex);
            return 0;
        }
    }
}
