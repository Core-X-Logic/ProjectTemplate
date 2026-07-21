import { useEffect, useMemo } from 'react';
import { zodResolver } from '@hookform/resolvers/zod';
import { LoaderCircle } from 'lucide-react';
import { Helmet } from 'react-helmet-async';
import { useForm } from 'react-hook-form';
import { FormattedMessage, useIntl } from 'react-intl';
import { z } from 'zod';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardHeading,
  CardTitle,
} from '@/components/ui/card';
import { DataError } from '@/components/common/data-state';
import { PageHeader } from '@/components/common/page-header';
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
import { Skeleton } from '@/components/ui/skeleton';
import { ChangePasswordCard } from '../components/change-password-card';
import { useProfile, useUpdateProfile } from '../hooks';
import { PROFILE_LIMITS } from '../types';

interface ProfileFormValues {
  name: string;
  surname: string;
  email: string;
  phoneNumber: string;
}

/**
 * "My profile" (U-01, flow 2) — the user's own details plus password change.
 *
 * RBAC: no named permission. `ProfileController` is
 * `@PreAuthorize("isAuthenticated()")` on every method, so the route only sits
 * behind the plain `<RequireAuth>` shell — gating this screen on a permission
 * would lock users out of their own account details.
 *
 * `username` is displayed read-only: `UpdateProfileRequest` carries no username
 * field, so offering an editable input would be a control that silently does
 * nothing.
 */
export function ProfilePage() {
  const intl = useIntl();
  const { data: profile, isLoading, isError, refetch } = useProfile();
  const updateProfile = useUpdateProfile();

  const schema = useMemo(
    () =>
      z.object({
        name: z.string().max(
          PROFILE_LIMITS.name,
          intl.formatMessage(
            { id: 'profile.details.tooLong' },
            { max: PROFILE_LIMITS.name },
          ),
        ),
        surname: z.string().max(
          PROFILE_LIMITS.surname,
          intl.formatMessage(
            { id: 'profile.details.tooLong' },
            { max: PROFILE_LIMITS.surname },
          ),
        ),
        // Optional on the backend, so an empty string is valid here and is sent
        // as `undefined`; anything else must clear the `@Email` check.
        email: z
          .string()
          .max(
            PROFILE_LIMITS.email,
            intl.formatMessage(
              { id: 'profile.details.tooLong' },
              { max: PROFILE_LIMITS.email },
            ),
          )
          .refine(
            (value) => value === '' || z.email().safeParse(value).success,
            {
              message: intl.formatMessage({
                id: 'profile.details.invalidEmail',
              }),
            },
          ),
        phoneNumber: z.string().max(
          PROFILE_LIMITS.phoneNumber,
          intl.formatMessage(
            { id: 'profile.details.tooLong' },
            { max: PROFILE_LIMITS.phoneNumber },
          ),
        ),
      }),
    [intl],
  );

  const form = useForm<ProfileFormValues>({
    resolver: zodResolver(schema),
    defaultValues: { name: '', surname: '', email: '', phoneNumber: '' },
  });

  // The query resolves after the first render, so seed the form once it lands.
  const { reset } = form;
  useEffect(() => {
    if (profile) {
      reset({
        name: profile.name ?? '',
        surname: profile.surname ?? '',
        email: profile.email ?? '',
        phoneNumber: profile.phoneNumber ?? '',
      });
    }
  }, [profile, reset]);

  const submit = form.handleSubmit(async (values) => {
    try {
      // Blank inputs are sent as `undefined`, not `''`: the backend treats the
      // fields as optional, and an empty string would fail the `@Email` check.
      await updateProfile.mutateAsync({
        name: values.name.trim() || undefined,
        surname: values.surname.trim() || undefined,
        email: values.email.trim() || undefined,
        phoneNumber: values.phoneNumber.trim() || undefined,
      });
    } catch {
      // Surfaced by the mutation's error toast (prefers ProblemDetail detail).
    }
  });

  const isSubmitting = form.formState.isSubmitting;
  const roles = profile?.roles ?? [];

  return (
    <div className="container-fluid">
      <Helmet>
        <title>{intl.formatMessage({ id: 'profile.title' })}</title>
      </Helmet>

      <PageHeader
        title={<FormattedMessage id="profile.title" />}
        description={<FormattedMessage id="profile.description" />}
      />

      {isLoading ? (
        <Card>
          <CardContent className="flex flex-col gap-4 py-8">
            <Skeleton className="h-6 w-40" />
            <Skeleton className="h-9 w-full max-w-md" />
            <Skeleton className="h-9 w-full max-w-md" />
            <Skeleton className="h-9 w-full max-w-md" />
          </CardContent>
        </Card>
      ) : isError ? (
        <Card>
          <CardContent className="py-8">
            <DataError
              message={intl.formatMessage({ id: 'profile.loadError' })}
              onRetry={() => refetch()}
            />
          </CardContent>
        </Card>
      ) : (
        <div className="flex flex-col gap-6">
          <Card>
            <CardHeader className="py-5">
              <CardHeading>
                <CardTitle>
                  <FormattedMessage id="profile.details.title" />
                </CardTitle>
                <CardDescription>
                  <FormattedMessage id="profile.details.description" />
                </CardDescription>
              </CardHeading>
            </CardHeader>

            <CardContent className="flex flex-col gap-5 py-5">
              <div className="flex flex-col gap-2">
                <span className="text-sm font-medium text-foreground">
                  <FormattedMessage id="profile.details.username" />
                </span>
                <span className="text-sm text-muted-foreground">
                  {profile?.username ?? '—'}
                </span>
                <span className="text-xs text-muted-foreground">
                  <FormattedMessage id="profile.details.usernameHint" />
                </span>
              </div>

              <div className="flex flex-col gap-2">
                <span className="text-sm font-medium text-foreground">
                  <FormattedMessage id="profile.details.roles" />
                </span>
                {roles.length === 0 ? (
                  <span className="text-sm text-muted-foreground">
                    <FormattedMessage id="profile.details.noRoles" />
                  </span>
                ) : (
                  <div className="flex flex-wrap gap-1.5">
                    {roles.map((role) => (
                      <Badge key={role} variant="secondary" appearance="light">
                        {role}
                      </Badge>
                    ))}
                  </div>
                )}
              </div>

              <Badge
                variant={profile?.emailConfirmed ? 'success' : 'warning'}
                appearance="light"
                className="w-fit"
              >
                <FormattedMessage
                  id={
                    profile?.emailConfirmed
                      ? 'profile.details.emailConfirmed'
                      : 'profile.details.emailUnconfirmed'
                  }
                />
              </Badge>

              <Form {...form}>
                <form
                  onSubmit={submit}
                  className="flex max-w-md flex-col gap-5"
                  noValidate
                >
                  <FormField
                    control={form.control}
                    name="name"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>
                          <FormattedMessage id="profile.details.name" />
                        </FormLabel>
                        <FormControl>
                          <Input {...field} autoComplete="given-name" />
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />

                  <FormField
                    control={form.control}
                    name="surname"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>
                          <FormattedMessage id="profile.details.surname" />
                        </FormLabel>
                        <FormControl>
                          <Input {...field} autoComplete="family-name" />
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />

                  <FormField
                    control={form.control}
                    name="email"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>
                          <FormattedMessage id="profile.details.email" />
                        </FormLabel>
                        <FormControl>
                          <Input
                            {...field}
                            type="email"
                            autoComplete="email"
                          />
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />

                  <FormField
                    control={form.control}
                    name="phoneNumber"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>
                          <FormattedMessage id="profile.details.phoneNumber" />
                        </FormLabel>
                        <FormControl>
                          <Input {...field} autoComplete="tel" />
                        </FormControl>
                        <FormDescription />
                        <FormMessage />
                      </FormItem>
                    )}
                  />

                  <div>
                    <Button type="submit" disabled={isSubmitting}>
                      {isSubmitting && (
                        <LoaderCircle className="size-4 animate-spin" />
                      )}
                      <FormattedMessage
                        id={
                          isSubmitting
                            ? 'profile.details.saving'
                            : 'profile.details.save'
                        }
                      />
                    </Button>
                  </div>
                </form>
              </Form>
            </CardContent>
          </Card>

          <ChangePasswordCard />
        </div>
      )}
    </div>
  );
}
