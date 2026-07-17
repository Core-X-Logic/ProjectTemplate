import { FormattedMessage } from 'react-intl';
import { Helmet } from 'react-helmet-async';
import { useAuth } from '@/providers/auth-provider';
import { useTenant } from '@/providers/tenant-provider';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';

/** Dashboard landing page (FRONTEND-ARCHITECTURE.md §8). */
export function DashboardPage() {
  const { user } = useAuth();
  const { tenant } = useTenant();

  const displayName = user?.username ?? user?.email ?? '';
  const tenantLabel = tenant ?? user?.tenantId ?? '—';

  return (
    <div className="container-fluid">
      <Helmet>
        <title>Dashboard</title>
      </Helmet>

      <Card>
        <CardHeader className="flex-col items-stretch gap-1.5 py-6">
          <CardTitle className="text-xl">
            <FormattedMessage
              id="dashboard.welcome"
              values={{ name: displayName }}
            />
          </CardTitle>
          <CardDescription>
            <FormattedMessage id="dashboard.comingSoonSlice" />
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-2 py-6">
          <div className="flex items-center gap-2 text-sm">
            <span className="text-muted-foreground">
              <FormattedMessage id="dashboard.tenantLabel" />:
            </span>
            <span className="font-medium text-mono">{tenantLabel}</span>
          </div>
          {user?.email && (
            <div className="flex items-center gap-2 text-sm">
              <span className="text-muted-foreground">Email:</span>
              <span className="font-medium text-mono">{user.email}</span>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
