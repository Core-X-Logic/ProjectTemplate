import { useEffect, useRef, useState } from 'react';
import { CheckCircle2, LoaderCircle, TriangleAlert } from 'lucide-react';
import { Helmet } from 'react-helmet-async';
import { FormattedMessage, useIntl } from 'react-intl';
import { Link, useSearchParams } from 'react-router-dom';
import { ApiError } from '@/api/client';
import { Button } from '@/components/ui/button';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import { useConfirmEmail } from '../hooks';
import { CODE_QUERY_PARAM } from '../types';

/**
 * Email confirmation landing page over `POST /api/account/confirm-email`.
 *
 * Exists because `EmailTemplateService.emailConfirmation` already mails a link
 * to `{baseUrl}/account/confirm-email?code=…`; without this route every
 * confirmation mail the backend has ever sent lands on the 404 page.
 *
 * The code is consumed on mount (there is nothing for the user to decide), so
 * the screen is a pure status surface: pending · confirmed · failed · no-code.
 */
export function ConfirmEmailPage() {
  const intl = useIntl();
  const [searchParams] = useSearchParams();
  const code = searchParams.get(CODE_QUERY_PARAM) ?? '';

  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const confirm = useConfirmEmail();

  // React 18/19 StrictMode double-invokes effects in development; the code is
  // single-use, so a second POST would fail against an already-consumed code
  // and paint an error over a successful confirmation.
  const attempted = useRef(false);
  const { mutateAsync } = confirm;

  useEffect(() => {
    if (!code || attempted.current) {
      return;
    }
    attempted.current = true;
    void (async () => {
      try {
        await mutateAsync({ code });
        setDone(true);
      } catch (caught) {
        const fallback = intl.formatMessage({ id: 'account.confirm.error' });
        setError(
          caught instanceof ApiError ? caught.detail || fallback : fallback,
        );
      }
    })();
  }, [code, mutateAsync, intl]);

  return (
    <div className="flex grow items-center justify-center min-h-screen p-5">
      <Helmet>
        <title>{intl.formatMessage({ id: 'account.confirm.title' })}</title>
      </Helmet>

      <Card className="w-full max-w-sm">
        <CardHeader className="flex-col items-stretch gap-1.5 py-6">
          <div className="flex items-center gap-2">
            {done ? <CheckCircle2 className="size-5 text-green-600" /> : null}
            {error || !code ? (
              <TriangleAlert className="size-5 text-destructive" />
            ) : null}
            <CardTitle className="text-lg">
              <FormattedMessage
                id={
                  done ? 'account.confirm.doneTitle' : 'account.confirm.title'
                }
              />
            </CardTitle>
          </div>
          {done ? (
            <CardDescription>
              <FormattedMessage id="account.confirm.doneDescription" />
            </CardDescription>
          ) : null}
        </CardHeader>

        <CardContent className="flex flex-col gap-4">
          {!code ? (
            <p role="alert" className="text-sm text-destructive">
              <FormattedMessage id="account.confirm.missingCode" />
            </p>
          ) : null}

          {code && !done && !error ? (
            <p className="flex items-center gap-2 text-sm text-muted-foreground">
              <LoaderCircle className="size-4 animate-spin" />
              <FormattedMessage id="account.confirm.pending" />
            </p>
          ) : null}

          {error ? (
            <p role="alert" className="text-sm text-destructive">
              {error}
            </p>
          ) : null}

          <Button asChild className="w-full" variant={done ? 'primary' : 'outline'}>
            <Link to="/login">
              <FormattedMessage id="account.confirm.goToLogin" />
            </Link>
          </Button>
        </CardContent>
      </Card>
    </div>
  );
}
