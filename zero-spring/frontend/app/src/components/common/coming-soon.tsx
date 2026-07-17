import { Construction } from 'lucide-react';
import { FormattedMessage, useIntl } from 'react-intl';
import { Helmet } from 'react-helmet-async';
import { Card, CardContent } from '@/components/ui/card';

interface ComingSoonProps {
  /** i18n message id for the feature title (e.g. `nav.users`). */
  titleId: string;
}

/**
 * Placeholder for feature modules delivered in a later vertical slice.
 * Routes still sit behind their permission guard, so this validates the RBAC
 * wiring ahead of the real screens.
 */
export function ComingSoon({ titleId }: ComingSoonProps) {
  const intl = useIntl();
  const title = intl.formatMessage({ id: titleId });

  return (
    <div className="container-fluid">
      <Helmet>
        <title>{title}</title>
      </Helmet>
      <Card>
        <CardContent className="flex flex-col items-center justify-center gap-3 py-16 text-center">
          <Construction className="size-10 text-muted-foreground" />
          <h1 className="text-xl font-semibold text-mono">{title}</h1>
          <p className="text-sm text-muted-foreground">
            <FormattedMessage id="common.comingSoon" /> —{' '}
            <FormattedMessage id="dashboard.comingSoonSlice" />
          </p>
        </CardContent>
      </Card>
    </div>
  );
}
