import { useEffect, useMemo, useState } from 'react';
import { ExternalLink, LoaderCircle, TriangleAlert } from 'lucide-react';
import { FormattedMessage, useIntl } from 'react-intl';
import { ApiError } from '@/api/client';
import { Button } from '@/components/ui/button';
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
import { useStartCheckout } from '../hooks';
import {
  BILLING_PERIODS,
  PAYMENT_PROVIDERS,
  type BillingPeriod,
  type CheckoutSessionDto,
  type PaymentProvider,
  type SubscriptionDto,
} from '../types';

/**
 * "Pay & assign" checkout dialog (CONTRACT-payments-tr P2'-C, UI half).
 *
 * Sends `POST /api/billing/checkout` and hands the operator off to the
 * provider's HOSTED payment page in a new tab. Deliberate constraints:
 *
 *  - No iframe embedding: PayTR's iframe flow needs their external
 *    iframeResizer script and a relaxed CSP, so the `url` (PayTR
 *    `/odeme/guvenli/{token}` or iyzico `paymentPageUrl`) is opened via
 *    `window.open` with a visible fallback link for popup blockers.
 *  - The "payment started" state ALWAYS renders after a successful start —
 *    activation happens server-side (webhook/reconciliation). Closing the
 *    provider tab or this dialog neither cancels nor confirms anything, and
 *    the warning says so explicitly; status lands on the subscriptions list.
 *  - `successUrl`/`cancelUrl` point at `/payment/result/*` on this origin;
 *    those pages are informational only (the redirect proves nothing).
 *
 * The backend owns provider enablement: a disabled/unknown provider comes
 * back as a 400 ProblemDetail naming the enabled ids, surfaced inline (and as
 * a toast by the hook) with a retry button; the dialog stays open.
 *
 * Selectors are native `<select>`/`<input type="radio">` elements — no extra
 * dependency, accessible out of the box, directly drivable from the tests
 * (same rationale as the assign-edition dialog).
 */

export interface CheckoutDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Subscription row being paid for; supplies tenant id + current edition. */
  subscription: SubscriptionDto | null;
}

const SELECT_CLASS =
  'flex h-9 w-full items-center rounded-md border border-input bg-background px-3 py-1 text-sm text-foreground shadow-xs shadow-black/5 outline-none focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/30 disabled:cursor-not-allowed disabled:opacity-50';

export function CheckoutDialog({
  open,
  onOpenChange,
  subscription,
}: CheckoutDialogProps) {
  const intl = useIntl();

  // Mounted (closed) on the subscriptions list; fetch editions only when open.
  const {
    data: editionPage,
    isLoading,
    isError,
  } = useEditions({ page: 0, size: 100 }, { enabled: open });
  const checkout = useStartCheckout();
  const { reset: resetCheckout } = checkout;

  const editions = useMemo(
    () => (editionPage?.content ?? []).filter((edition) => edition.active),
    [editionPage],
  );

  const [editionId, setEditionId] = useState('');
  const [billingPeriod, setBillingPeriod] = useState<BillingPeriod>('MONTHLY');
  const [provider, setProvider] = useState<PaymentProvider>('paytr');
  const [touched, setTouched] = useState(false);
  const [session, setSession] = useState<CheckoutSessionDto | null>(null);

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
      setProvider('paytr');
      setTouched(false);
      setSession(null);
      resetCheckout();
    }
  }, [open, subscription, resetCheckout]);

  const tenantLabel =
    subscription?.tenantName ??
    (subscription?.tenantId !== undefined ? String(subscription.tenantId) : '');

  const submit = () => {
    setTouched(true);
    const tenantId = subscription?.tenantId;
    if (tenantId === undefined || editionId === '') {
      return;
    }

    checkout.mutate(
      {
        tenantId,
        editionId: Number(editionId),
        billingPeriod,
        provider,
        // Absolute URLs on this origin: the provider redirects the buyer's
        // browser here after the hosted page. Informational landing only.
        successUrl: `${window.location.origin}/payment/result/success`,
        cancelUrl: `${window.location.origin}/payment/result/cancel`,
      },
      {
        onSuccess: (started) => {
          setSession(started);
          if (started.url) {
            // May be blocked by popup blockers — the started state below
            // always renders the same URL as a plain fallback link.
            window.open(started.url, '_blank', 'noopener,noreferrer');
          }
        },
      },
    );
  };

  const checkoutErrorDetail =
    checkout.error instanceof ApiError ? checkout.error.detail : undefined;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>
            <FormattedMessage
              id={
                session
                  ? 'subscriptions.checkout.started.title'
                  : 'subscriptions.checkout.title'
              }
            />
          </DialogTitle>
          <DialogDescription>
            <FormattedMessage
              id="subscriptions.checkout.description"
              values={{ tenant: tenantLabel }}
            />
          </DialogDescription>
        </DialogHeader>

        {session ? (
          <div className="flex flex-col gap-4 py-2">
            <p className="text-sm font-medium text-foreground">
              <FormattedMessage id="subscriptions.checkout.started.description" />
            </p>

            {/* The single most important sentence in this dialog: activation
                is server-side; nothing here confirms or cancels a payment. */}
            <p
              role="alert"
              className="flex items-start gap-2 rounded-md border border-input bg-muted/40 p-3 text-sm text-muted-foreground"
            >
              <TriangleAlert className="mt-0.5 size-4 shrink-0 text-amber-500" />
              <FormattedMessage id="subscriptions.checkout.started.warning" />
            </p>

            {session.paymentId !== undefined ? (
              <p className="text-sm text-muted-foreground">
                <FormattedMessage
                  id="subscriptions.checkout.started.paymentId"
                  values={{ id: String(session.paymentId) }}
                />
              </p>
            ) : null}

            {session.url ? (
              <p className="text-sm text-muted-foreground">
                <FormattedMessage id="subscriptions.checkout.started.fallback" />{' '}
                <a
                  href={session.url}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex items-center gap-1 font-medium text-primary hover:underline"
                >
                  <FormattedMessage id="subscriptions.checkout.started.fallbackLink" />
                  <ExternalLink className="size-3.5" />
                </a>
              </p>
            ) : null}
          </div>
        ) : isLoading ? (
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
              <Label htmlFor="checkout-edition">
                <FormattedMessage id="subscriptions.assign.edition" />
              </Label>
              <select
                id="checkout-edition"
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
              <Label htmlFor="checkout-billing-period">
                <FormattedMessage id="subscriptions.assign.billingPeriod" />
              </Label>
              <select
                id="checkout-billing-period"
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

            <fieldset className="flex flex-col gap-1.5">
              <legend className="mb-1.5 text-sm font-medium text-foreground">
                <FormattedMessage id="subscriptions.checkout.provider" />
              </legend>
              <div className="flex flex-row gap-5">
                {PAYMENT_PROVIDERS.map((id) => (
                  <label
                    key={id}
                    className="flex cursor-pointer items-center gap-2 text-sm text-foreground"
                  >
                    <input
                      type="radio"
                      name="checkout-provider"
                      className="size-4 accent-primary"
                      value={id}
                      checked={provider === id}
                      onChange={() => setProvider(id)}
                    />
                    <FormattedMessage
                      id={`subscriptions.checkout.provider.${id}`}
                    />
                  </label>
                ))}
              </div>
            </fieldset>

            {checkout.isError ? (
              <div
                role="alert"
                className="flex flex-col gap-2 rounded-md border border-destructive/40 bg-destructive/5 p-3 text-sm text-destructive"
              >
                <span className="font-medium">
                  <FormattedMessage id="subscriptions.checkout.error" />
                </span>
                {/* Backend wording verbatim — e.g. the 400 naming which
                    providers are actually enabled. */}
                {checkoutErrorDetail ? <span>{checkoutErrorDetail}</span> : null}
                <div>
                  <Button
                    type="button"
                    size="sm"
                    variant="outline"
                    onClick={submit}
                    disabled={checkout.isPending}
                  >
                    <FormattedMessage id="subscriptions.checkout.retry" />
                  </Button>
                </div>
              </div>
            ) : null}
          </div>
        )}

        <DialogFooter>
          {session ? (
            <Button type="button" onClick={() => onOpenChange(false)}>
              <FormattedMessage id="subscriptions.checkout.close" />
            </Button>
          ) : (
            <>
              <Button
                type="button"
                variant="outline"
                onClick={() => onOpenChange(false)}
              >
                <FormattedMessage id="subscriptions.checkout.cancel" />
              </Button>
              <Button
                type="button"
                onClick={submit}
                disabled={checkout.isPending || editions.length === 0}
              >
                {checkout.isPending && (
                  <LoaderCircle className="size-4 animate-spin" />
                )}
                <FormattedMessage
                  id={
                    checkout.isPending
                      ? 'subscriptions.checkout.starting'
                      : 'subscriptions.checkout.submit'
                  }
                />
              </Button>
            </>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
