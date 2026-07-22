import { useMemo, useState } from 'react';
import { zodResolver } from '@hookform/resolvers/zod';
import { LoaderCircle } from 'lucide-react';
import { useForm } from 'react-hook-form';
import { FormattedMessage, useIntl } from 'react-intl';
import { Helmet } from 'react-helmet-async';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import { toast } from 'sonner';
import { z } from 'zod';
import { ApiError } from '@/api/client';
import { useAuth } from '@/providers/auth-provider';
import { useTenant } from '@/providers/tenant-provider';
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

interface LoginFormValues {
  usernameOrEmail: string;
  password: string;
  tenant?: string;
}

export function LoginPage() {
  const intl = useIntl();
  const navigate = useNavigate();
  const { user, login } = useAuth();
  const { setTenant } = useTenant();
  const [serverError, setServerError] = useState<string | null>(null);

  const schema = useMemo(
    () =>
      z.object({
        usernameOrEmail: z
          .string()
          .min(1, intl.formatMessage({ id: 'validation.required' })),
        password: z
          .string()
          .min(1, intl.formatMessage({ id: 'validation.required' })),
        tenant: z.string().optional(),
      }),
    [intl],
  );

  const form = useForm<LoginFormValues>({
    resolver: zodResolver(schema),
    defaultValues: { usernameOrEmail: '', password: '', tenant: '' },
  });

  const submit = form.handleSubmit(async (values) => {
    setServerError(null);
    const tenant = values.tenant?.trim() ? values.tenant.trim() : undefined;
    try {
      // The form field is the ONLY tenant authority on this screen. An empty
      // field must CLEAR the persisted tenant, otherwise a stale localStorage
      // value from an earlier session rides in as `X-Tenant` and the "leave
      // blank for the default tenant" promise silently logs into the wrong
      // tenant ("Invalid credentials" against its unrelated admin password).
      setTenant(tenant ?? null);
      const outcome = await login(values.usernameOrEmail, values.password, tenant);
      // 2FA account: no session yet. Carry the short-lived challenge to the
      // second step in router state (never localStorage) — a refresh drops it
      // and the second-step page sends the user back to /login.
      if (outcome?.status === 'twoFactorRequired') {
        navigate('/login/two-factor', {
          replace: true,
          state: { challengeToken: outcome.challengeToken },
        });
        return;
      }
      navigate('/', { replace: true });
    } catch (error) {
      const fallback = intl.formatMessage({ id: 'auth.login.error' });
      const detail =
        error instanceof ApiError ? error.detail || fallback : fallback;
      setServerError(detail);
      toast.error(fallback, {
        description: error instanceof ApiError ? error.detail : undefined,
      });
      if (error instanceof ApiError && error.fields) {
        for (const [field, messages] of Object.entries(error.fields)) {
          if (field === 'usernameOrEmail' || field === 'password') {
            form.setError(field, { message: messages.join(' ') });
          }
        }
      }
    }
  });

  // Already authenticated — nothing to do here.
  if (user) {
    return <Navigate to="/" replace />;
  }

  const isSubmitting = form.formState.isSubmitting;

  return (
    <div className="flex grow items-center justify-center min-h-screen p-5">
      <Helmet>
        <title>{intl.formatMessage({ id: 'auth.login.title' })}</title>
      </Helmet>

      <Card className="w-full max-w-sm">
        <CardHeader className="flex-col items-stretch gap-1.5 py-6">
          <CardTitle className="text-lg">
            <FormattedMessage id="auth.login.title" />
          </CardTitle>
          <CardDescription>
            <FormattedMessage id="auth.login.subtitle" />
          </CardDescription>
        </CardHeader>

        <CardContent>
          <Form {...form}>
            <form onSubmit={submit} className="flex flex-col gap-5" noValidate>
              <FormField
                control={form.control}
                name="usernameOrEmail"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>
                      <FormattedMessage id="auth.login.username" />
                    </FormLabel>
                    <FormControl>
                      <Input
                        {...field}
                        autoComplete="username"
                        placeholder={intl.formatMessage({
                          id: 'auth.login.usernamePlaceholder',
                        })}
                      />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <FormField
                control={form.control}
                name="password"
                render={({ field }) => (
                  <FormItem>
                    <div className="flex items-center justify-between gap-2">
                      <FormLabel>
                        <FormattedMessage id="auth.login.password" />
                      </FormLabel>
                      {/* Self-service recovery: without this link the only way
                          out of a forgotten password is an administrator. */}
                      <Link
                        to="/account/forgot-password"
                        className="text-sm text-muted-foreground hover:underline"
                      >
                        <FormattedMessage id="auth.login.forgotPassword" />
                      </Link>
                    </div>
                    <FormControl>
                      <Input
                        {...field}
                        type="password"
                        autoComplete="current-password"
                        placeholder={intl.formatMessage({
                          id: 'auth.login.passwordPlaceholder',
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
                      <FormattedMessage id="auth.login.tenant" />
                    </FormLabel>
                    <FormControl>
                      <Input
                        {...field}
                        autoComplete="off"
                        placeholder={intl.formatMessage({
                          id: 'auth.login.tenantPlaceholder',
                        })}
                      />
                    </FormControl>
                    <FormDescription>
                      <FormattedMessage id="auth.login.tenantPlaceholder" />
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

              <Button type="submit" disabled={isSubmitting} className="w-full">
                {isSubmitting && (
                  <LoaderCircle className="size-4 animate-spin" />
                )}
                <FormattedMessage
                  id={isSubmitting ? 'auth.login.submitting' : 'auth.login.submit'}
                />
              </Button>
            </form>
          </Form>
        </CardContent>
      </Card>
    </div>
  );
}
