import { useEffect, useMemo, useState } from 'react';
import { LoaderCircle } from 'lucide-react';
import { FormattedMessage, useIntl } from 'react-intl';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { useEditions } from '@/features/editions/hooks';
import { useAssignEdition } from '../hooks';
import {
  BILLING_PERIODS,
  type BillingPeriod,
  type SubscriptionDto,
} from '../types';

/**
 * Package assignment dialog (CONTRACT-phase5.md §A.2).
 *
 * Sends `PUT /api/subscriptions/{tenantId}/edition` with the chosen edition,
 * billing period and trial flag. The price snapshot is taken server-side, so no
 * money arithmetic happens here.
 *
 * Business rules the backend owns (and rejects with 400) are only *surfaced*
 * here, never re-implemented: a free edition cannot start as a trial, so the
 * trial checkbox disables itself once a free edition is selected.
 *
 * The selectors are native `<select>` elements — no extra dependency, keyboard
 * and screen-reader accessible out of the box, and directly drivable from the
 * behaviour tests.
 */

export interface AssignEditionDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Subscription row being edited; supplies the tenant id + current edition. */
  subscription: SubscriptionDto | null;
}

const SELECT_CLASS =
  'flex h-9 w-full items-center rounded-md border border-input bg-background px-3 py-1 text-sm text-foreground shadow-xs shadow-black/5 outline-none focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/30 disabled:cursor-not-allowed disabled:opacity-50';

export function AssignEditionDialog({
  open,
  onOpenChange,
  subscription,
}: AssignEditionDialogProps) {
  const intl = useIntl();

  // The dialog stays mounted (closed) on the subscriptions list, so the edition
  // list is only fetched once it is actually opened.
  const {
    data: editionPage,
    isLoading,
    isError,
  } = useEditions({ page: 0, size: 100 }, { enabled: open });
  const assignEdition = useAssignEdition();

  const editions = useMemo(
    () => (editionPage?.content ?? []).filter((edition) => edition.active),
    [editionPage],
  );

  const [editionId, setEditionId] = useState('');
  const [billingPeriod, setBillingPeriod] = useState<BillingPeriod>('MONTHLY');
  const [trial, setTrial] = useState(false);
  const [touched, setTouched] = useState(false);

  // Re-seed each time the dialog opens for a (possibly different) tenant.
  useEffect(() => {
    if (open) {
      setEditionId(
        subscription?.editionId !== undefined
          ? String(subscription.editionId)
          : '',
      );
      setBillingPeriod(
        subscription?.billingPeriod === 'ANNUAL' ? 'ANNUAL' : 'MONTHLY',
      );
      setTrial(false);
      setTouched(false);
    }
  }, [open, subscription]);

  const selectedEdition = editions.find(
    (edition) => String(edition.id) === editionId,
  );
  // Free editions accept no trial (backend returns 400); keep the box honest.
  const trialAllowed = selectedEdition ? selectedEdition.free !== true : false;

  const tenantLabel =
    subscription?.tenantName ??
    (subscription?.tenantId !== undefined ? String(subscription.tenantId) : '');

  const submit = () => {
    setTouched(true);
    const tenantId = subscription?.tenantId;
    if (tenantId === undefined || editionId === '') {
      return;
    }

    assignEdition.mutate(
      {
        tenantId,
        body: {
          editionId: Number(editionId),
          billingPeriod,
          trial: trialAllowed ? trial : false,
        },
      },
      { onSuccess: () => onOpenChange(false) },
    );
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>
            <FormattedMessage id="subscriptions.assign.title" />
          </DialogTitle>
          <DialogDescription>
            <FormattedMessage
              id="subscriptions.assign.description"
              values={{ tenant: tenantLabel }}
            />
          </DialogDescription>
        </DialogHeader>

        {isLoading ? (
          <div className="flex flex-col gap-3 py-2">
            <Skeleton className="h-9 w-full" />
            <Skeleton className="h-9 w-full" />
          </div>
        ) : isError ? (
          <p role="alert" className="py-2 text-sm text-destructive">
            <FormattedMessage id="subscriptions.assign.loadError" />
          </p>
        ) : editions.length === 0 ? (
          <p className="py-2 text-sm text-muted-foreground">
            <FormattedMessage id="subscriptions.assign.empty" />
          </p>
        ) : (
          <div className="flex flex-col gap-4 py-2">
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="assign-edition">
                <FormattedMessage id="subscriptions.assign.edition" />
              </Label>
              <select
                id="assign-edition"
                className={SELECT_CLASS}
                value={editionId}
                onChange={(event) => setEditionId(event.target.value)}
              >
                <option value="">
                  {intl.formatMessage({
                    id: 'subscriptions.assign.editionPlaceholder',
                  })}
                </option>
                {editions.map((edition) => (
                  <option key={edition.id} value={String(edition.id)}>
                    {edition.displayName || edition.name}
                  </option>
                ))}
              </select>
              {touched && editionId === '' ? (
                <p role="alert" className="text-xs text-destructive">
                  <FormattedMessage id="subscriptions.assign.required" />
                </p>
              ) : null}
            </div>

            <div className="flex flex-col gap-1.5">
              <Label htmlFor="assign-billing-period">
                <FormattedMessage id="subscriptions.assign.billingPeriod" />
              </Label>
              <select
                id="assign-billing-period"
                className={SELECT_CLASS}
                value={billingPeriod}
                onChange={(event) =>
                  setBillingPeriod(event.target.value as BillingPeriod)
                }
              >
                {BILLING_PERIODS.map((period) => (
                  <option key={period} value={period}>
                    {intl.formatMessage({
                      id: `subscriptions.period.${period}`,
                    })}
                  </option>
                ))}
              </select>
            </div>

            <div className="flex flex-row items-start gap-2.5">
              <Checkbox
                id="assign-trial"
                checked={trial}
                disabled={!trialAllowed}
                onCheckedChange={(checked) => setTrial(checked === true)}
              />
              <div className="flex flex-col gap-1">
                <Label htmlFor="assign-trial">
                  <FormattedMessage id="subscriptions.assign.trial" />
                </Label>
                <p className="text-xs text-muted-foreground">
                  <FormattedMessage id="subscriptions.assign.trialHint" />
                </p>
              </div>
            </div>
          </div>
        )}

        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            onClick={() => onOpenChange(false)}
          >
            <FormattedMessage id="subscriptions.assign.cancel" />
          </Button>
          <Button
            type="button"
            onClick={submit}
            disabled={assignEdition.isPending || editions.length === 0}
          >
            {assignEdition.isPending && (
              <LoaderCircle className="size-4 animate-spin" />
            )}
            <FormattedMessage
              id={
                assignEdition.isPending
                  ? 'subscriptions.assign.saving'
                  : 'subscriptions.assign.submit'
              }
            />
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
