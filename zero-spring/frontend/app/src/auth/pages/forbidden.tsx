import { ShieldX } from 'lucide-react';
import { Link } from 'react-router-dom';
import { FormattedMessage } from 'react-intl';
import { Helmet } from 'react-helmet-async';
import { Button } from '@/components/ui/button';

/** 403 — authenticated but not authorized (FRONTEND-ARCHITECTURE.md §5). */
export function ForbiddenPage() {
  return (
    <div className="flex grow flex-col items-center justify-center gap-4 min-h-[60vh] px-5 text-center">
      <Helmet>
        <title>403</title>
      </Helmet>
      <ShieldX className="size-12 text-destructive" />
      <div className="space-y-1.5">
        <h1 className="text-2xl font-semibold text-mono">
          <FormattedMessage id="forbidden.title" />
        </h1>
        <p className="text-sm text-muted-foreground max-w-md">
          <FormattedMessage id="forbidden.description" />
        </p>
      </div>
      <Button asChild variant="mono">
        <Link to="/">
          <FormattedMessage id="forbidden.back" />
        </Link>
      </Button>
    </div>
  );
}
