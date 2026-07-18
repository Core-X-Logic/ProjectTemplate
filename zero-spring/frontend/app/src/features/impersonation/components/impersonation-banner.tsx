import { ApiError } from '@/api/client';
import { ArrowLeft, VenetianMask } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';
import { useAuth } from '@/providers/auth-provider';
import {
  Alert,
  AlertContent,
  AlertIcon,
  AlertTitle,
  AlertToolbar,
} from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { useImpersonationMessages } from '../messages';

/**
 * Fixed top banner shown while the session is an impersonation of another user.
 *
 * Renders `null` unless `auth.isImpersonating` is true. The "Back to my account"
 * action calls `auth.backToImpersonator()`, toasts the result and returns to the
 * dashboard so the restored identity lands on a permitted page.
 *
 * INTEGRATION NOTE: mount once near the top of the admin shell (e.g. above the
 * header in `layouts/admin`). It is self-contained and only needs the router,
 * auth and intl providers.
 */
export function ImpersonationBanner() {
  const auth = useAuth();
  const navigate = useNavigate();
  const t = useImpersonationMessages();

  if (!auth.isImpersonating) {
    return null;
  }

  const handleBack = async () => {
    try {
      await auth.backToImpersonator();
      toast.success(t('impersonation.backSuccess'));
      navigate('/');
    } catch (error) {
      toast.error(t('impersonation.error'), {
        description: error instanceof ApiError ? error.detail : undefined,
      });
    }
  };

  return (
    <div className="fixed inset-x-0 top-0 z-50 p-2">
      <Alert variant="warning" appearance="light" size="sm">
        <AlertIcon>
          <VenetianMask />
        </AlertIcon>
        <AlertContent className="flex flex-wrap items-center gap-2">
          <Badge variant="warning" size="sm" appearance="light">
            {t('impersonation.banner.badge')}
          </Badge>
          <AlertTitle>
            {t('impersonation.banner.title', {
              username: auth.user?.username ?? '',
            })}
          </AlertTitle>
        </AlertContent>
        <AlertToolbar>
          <Button type="button" variant="mono" size="sm" onClick={handleBack}>
            <ArrowLeft />
            {t('impersonation.banner.back')}
          </Button>
        </AlertToolbar>
      </Alert>
    </div>
  );
}
