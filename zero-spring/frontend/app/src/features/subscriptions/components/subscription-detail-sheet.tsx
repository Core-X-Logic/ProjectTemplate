import { FormattedMessage, useIntl } from 'react-intl';
import { History } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet';
import { Skeleton } from '@/components/ui/skeleton';
import { DataEmpty, DataError } from '@/components/common/data-state';
import { useSubscription } from '../hooks';
import { toSubscriptionStatus } from '../types';
import type { SubscriptionDto, SubscriptionEventDto } from '../types';

/**
 * Host-side subscription detail: the full state snapshot plus the DOMAIN EVENT TRAIL
 * (`subscription_events` — every lifecycle transition with reason/actor/time). ASP.NET Zero parity:
 * the subscription-management detail view; here the history is first-class instead of implicit.
 *
 * Reasons are backend constants; each gets an i18n label and falls back to the raw constant for a
 * reason this catalogue does not know yet (forward-compatible with new backend reasons).
 */

const KNOWN_REASONS = new Set([
  'PROVISIONED',
  'EDITION_ASSIGNED',
  'EDITION_CHANGED',
  'ACTIVATED',
  'CANCELLED',
  'DOWNGRADED',
  'TRIAL_ENDED',
  'PERIOD_ENDED',
  'GRACE_ENDED',
  'EXPIRY_NOTICE',
]);

function StatusChip({ status }: { status?: string }) {
  const known = toSubscriptionStatus(status);
  return (
    <Badge
      variant={
        known === 'ACTIVE'
          ? 'success'
          : known === 'TRIALING' || known === 'GRACE'
            ? 'warning'
            : known === 'EXPIRED' || known === 'CANCELLED'
              ? 'destructive'
              : 'secondary'
      }
      appearance="light"
      size="sm"
    >
      <FormattedMessage
        id={
          known ? `subscriptions.status.${known}` : 'subscriptions.status.unknown'
        }
      />
    </Badge>
  );
}

function InfoRow({ labelId, children }: { labelId: string; children: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-4 py-1.5">
      <span className="text-sm text-muted-foreground">
        <FormattedMessage id={labelId} />
      </span>
      <span className="text-sm font-medium text-foreground">{children}</span>
    </div>
  );
}

function EventRow({ event }: { event: SubscriptionEventDto }) {
  const intl = useIntl();
  const reason = event.reason ?? '';
  return (
    <li className="relative pb-4 pl-5 last:pb-0">
      <span
        aria-hidden="true"
        className="absolute left-0 top-1.5 size-2 rounded-full bg-primary/70"
      />
      <span
        aria-hidden="true"
        className="absolute bottom-0 left-[3px] top-4 w-px bg-border"
      />
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-sm font-medium text-foreground">
          {KNOWN_REASONS.has(reason) ? (
            <FormattedMessage id={`subscriptions.detail.reason.${reason}`} />
          ) : (
            reason || '—'
          )}
        </span>
        {event.fromStatus && event.fromStatus !== event.toStatus ? (
          <span className="flex items-center gap-1 text-xs text-muted-foreground">
            <StatusChip status={event.fromStatus} />
            →
            <StatusChip status={event.toStatus} />
          </span>
        ) : (
          <StatusChip status={event.toStatus} />
        )}
      </div>
      <p className="mt-0.5 text-xs text-muted-foreground">
        {event.occurredAt
          ? intl.formatDate(event.occurredAt, {
              dateStyle: 'medium',
              timeStyle: 'short',
            })
          : '—'}
        {' · '}
        {event.actor ?? '—'}
      </p>
    </li>
  );
}

export interface SubscriptionDetailSheetProps {
  subscription: SubscriptionDto | null;
  onOpenChange: (open: boolean) => void;
}

export function SubscriptionDetailSheet({
  subscription,
  onOpenChange,
}: SubscriptionDetailSheetProps) {
  const intl = useIntl();
  const tenantId = subscription?.tenantId ?? undefined;
  const query = useSubscription(tenantId);

  const detail = query.data?.subscription;
  const events = query.data?.events ?? [];

  return (
    <Sheet open={subscription !== null} onOpenChange={onOpenChange}>
      <SheetContent className="flex w-full flex-col gap-0 sm:max-w-md">
        <SheetHeader>
          <SheetTitle className="flex items-center gap-2">
            {subscription?.tenantName ?? subscription?.tenantId ?? '—'}
            <StatusChip status={detail?.status ?? subscription?.status} />
          </SheetTitle>
          <SheetDescription>
            <FormattedMessage id="subscriptions.detail.description" />
          </SheetDescription>
        </SheetHeader>

        <div className="flex-1 overflow-y-auto px-4 pb-6">
          {query.isLoading ? (
            <div className="flex flex-col gap-3 pt-2" aria-hidden="true">
              <Skeleton className="h-5 w-2/3" />
              <Skeleton className="h-5 w-1/2" />
              <Skeleton className="h-40 w-full" />
            </div>
          ) : query.isError ? (
            <DataError
              message={intl.formatMessage({ id: 'subscriptions.detail.error' })}
              onRetry={() => void query.refetch()}
            />
          ) : (
            <>
              <div className="divide-y divide-border rounded-lg border border-border px-3 py-1.5">
                <InfoRow labelId="subscriptions.columns.edition">
                  {detail?.editionDisplayName ?? detail?.editionName ?? '—'}
                </InfoRow>
                <InfoRow labelId="subscriptions.columns.billingPeriod">
                  <FormattedMessage
                    id={
                      detail?.billingPeriod === 'MONTHLY' ||
                      detail?.billingPeriod === 'ANNUAL'
                        ? `subscriptions.period.${detail.billingPeriod}`
                        : 'subscriptions.period.none'
                    }
                  />
                </InfoRow>
                <InfoRow labelId="subscriptions.detail.price">
                  {detail?.priceAmount != null
                    ? `${detail.priceAmount} ${detail.priceCurrency ?? ''}`
                    : '—'}
                </InfoRow>
                <InfoRow labelId="subscriptions.columns.currentPeriodEndAt">
                  {detail?.currentPeriodEndAt
                    ? intl.formatDate(detail.currentPeriodEndAt, {
                        dateStyle: 'medium',
                      })
                    : '—'}
                </InfoRow>
                {detail?.trialEndAt ? (
                  <InfoRow labelId="subscriptions.detail.trialEndAt">
                    {intl.formatDate(detail.trialEndAt, { dateStyle: 'medium' })}
                  </InfoRow>
                ) : null}
                {detail?.cancelledAt ? (
                  <InfoRow labelId="subscriptions.detail.cancelledAt">
                    {intl.formatDate(detail.cancelledAt, { dateStyle: 'medium' })}
                  </InfoRow>
                ) : null}
              </div>

              <h3 className="mb-3 mt-6 flex items-center gap-1.5 text-sm font-semibold text-foreground">
                <History aria-hidden="true" className="size-4" />
                <FormattedMessage id="subscriptions.detail.history" />
              </h3>
              {events.length === 0 ? (
                <DataEmpty
                  title={intl.formatMessage({
                    id: 'subscriptions.detail.historyEmpty',
                  })}
                />
              ) : (
                <ul className="flex flex-col">
                  {[...events].reverse().map((event) => (
                    <EventRow key={event.id} event={event} />
                  ))}
                </ul>
              )}
            </>
          )}
        </div>
      </SheetContent>
    </Sheet>
  );
}
