import { useEffect, useState } from 'react';
import { Check, Copy, LoaderCircle, ShieldCheck, TriangleAlert } from 'lucide-react';
import { FormattedMessage, useIntl } from 'react-intl';
import { QRCodeSVG } from 'qrcode.react';
import {
  Alert,
  AlertContent,
  AlertDescription,
  AlertIcon,
  AlertTitle,
} from '@/components/ui/alert';
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
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import {
  InputOTP,
  InputOTPGroup,
  InputOTPSlot,
} from '@/components/ui/input-otp';
import { Label } from '@/components/ui/label';
import { useAuth } from '@/providers/auth-provider';
import { useCopyToClipboard } from '@/hooks/use-copy-to-clipboard';
import {
  useDisableTwoFactor,
  useEnableTwoFactor,
  useRegenerateRecoveryCodes,
  useSetupTwoFactor,
} from '../hooks';
import type { TwoFactorSetupDto } from '../types';

/**
 * Self-service 2FA management card, mounted next to `<ChangePasswordCard/>`.
 *
 * The on/off state is READ from the backend: `MeDto.twoFactorEnabled` (served by
 * `/api/auth/me`, held on the auth-context `user`). The card shows ONLY the
 * enable flow when it is off and ONLY the manage flow (disable / regenerate)
 * when it is on — no guessing, no dual bridge. After an enable or disable
 * succeeds it calls `refreshMe()` so the authoritative flag reloads and the card
 * flips to the matching view.
 *
 * `view` is a transient overlay for the two multi-step flows only: `enrolling`
 * (scan + confirm during enable) and `recovery` (show the one-time codes after
 * enable or regenerate). When `view === 'idle'` the resting content is derived
 * purely from `twoFactorEnabled`.
 *
 * The setup secret and the recovery codes are each shown ONCE and never
 * persisted — they live only in component state and are dropped on unmount.
 */
type View = 'idle' | 'enrolling' | 'recovery';

export function TwoFactorCard() {
  const intl = useIntl();
  const { user, refreshMe } = useAuth();
  const enabled = user?.twoFactorEnabled ?? false;

  const setup = useSetupTwoFactor();
  const enable = useEnableTwoFactor();
  const disable = useDisableTwoFactor();
  const regenerate = useRegenerateRecoveryCodes();

  const [view, setView] = useState<View>('idle');
  const [setupData, setSetupData] = useState<TwoFactorSetupDto | null>(null);
  const [confirmCode, setConfirmCode] = useState('');
  const [recoveryCodes, setRecoveryCodes] = useState<string[] | null>(null);
  const [disableOpen, setDisableOpen] = useState(false);
  const [regenerateOpen, setRegenerateOpen] = useState(false);

  const startEnroll = async () => {
    try {
      const data = await setup.mutateAsync();
      setSetupData(data);
      setConfirmCode('');
      setView('enrolling');
    } catch {
      // Surfaced by the mutation's error toast.
    }
  };

  const confirmEnable = async (event: React.FormEvent) => {
    event.preventDefault();
    const code = confirmCode.trim();
    if (!code) {
      return;
    }
    try {
      const result = await enable.mutateAsync(code);
      // Reload the authoritative flag so the resting view under the recovery
      // step is "enabled" (manage) once the codes are dismissed.
      await refreshMe();
      setRecoveryCodes(result.recoveryCodes ?? []);
      setSetupData(null);
      setConfirmCode('');
      setView('recovery');
    } catch {
      // Surfaced by the mutation's error toast; stay on the confirm step.
    }
  };

  const handleDisable = async (password: string) => {
    try {
      await disable.mutateAsync(password);
      // Reload `twoFactorEnabled` so the card flips back to the enable flow.
      await refreshMe();
      setDisableOpen(false);
      setView('idle');
    } catch {
      // Wrong password / other reason is surfaced by the toast; keep the dialog.
    }
  };

  const handleRegenerate = async (password: string) => {
    try {
      const result = await regenerate.mutateAsync(password);
      setRegenerateOpen(false);
      setRecoveryCodes(result.recoveryCodes ?? []);
      setView('recovery');
    } catch {
      // Surfaced by the toast; keep the dialog open to retry.
    }
  };

  return (
    <Card>
      <CardHeader className="py-5">
        <CardHeading>
          <CardTitle>
            <FormattedMessage id="profile.twoFactor.title" />
          </CardTitle>
          <CardDescription>
            <FormattedMessage id="profile.twoFactor.description" />
          </CardDescription>
        </CardHeading>
      </CardHeader>

      <CardContent className="flex max-w-md flex-col gap-5 py-5">
        {view === 'idle' && (
          <>
            <Badge
              variant={enabled ? 'success' : 'secondary'}
              appearance="light"
              className="w-fit"
            >
              <FormattedMessage
                id={
                  enabled
                    ? 'profile.twoFactor.statusEnabled'
                    : 'profile.twoFactor.statusDisabled'
                }
              />
            </Badge>

            {enabled ? (
              <div className="flex flex-col gap-4">
                <p className="text-sm text-muted-foreground">
                  <FormattedMessage id="profile.twoFactor.manageDescription" />
                </p>
                <div className="flex flex-wrap gap-3">
                  <Button
                    type="button"
                    variant="outline"
                    onClick={() => setDisableOpen(true)}
                  >
                    <FormattedMessage id="profile.twoFactor.disableButton" />
                  </Button>
                  <Button
                    type="button"
                    variant="outline"
                    onClick={() => setRegenerateOpen(true)}
                  >
                    <FormattedMessage id="profile.twoFactor.regenerateButton" />
                  </Button>
                </div>
              </div>
            ) : (
              <div className="flex flex-col gap-4">
                <p className="text-sm text-muted-foreground">
                  <FormattedMessage id="profile.twoFactor.disabledHint" />
                </p>
                <div className="flex flex-wrap gap-3">
                  <Button
                    type="button"
                    onClick={startEnroll}
                    disabled={setup.isPending}
                  >
                    {setup.isPending && (
                      <LoaderCircle className="size-4 animate-spin" />
                    )}
                    <ShieldCheck className="size-4" aria-hidden />
                    <FormattedMessage id="profile.twoFactor.enableButton" />
                  </Button>
                </div>
              </div>
            )}
          </>
        )}

        {view === 'enrolling' && setupData && (
          <form onSubmit={confirmEnable} className="flex flex-col gap-5" noValidate>
            <p className="text-sm text-muted-foreground">
              <FormattedMessage id="profile.twoFactor.scanInstruction" />
            </p>

            {setupData.otpauthUri && (
              <div className="w-fit rounded-md border bg-white p-3">
                <QRCodeSVG
                  value={setupData.otpauthUri}
                  size={160}
                  aria-label={intl.formatMessage({
                    id: 'profile.twoFactor.qrAlt',
                  })}
                />
              </div>
            )}

            <div className="flex flex-col gap-2">
              <span className="text-sm text-muted-foreground">
                <FormattedMessage id="profile.twoFactor.manualInstruction" />
              </span>
              <div className="flex items-center gap-2">
                <code
                  data-testid="two-factor-secret"
                  className="grow rounded-md border bg-muted px-3 py-2 font-mono text-sm break-all select-all"
                >
                  {setupData.secret}
                </code>
                <CopyButton
                  value={setupData.secret ?? ''}
                  labelId="profile.twoFactor.copySecret"
                />
              </div>
            </div>

            <div className="flex flex-col gap-2">
              <Label htmlFor="two-factor-confirm">
                <FormattedMessage id="profile.twoFactor.confirmLabel" />
              </Label>
              <InputOTP
                id="two-factor-confirm"
                maxLength={6}
                value={confirmCode}
                onChange={setConfirmCode}
                inputMode="numeric"
                // No password-manager value in a 6-digit code; disabling the
                // badge sync avoids its `elementFromPoint` timers (also keeps
                // jsdom behaviour tests clean).
                pushPasswordManagerStrategy="none"
                aria-label={intl.formatMessage({
                  id: 'profile.twoFactor.confirmLabel',
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

            <div className="flex flex-wrap gap-3">
              <Button
                type="submit"
                disabled={enable.isPending || confirmCode.trim().length === 0}
              >
                {enable.isPending && (
                  <LoaderCircle className="size-4 animate-spin" />
                )}
                <FormattedMessage
                  id={
                    enable.isPending
                      ? 'profile.twoFactor.confirming'
                      : 'profile.twoFactor.confirmButton'
                  }
                />
              </Button>
              <Button
                type="button"
                variant="outline"
                disabled={enable.isPending}
                onClick={() => {
                  setView('idle');
                  setSetupData(null);
                  setConfirmCode('');
                }}
              >
                <FormattedMessage id="profile.twoFactor.cancel" />
              </Button>
            </div>
          </form>
        )}

        {view === 'recovery' && recoveryCodes && (
          <div className="flex flex-col gap-4">
            <Alert variant="warning" appearance="light" size="sm">
              <AlertIcon>
                <TriangleAlert />
              </AlertIcon>
              <AlertContent>
                <AlertTitle>
                  <FormattedMessage id="profile.twoFactor.recoveryTitle" />
                </AlertTitle>
                <AlertDescription>
                  <FormattedMessage id="profile.twoFactor.recoveryWarning" />
                </AlertDescription>
              </AlertContent>
            </Alert>

            <ul
              data-testid="recovery-codes"
              className="grid grid-cols-2 gap-2 rounded-md border bg-muted p-3 font-mono text-sm"
            >
              {recoveryCodes.map((recoveryCode) => (
                <li key={recoveryCode} className="break-all select-all">
                  {recoveryCode}
                </li>
              ))}
            </ul>

            <div className="flex flex-wrap gap-3">
              <CopyButton
                value={recoveryCodes.join('\n')}
                labelId="profile.twoFactor.copyAll"
              />
              <Button
                type="button"
                onClick={() => {
                  setRecoveryCodes(null);
                  setView('idle');
                }}
              >
                <FormattedMessage id="profile.twoFactor.recoveryDone" />
              </Button>
            </div>
          </div>
        )}
      </CardContent>

      <PasswordConfirmDialog
        open={disableOpen}
        onOpenChange={setDisableOpen}
        title={<FormattedMessage id="profile.twoFactor.disableDialogTitle" />}
        description={
          <FormattedMessage id="profile.twoFactor.disableDialogDescription" />
        }
        submitLabelId="profile.twoFactor.disableConfirm"
        pending={disable.isPending}
        onConfirm={handleDisable}
      />

      <PasswordConfirmDialog
        open={regenerateOpen}
        onOpenChange={setRegenerateOpen}
        title={
          <FormattedMessage id="profile.twoFactor.regenerateDialogTitle" />
        }
        description={
          <FormattedMessage id="profile.twoFactor.regenerateDialogDescription" />
        }
        submitLabelId="profile.twoFactor.regenerateConfirm"
        pending={regenerate.isPending}
        onConfirm={handleRegenerate}
      />
    </Card>
  );
}

/** Copy-to-clipboard button with a transient "copied" confirmation. */
function CopyButton({
  value,
  labelId,
}: {
  value: string;
  labelId: string;
}) {
  const { isCopied, copyToClipboard } = useCopyToClipboard();
  return (
    <Button
      type="button"
      variant="outline"
      onClick={() => copyToClipboard(value)}
    >
      {isCopied ? (
        <Check className="size-4" />
      ) : (
        <Copy className="size-4" />
      )}
      <FormattedMessage id={isCopied ? 'profile.twoFactor.copied' : labelId} />
    </Button>
  );
}

/**
 * Password re-authentication dialog for the two sensitive operations (disable,
 * regenerate). Mirrors the backend's re-auth step. The password lives only in
 * this component's state and is cleared each time the dialog opens.
 */
function PasswordConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  submitLabelId,
  pending,
  onConfirm,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: React.ReactNode;
  description: React.ReactNode;
  submitLabelId: string;
  pending: boolean;
  onConfirm: (password: string) => Promise<void>;
}) {
  const [password, setPassword] = useState('');

  useEffect(() => {
    if (open) {
      setPassword('');
    }
  }, [open]);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!password || pending) {
      return;
    }
    await onConfirm(password);
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>

        <form onSubmit={submit} className="flex flex-col gap-4" noValidate>
          <div className="flex flex-col gap-2">
            <Label htmlFor="two-factor-password">
              <FormattedMessage id="profile.twoFactor.passwordLabel" />
            </Label>
            <Input
              id="two-factor-password"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              autoFocus
            />
          </div>

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={pending}
              onClick={() => onOpenChange(false)}
            >
              <FormattedMessage id="profile.twoFactor.cancel" />
            </Button>
            <Button type="submit" disabled={pending || !password}>
              {pending && <LoaderCircle className="size-4 animate-spin" />}
              <FormattedMessage id={submitLabelId} />
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
