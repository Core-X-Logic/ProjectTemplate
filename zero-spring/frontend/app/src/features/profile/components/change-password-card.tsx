import { useMemo } from 'react';
import { zodResolver } from '@hookform/resolvers/zod';
import { CheckCircle2, LoaderCircle } from 'lucide-react';
import { useForm } from 'react-hook-form';
import { FormattedMessage, useIntl } from 'react-intl';
import { z } from 'zod';
import { ApiError } from '@/api/client';
import { Button } from '@/components/ui/button';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardHeading,
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
import { useChangePassword } from '../hooks';
import { CHANGE_PASSWORD_MIN_LENGTH } from '../types';

interface ChangePasswordFormValues {
  currentPassword: string;
  newPassword: string;
  confirmPassword: string;
}

/**
 * Change-password card over `POST /api/profile/change-password`.
 *
 * Client-side checks are the cheap ones only — required, DTO minimum length,
 * confirmation match, and "not identical to the current password". The real
 * policy (complexity, reuse history) is the server's; its ProblemDetail
 * `detail` is surfaced by the mutation's error toast rather than duplicated
 * into a client-side rule that would drift from the backend.
 */
export function ChangePasswordCard() {
  const intl = useIntl();
  const changePassword = useChangePassword();

  const schema = useMemo(
    () =>
      z
        .object({
          currentPassword: z
            .string()
            .min(1, intl.formatMessage({ id: 'validation.required' })),
          newPassword: z.string().min(
            CHANGE_PASSWORD_MIN_LENGTH,
            intl.formatMessage(
              { id: 'profile.password.tooShort' },
              { min: CHANGE_PASSWORD_MIN_LENGTH },
            ),
          ),
          confirmPassword: z.string(),
        })
        .refine((values) => values.newPassword === values.confirmPassword, {
          path: ['confirmPassword'],
          message: intl.formatMessage({ id: 'profile.password.mismatch' }),
        })
        .refine((values) => values.newPassword !== values.currentPassword, {
          path: ['newPassword'],
          message: intl.formatMessage({
            id: 'profile.password.sameAsCurrent',
          }),
        }),
    [intl],
  );

  const form = useForm<ChangePasswordFormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      currentPassword: '',
      newPassword: '',
      confirmPassword: '',
    },
  });

  const submit = form.handleSubmit(async (values) => {
    try {
      await changePassword.mutateAsync({
        currentPassword: values.currentPassword,
        newPassword: values.newPassword,
      });
      // Never leave a plaintext password sitting in form state after success.
      form.reset();
    } catch {
      // Surfaced by the mutation's error toast (prefers ProblemDetail detail).
    }
  });

  const isSubmitting = form.formState.isSubmitting;

  return (
    <Card>
      <CardHeader className="py-5">
        <CardHeading>
          <CardTitle>
            <FormattedMessage id="profile.password.title" />
          </CardTitle>
          <CardDescription>
            <FormattedMessage id="profile.password.description" />
          </CardDescription>
        </CardHeading>
      </CardHeader>

      <CardContent className="py-5">
        <Form {...form}>
          <form
            onSubmit={submit}
            className="flex max-w-md flex-col gap-5"
            noValidate
          >
            <FormField
              control={form.control}
              name="currentPassword"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>
                    <FormattedMessage id="profile.password.current" />
                  </FormLabel>
                  <FormControl>
                    <Input
                      {...field}
                      type="password"
                      autoComplete="current-password"
                    />
                  </FormControl>
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
                    <FormattedMessage id="profile.password.new" />
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
                      id="profile.password.hint"
                      values={{ min: CHANGE_PASSWORD_MIN_LENGTH }}
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
                    <FormattedMessage id="profile.password.confirm" />
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

            {changePassword.isSuccess ? (
              <p
                role="status"
                className="flex items-center gap-2 text-sm text-success"
              >
                <CheckCircle2 className="size-4" aria-hidden />
                <FormattedMessage id="profile.password.success" />
              </p>
            ) : null}

            {changePassword.isError ? (
              <p role="alert" className="text-sm text-destructive">
                {changePassword.error instanceof ApiError &&
                changePassword.error.detail ? (
                  changePassword.error.detail
                ) : (
                  <FormattedMessage id="profile.password.error" />
                )}
              </p>
            ) : null}

            <div>
              <Button type="submit" disabled={isSubmitting}>
                {isSubmitting && (
                  <LoaderCircle className="size-4 animate-spin" />
                )}
                <FormattedMessage
                  id={
                    isSubmitting
                      ? 'profile.password.submitting'
                      : 'profile.password.submit'
                  }
                />
              </Button>
            </div>
          </form>
        </Form>
      </CardContent>
    </Card>
  );
}
