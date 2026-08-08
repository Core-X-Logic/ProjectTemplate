import { useMemo, useState } from 'react';
import { zodResolver } from '@hookform/resolvers/zod';
import { CheckCircle2, CircleAlert, LoaderCircle } from 'lucide-react';
import { Helmet } from 'react-helmet-async';
import { useForm } from 'react-hook-form';
import { FormattedMessage, useIntl } from 'react-intl';
import { Link, useSearchParams } from 'react-router-dom';
import { z } from 'zod';
import { ApiError } from '@/api/client';
import { Button } from '@/components/ui/button';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import {
  Form,
  FormControl,
  FormDescription,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from '@/components/ui/form';
import { Input } from '@/components/ui/input';
import { useAcceptInvitation, useInvitationInfo } from '../hooks';
import {
  INVITATION_PASSWORD_MAX_LENGTH,
  INVITATION_PASSWORD_MIN_LENGTH,
  INVITATION_TOKEN_QUERY_PARAM,
} from '../types';

interface AcceptInvitationFormValues {
  password: string;
  confirmPassword: string;
}

/** Terminal panel shared by the done / already-accepted / invalid states. */
function ResultPanel({
  ok,
  titleId,
  descriptionId,
}: {
  ok: boolean;
  titleId: string;
  descriptionId: string;
}) {
  return (
    <>
      <CardHeader className="flex-col items-stretch gap-1.5 py-6">
        <div className="flex items-center gap-2">
          {ok ? (
            <CheckCircle2 className="size-5 text-green-600" />
          ) : (
            <CircleAlert className="size-5 text-destructive" />
          )}
          <CardTitle className="text-lg">
            <FormattedMessage id={titleId} />
          </CardTitle>
        </div>
        <CardDescription>
          <FormattedMessage id={descriptionId} />
        </CardDescription>
      </CardHeader>
      <CardContent>
        <Button asChild className="w-full">
          <Link to="/login">
            <FormattedMessage id="account.invite.goToLogin" />
          </Link>
        </Button>
      </CardContent>
    </>
  );
}

/**
 * "Accept invitation" — anonymous screen over
 * `GET /api/account/invitation` + `POST /api/account/accept-invitation`.
 *
 * The route path and the `?token=` parameter are dictated by the backend:
 * `EmailTemplateService.invitation` mails
 * `{baseUrl}/account/accept-invitation?token=…`. The username is DISPLAYED,
 * never collected — the admin fixed it at invite time; the invitee only
 * chooses a password.
 *
 * An already-accepted invitation is a success-shaped screen pointing at
 * sign-in (the backend answers the same 204 no-op on a replayed accept).
 */
export function AcceptInvitationPage() {
  const intl = useIntl();
  const [searchParams] = useSearchParams();
  const token = searchParams.get(INVITATION_TOKEN_QUERY_PARAM) ?? '';

  const [done, setDone] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);
  const info = useInvitationInfo(token);
  const accept = useAcceptInvitation();

  const schema = useMemo(
    () =>
      z
        .object({
          password: z
            .string()
            .min(
              INVITATION_PASSWORD_MIN_LENGTH,
              intl.formatMessage(
                { id: 'account.invite.tooShort' },
                { min: INVITATION_PASSWORD_MIN_LENGTH },
              ),
            )
            .max(
              INVITATION_PASSWORD_MAX_LENGTH,
              intl.formatMessage(
                { id: 'account.invite.tooLong' },
                { max: INVITATION_PASSWORD_MAX_LENGTH },
              ),
            ),
          confirmPassword: z.string(),
        })
        .refine((values) => values.password === values.confirmPassword, {
          path: ['confirmPassword'],
          message: intl.formatMessage({ id: 'account.invite.mismatch' }),
        }),
    [intl],
  );

  const form = useForm<AcceptInvitationFormValues>({
    resolver: zodResolver(schema),
    defaultValues: { password: '', confirmPassword: '' },
  });

  const submit = form.handleSubmit(async (values) => {
    setServerError(null);
    try {
      await accept.mutateAsync({ token: token.trim(), password: values.password });
      setDone(true);
    } catch (error) {
      const fallback = intl.formatMessage({ id: 'account.invite.error' });
      setServerError(
        error instanceof ApiError ? error.detail || fallback : fallback,
      );
    }
  });

  const isSubmitting = form.formState.isSubmitting;

  const body = () => {
    if (!token) {
      return (
        <ResultPanel
          ok={false}
          titleId="account.invite.invalidTitle"
          descriptionId="account.invite.missingToken"
        />
      );
    }
    if (done) {
      return (
        <ResultPanel
          ok
          titleId="account.invite.doneTitle"
          descriptionId="account.invite.doneDescription"
        />
      );
    }
    if (info.isLoading) {
      return (
        <CardHeader className="flex-col items-stretch gap-1.5 py-6">
          <div className="flex items-center gap-2">
            <LoaderCircle className="size-5 animate-spin text-muted-foreground" />
            <CardTitle className="text-lg">
              <FormattedMessage id="account.invite.loading" />
            </CardTitle>
          </div>
        </CardHeader>
      );
    }
    if (info.isError) {
      // The backend's single non-oracle 400: unknown, expired and revoked all
      // land here — the invitee's remedy is the same (ask for a new one).
      return (
        <ResultPanel
          ok={false}
          titleId="account.invite.invalidTitle"
          descriptionId="account.invite.invalidDescription"
        />
      );
    }
    if (info.data?.status === 'ACCEPTED') {
      return (
        <ResultPanel
          ok
          titleId="account.invite.alreadyTitle"
          descriptionId="account.invite.alreadyDescription"
        />
      );
    }
    return (
      <>
        <CardHeader className="flex-col items-stretch gap-1.5 py-6">
          <CardTitle className="text-lg">
            <FormattedMessage id="account.invite.title" />
          </CardTitle>
          <CardDescription>
            <FormattedMessage id="account.invite.subtitle" />
          </CardDescription>
        </CardHeader>

        <CardContent>
          <Form {...form}>
            <form onSubmit={submit} className="flex flex-col gap-5" noValidate>
              <div className="flex flex-col gap-2">
                <span className="text-sm font-medium">
                  <FormattedMessage id="account.invite.username" />
                </span>
                <Input
                  value={info.data?.username ?? ''}
                  readOnly
                  disabled
                  aria-label={intl.formatMessage({
                    id: 'account.invite.username',
                  })}
                />
                <p className="text-xs text-muted-foreground">
                  <FormattedMessage id="account.invite.usernameHint" />
                </p>
              </div>

              <FormField
                control={form.control}
                name="password"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>
                      <FormattedMessage id="account.invite.password" />
                    </FormLabel>
                    <FormControl>
                      <Input
                        {...field}
                        type="password"
                        autoComplete="new-password"
                      />
                    </FormControl>
                    <FormDescription>
                      <FormattedMessage
                        id="account.invite.passwordHint"
                        values={{ min: INVITATION_PASSWORD_MIN_LENGTH }}
                      />
                    </FormDescription>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <FormField
                control={form.control}
                name="confirmPassword"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>
                      <FormattedMessage id="account.invite.confirmPassword" />
                    </FormLabel>
                    <FormControl>
                      <Input
                        {...field}
                        type="password"
                        autoComplete="new-password"
                      />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />

              {serverError && (
                <p role="alert" className="text-sm font-normal text-destructive">
                  {serverError}
                </p>
              )}

              <Button type="submit" disabled={isSubmitting} className="w-full">
                {isSubmitting && (
                  <LoaderCircle className="size-4 animate-spin" />
                )}
                <FormattedMessage
                  id={
                    isSubmitting
                      ? 'account.invite.submitting'
                      : 'account.invite.submit'
                  }
                />
              </Button>

              <Link
                to="/login"
                className="text-center text-sm text-muted-foreground hover:underline"
              >
                <FormattedMessage id="account.forgot.backToLogin" />
              </Link>
            </form>
          </Form>
        </CardContent>
      </>
    );
  };

  return (
    <div className="flex grow items-center justify-center min-h-screen p-5">
      <Helmet>
        <title>{intl.formatMessage({ id: 'account.invite.title' })}</title>
      </Helmet>

      <Card className="w-full max-w-sm">{body()}</Card>
    </div>
  );
}
