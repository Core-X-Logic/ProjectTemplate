import { useMemo, useState } from 'react';
import { zodResolver } from '@hookform/resolvers/zod';
import { CheckCircle2, LoaderCircle } from 'lucide-react';
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
import { useResetPassword } from '../hooks';
import {
  CODE_QUERY_PARAM,
  RESET_PASSWORD_MAX_LENGTH,
  RESET_PASSWORD_MIN_LENGTH,
} from '../types';

interface ResetPasswordFormValues {
  resetCode: string;
  newPassword: string;
  confirmPassword: string;
}

/**
 * True when a 400 from `POST /api/account/reset-password` rejects the PASSWORD
 * rather than the code. Both failures arrive as `code: VALIDATION`, so only
 * `detail` tells them apart — a contract with the backend's message prefixes:
 * `PasswordPolicyValidator` ("Password does not meet policy: …", "Password
 * must not be empty") and `PasswordHistoryService` ("Password was used
 * recently; …") all start with "Password", while a stale/unknown code is
 * "Invalid or expired reset code" (`AccountService`, pinned by
 * `PasswordPolicyIT`). The split matters for recovery: a rejected password is
 * fixed in the field, a rejected code is only fixed by requesting a new one.
 */
function isPasswordRejection(error: ApiError): boolean {
  return (
    error.status === 400 &&
    typeof error.detail === 'string' &&
    error.detail.startsWith('Password')
  );
}

/**
 * "Reset password" (U-01, flow 1) — anonymous screen over
 * `POST /api/account/reset-password`.
 *
 * The route path and the `?code=` parameter are dictated by the backend: the
 * mail template builds `{baseUrl}/account/reset-password?code=…`. The code is
 * still rendered as an editable field when the parameter is absent, because the
 * same mail also prints the bare code for anyone whose client mangles links.
 *
 * Client-side validation only enforces the DTO floor
 * (`@Size(min = 6, max = 128)`). The tenant `PasswordPolicy` and the
 * password-history check live on the server; both surface as a ProblemDetail
 * `detail` which is shown verbatim rather than second-guessed here.
 */
export function ResetPasswordPage() {
  const intl = useIntl();
  const [searchParams] = useSearchParams();
  const codeFromLink = searchParams.get(CODE_QUERY_PARAM) ?? '';

  const [done, setDone] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);
  const reset = useResetPassword();

  const schema = useMemo(
    () =>
      z
        .object({
          resetCode: z
            .string()
            .min(1, intl.formatMessage({ id: 'validation.required' })),
          newPassword: z
            .string()
            .min(
              RESET_PASSWORD_MIN_LENGTH,
              intl.formatMessage(
                { id: 'account.reset.tooShort' },
                { min: RESET_PASSWORD_MIN_LENGTH },
              ),
            )
            .max(
              RESET_PASSWORD_MAX_LENGTH,
              intl.formatMessage(
                { id: 'account.reset.tooLong' },
                { max: RESET_PASSWORD_MAX_LENGTH },
              ),
            ),
          confirmPassword: z.string(),
        })
        .refine((values) => values.newPassword === values.confirmPassword, {
          path: ['confirmPassword'],
          message: intl.formatMessage({ id: 'account.reset.mismatch' }),
        }),
    [intl],
  );

  const form = useForm<ResetPasswordFormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      resetCode: codeFromLink,
      newPassword: '',
      confirmPassword: '',
    },
  });

  const submit = form.handleSubmit(async (values) => {
    setServerError(null);
    try {
      await reset.mutateAsync({
        resetCode: values.resetCode.trim(),
        newPassword: values.newPassword,
      });
      setDone(true);
    } catch (error) {
      const fallback = intl.formatMessage({ id: 'account.reset.error' });
      if (error instanceof ApiError && isPasswordRejection(error)) {
        // The code is still valid; the password is what needs fixing. Editing
        // the field re-runs the resolver and clears this server verdict.
        form.setError('newPassword', {
          type: 'server',
          message: error.detail ?? fallback,
        });
        return;
      }
      setServerError(
        error instanceof ApiError ? error.detail || fallback : fallback,
      );
    }
  });

  const isSubmitting = form.formState.isSubmitting;

  return (
    <div className="flex grow items-center justify-center min-h-screen p-5">
      <Helmet>
        <title>{intl.formatMessage({ id: 'account.reset.title' })}</title>
      </Helmet>

      <Card className="w-full max-w-sm">
        {done ? (
          <>
            <CardHeader className="flex-col items-stretch gap-1.5 py-6">
              <div className="flex items-center gap-2">
                <CheckCircle2 className="size-5 text-green-600" />
                <CardTitle className="text-lg">
                  <FormattedMessage id="account.reset.doneTitle" />
                </CardTitle>
              </div>
              <CardDescription>
                <FormattedMessage id="account.reset.doneDescription" />
              </CardDescription>
            </CardHeader>
            <CardContent>
              <Button asChild className="w-full">
                <Link to="/login">
                  <FormattedMessage id="account.reset.goToLogin" />
                </Link>
              </Button>
            </CardContent>
          </>
        ) : (
          <>
            <CardHeader className="flex-col items-stretch gap-1.5 py-6">
              <CardTitle className="text-lg">
                <FormattedMessage id="account.reset.title" />
              </CardTitle>
              <CardDescription>
                <FormattedMessage id="account.reset.subtitle" />
              </CardDescription>
            </CardHeader>

            <CardContent>
              <Form {...form}>
                <form
                  onSubmit={submit}
                  className="flex flex-col gap-5"
                  noValidate
                >
                  <FormField
                    control={form.control}
                    name="resetCode"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>
                          <FormattedMessage id="account.reset.code" />
                        </FormLabel>
                        <FormControl>
                          <Input
                            {...field}
                            autoComplete="one-time-code"
                            placeholder={intl.formatMessage({
                              id: 'account.reset.codePlaceholder',
                            })}
                          />
                        </FormControl>
                        {codeFromLink ? (
                          <FormDescription>
                            <FormattedMessage id="account.reset.codeFromLink" />
                          </FormDescription>
                        ) : null}
                        <FormMessage />
                      </FormItem>
                    )}
                  />

                  <FormField
                    control={form.control}
                    name="newPassword"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>
                          <FormattedMessage id="account.reset.newPassword" />
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
                            id="account.reset.passwordHint"
                            values={{ min: RESET_PASSWORD_MIN_LENGTH }}
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
                          <FormattedMessage id="account.reset.confirmPassword" />
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
                    <div className="flex flex-col gap-1">
                      <p
                        role="alert"
                        className="text-sm font-normal text-destructive"
                      >
                        {serverError}
                      </p>
                      {/* A dead code cannot be revived from this form — the
                          only way forward is asking for a fresh one. */}
                      <Link
                        to="/account/forgot-password"
                        className="text-sm font-medium text-primary hover:underline"
                      >
                        <FormattedMessage id="account.reset.requestNew" />
                      </Link>
                    </div>
                  )}

                  <Button
                    type="submit"
                    disabled={isSubmitting}
                    className="w-full"
                  >
                    {isSubmitting && (
                      <LoaderCircle className="size-4 animate-spin" />
                    )}
                    <FormattedMessage
                      id={
                        isSubmitting
                          ? 'account.reset.submitting'
                          : 'account.reset.submit'
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
        )}
      </Card>
    </div>
  );
}
