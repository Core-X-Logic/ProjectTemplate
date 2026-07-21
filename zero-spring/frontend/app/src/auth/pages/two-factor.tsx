import { useState } from 'react';
import { LoaderCircle } from 'lucide-react';
import { FormattedMessage, useIntl } from 'react-intl';
import { Helmet } from 'react-helmet-async';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { ApiError } from '@/api/client';
import { useAuth } from '@/providers/auth-provider';
import { Button } from '@/components/ui/button';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  InputOTP,
  InputOTPGroup,
  InputOTPSlot,
} from '@/components/ui/input-otp';

/** Router state handed over by the login page (never localStorage — short-lived). */
interface TwoFactorLocationState {
  challengeToken?: string;
}

/**
 * Second step of login for 2FA accounts (pre-token, so PUBLIC/anonymous like
 * `/login`). It redeems the challenge minted by `POST /api/auth/login` with a
 * 6-digit TOTP or a recovery code via `verifyTwoFactor`.
 *
 * No oracle: the backend answers every bad attempt — wrong code, expired or
 * already-consumed challenge — with the same generic 401, so the UI shows one
 * neutral "invalid or expired" message and lets the user retry. Only a non-401
 * signal (e.g. a 429 rate-limit `Retry-After`) surfaces its ProblemDetail.
 *
 * The challenge lives only in router state; a refresh or a direct hit drops it,
 * and the page then bounces back to `/login` to start over.
 */
export function TwoFactorPage() {
  const intl = useIntl();
  const navigate = useNavigate();
  const location = useLocation();
  const { user, verifyTwoFactor } = useAuth();

  const [mode, setMode] = useState<'totp' | 'recovery'>('totp');
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const state = location.state as TwoFactorLocationState | null;
  const challengeToken = state?.challengeToken ?? null;

  // Already authenticated — nothing to verify.
  if (user) {
    return <Navigate to="/" replace />;
  }
  // No challenge (refresh dropped router state, or a direct visit) — restart.
  if (!challengeToken) {
    return <Navigate to="/login" replace />;
  }

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    const trimmed = code.trim();
    if (!trimmed || submitting) {
      return;
    }
    setError(null);
    setSubmitting(true);
    try {
      await verifyTwoFactor(challengeToken, trimmed);
      navigate('/', { replace: true });
    } catch (err) {
      // Wrong code and expired challenge are indistinguishable (generic 401).
      // Only a non-401 (e.g. 429 rate limit) carries a meaningful detail.
      if (err instanceof ApiError && err.status !== 401 && err.detail) {
        setError(err.detail);
      } else {
        setError(intl.formatMessage({ id: 'auth.twoFactor.error' }));
      }
    } finally {
      setSubmitting(false);
    }
  };

  const switchMode = () => {
    setMode((current) => (current === 'totp' ? 'recovery' : 'totp'));
    setCode('');
    setError(null);
  };

  const isRecovery = mode === 'recovery';

  return (
    <div className="flex grow items-center justify-center min-h-screen p-5">
      <Helmet>
        <title>{intl.formatMessage({ id: 'auth.twoFactor.title' })}</title>
      </Helmet>

      <Card className="w-full max-w-sm">
        <CardHeader className="flex-col items-stretch gap-1.5 py-6">
          <CardTitle className="text-lg">
            <FormattedMessage id="auth.twoFactor.title" />
          </CardTitle>
          <CardDescription>
            <FormattedMessage
              id={
                isRecovery
                  ? 'auth.twoFactor.recoverySubtitle'
                  : 'auth.twoFactor.subtitle'
              }
            />
          </CardDescription>
        </CardHeader>

        <CardContent>
          <form onSubmit={submit} className="flex flex-col gap-5" noValidate>
            {isRecovery ? (
              <div className="flex flex-col gap-2">
                <Label htmlFor="two-factor-recovery">
                  <FormattedMessage id="auth.twoFactor.recoveryLabel" />
                </Label>
                <Input
                  id="two-factor-recovery"
                  value={code}
                  onChange={(event) => setCode(event.target.value)}
                  autoComplete="one-time-code"
                  autoFocus
                />
              </div>
            ) : (
              <div className="flex flex-col gap-2">
                <Label htmlFor="two-factor-code">
                  <FormattedMessage id="auth.twoFactor.codeLabel" />
                </Label>
                <InputOTP
                  id="two-factor-code"
                  maxLength={6}
                  value={code}
                  onChange={setCode}
                  inputMode="numeric"
                  autoFocus
                  // The password-manager badge sync runs timers against
                  // `document.elementFromPoint`; a 6-digit code has no PM value,
                  // so disable it (also keeps jsdom behaviour tests clean).
                  pushPasswordManagerStrategy="none"
                  aria-label={intl.formatMessage({
                    id: 'auth.twoFactor.codeLabel',
                  })}
                >
                  <InputOTPGroup>
                    <InputOTPSlot index={0} />
                    <InputOTPSlot index={1} />
                    <InputOTPSlot index={2} />
                    <InputOTPSlot index={3} />
                    <InputOTPSlot index={4} />
                    <InputOTPSlot index={5} />
                  </InputOTPGroup>
                </InputOTP>
              </div>
            )}

            {error && (
              <p role="alert" className="text-sm font-normal text-destructive">
                {error}
              </p>
            )}

            <Button
              type="submit"
              disabled={submitting || code.trim().length === 0}
              className="w-full"
            >
              {submitting && <LoaderCircle className="size-4 animate-spin" />}
              <FormattedMessage
                id={
                  submitting
                    ? 'auth.twoFactor.submitting'
                    : 'auth.twoFactor.submit'
                }
              />
            </Button>

            <div className="flex items-center justify-between gap-2">
              <button
                type="button"
                onClick={switchMode}
                className="text-sm text-muted-foreground hover:underline"
              >
                <FormattedMessage
                  id={
                    isRecovery
                      ? 'auth.twoFactor.useAuthenticator'
                      : 'auth.twoFactor.useRecovery'
                  }
                />
              </button>
              <button
                type="button"
                onClick={() => navigate('/login', { replace: true })}
                className="text-sm text-muted-foreground hover:underline"
              >
                <FormattedMessage id="auth.twoFactor.backToLogin" />
              </button>
            </div>
          </form>
        </CardContent>
      </Card>
    </div>
  );
}
