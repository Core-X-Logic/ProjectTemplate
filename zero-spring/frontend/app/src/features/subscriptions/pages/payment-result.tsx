import { CheckCircle2, TriangleAlert } from 'lucide-react';
import { Helmet } from 'react-helmet-async';
import { FormattedMessage, useIntl } from 'react-intl';
import { Link } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';

/**
 * Payment result landing pages (CONTRACT-payments-tr P2'-C, UI half).
 *
 * The provider redirects the buyer's browser to
 * `/payment/result/{success|cancel}` after its hosted page. Both pages are
 * purely INFORMATIONAL:
 *
 *  - The success redirect proves nothing (per PayTR/iyzico docs, the browser
 *    return is not a payment confirmation). Activation happens server-side
 *    via webhook/reconciliation, so this page must never say "activated" —
 *    it says the provider received the payment and points at the
 *    subscriptions list for the authoritative status.
 *  - The cancel page states the payment was NOT completed and that a new
 *    attempt starts from the subscriptions page.
 *
 * Route guard: authenticated only, deliberately NO permission — the redirect
 * may land on any signed-in session (e.g. a buyer without host permissions),
 * and a 403 here would read as "payment failed" when it did not.
 */

type PaymentResultKind = 'success' | 'cancel';

function PaymentResultPage({ kind }: { kind: PaymentResultKind }) {
  const intl = useIntl();
  const success = kind === 'success';

  return (
    <div className="container-fluid flex justify-center py-10">
      <Helmet>
        <title>
          {intl.formatMessage({
            id: `subscriptions.paymentResult.${kind}.title`,
          })}
        </title>
      </Helmet>

      <Card className="w-full max-w-lg">
        <CardHeader className="flex-col items-stretch gap-1.5 py-6">
          <div className="flex items-center gap-2">
            {success ? (
              <CheckCircle2 className="size-5 text-green-600" />
            ) : (
              <TriangleAlert className="size-5 text-amber-500" />
            )}
            <CardTitle className="text-lg">
              <FormattedMessage
                id={`subscriptions.paymentResult.${kind}.title`}
              />
            </CardTitle>
          </div>
          <CardDescription>
            <FormattedMessage
              id={`subscriptions.paymentResult.${kind}.description`}
            />
          </CardDescription>
        </CardHeader>

        <CardContent className="flex flex-col gap-4">
          {/* The load-bearing caveat, on both pages: the server decides. */}
          <p role="status" className="text-sm text-muted-foreground">
            <FormattedMessage
              id={`subscriptions.paymentResult.${kind}.warning`}
            />
          </p>

          <Button asChild className="w-full" variant="outline">
            <Link to="/subscriptions">
              <FormattedMessage id="subscriptions.paymentResult.goToSubscriptions" />
            </Link>
          </Button>
        </CardContent>
      </Card>
    </div>
  );
}

export function PaymentResultSuccessPage() {
  return <PaymentResultPage kind="success" />;
}

export function PaymentResultCancelPage() {
  return <PaymentResultPage kind="cancel" />;
}
