import { useMemo, useState } from 'react';
import { zodResolver } from '@hookform/resolvers/zod';
import { LoaderCircle, MailCheck } from 'lucide-react';
import { Helmet } from 'react-helmet-async';
import { useForm } from 'react-hook-form';
import { FormattedMessage, useIntl } from 'react-intl';
import { Link } from 'react-router-dom';
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
import { useForgotPassword } from '../hooks';

interface ForgotPasswordFormValues {
  usernameOrEmail: string;
  tenant?: string;
}

/**
 * "Forgot password" (U-01, flow 1) — anonymous screen over
 * `POST /api/account/forgot-password`.
 *
 * The backend is enumeration-safe: it answers 204 whether or not the account
 * exists. The success panel therefore says "if an account matches" rather than
 * confirming delivery — otherwise the UI would hand back exactly the existence
 * oracle the backend refuses to give.
 *
 * `tenant` is optional and travels in the BODY (not the `X-Tenant` header):
 * leaving it empty targets the host (tenant-less) scope, matching
 * `AccountService.resolveTenantId`.
 */
export function ForgotPasswordPage() {
  const intl = useIntl();
  const [sent, setSent] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);
  const forgot = useForgotPassword();

  const schema = useMemo(
    () =>
      z.object({
        usernameOrEmail: z
          .string()
          .min(1, intl.formatMessage({ id: 'validation.required' })),
        tenant: z.string().optional(),
      }),
    [intl],
  );

  const form = useForm<ForgotPasswordFormValues>({
    resolver: zodResolver(schema),
    defaultValues: { usernameOrEmail: '', tenant: '' },
  });

  const submit = form.handleSubmit(async (values) => {
    setServerError(null);
    try {
      await forgot.mutateAsync({
        usernameOrEmail: values.usernameOrEmail.trim(),
        tenant: values.tenant?.trim() ? values.tenant.trim() : undefined,
      });
      setSent(true);
    } catch (error) {
      const fallback = intl.formatMessage({ id: 'account.forgot.error' });
      setServerError(
        error instanceof ApiError ? error.detail || fallback : fallback,
      );
    }
  });

  const isSubmitting = form.formState.isSubmitting;

  return (
    <div className="flex grow items-center justify-center min-h-screen p-5">
      <Helmet>
        <title>{intl.formatMessage({ id: 'account.forgot.title' })}</title>
      </Helmet>

      <Card className="w-full max-w-sm">
        {sent ? (
          <>
            <CardHeader className="flex-col items-stretch gap-1.5 py-6">
              <div className="flex items-center gap-2">
                <MailCheck className="size-5 text-green-600" />
                <CardTitle className="text-lg">
                  <FormattedMessage id="account.forgot.sentTitle" />
                </CardTitle>
              </div>
              <CardDescription>
                <FormattedMessage id="account.forgot.sentDescription" />
              </CardDescription>
            </CardHeader>
            <CardContent className="flex flex-col gap-4">
              <p className="text-sm text-muted-foreground">
                <FormattedMessage id="account.forgot.sentHint" />{' '}
                <Link
                  to="/account/reset-password"
                  className="font-medium text-primary hover:underline"
                >
                  <FormattedMessage id="account.forgot.sentAction" />
                </Link>
              </p>
              <Link
                to="/login"
                className="text-sm text-muted-foreground hover:underline"
              >
                <FormattedMessage id="account.forgot.backToLogin" />
              </Link>
            </CardContent>
          </>
        ) : (
          <>
            <CardHeader className="flex-col items-stretch gap-1.5 py-6">
              <CardTitle className="text-lg">
                <FormattedMessage id="account.forgot.title" />
              </CardTitle>
              <CardDescription>
                <FormattedMessage id="account.forgot.subtitle" />
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
                    name="usernameOrEmail"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>
                          <FormattedMessage id="account.forgot.username" />
                        </FormLabel>
                        <FormControl>
                          <Input
                            {...field}
                            autoComplete="username"
                            placeholder={intl.formatMessage({
                              id: 'account.forgot.usernamePlaceholder',
                            })}
                          />
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />

                  <FormField
                    control={form.control}
                    name="tenant"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>
                          <FormattedMessage id="account.forgot.tenant" />
                        </FormLabel>
                        <FormControl>
                          <Input
                            {...field}
                            autoComplete="off"
                            placeholder={intl.formatMessage({
                              id: 'account.forgot.tenantPlaceholder',
                            })}
                          />
                        </FormControl>
                        <FormDescription>
                          <FormattedMessage id="account.forgot.tenantHint" />
                        </FormDescription>
                        <FormMessage />
                      </FormItem>
                    )}
                  />

                  {serverError && (
                    <p
                      role="alert"
                      className="text-sm font-normal text-destructive"
                    >
                      {serverError}
                    </p>
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
                          ? 'account.forgot.submitting'
                          : 'account.forgot.submit'
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
