import { FileQuestion } from 'lucide-react';
import { Link } from 'react-router-dom';
import { FormattedMessage } from 'react-intl';
import { Helmet } from 'react-helmet-async';
import { Button } from '@/components/ui/button';

/** 404 — no route matched. */
export function NotFoundPage() {
  return (
    <div className="flex grow flex-col items-center justify-center gap-4 min-h-screen px-5 text-center">
      <Helmet>
        <title>404</title>
      </Helmet>
      <FileQuestion className="size-12 text-muted-foreground" />
      <div className="space-y-1.5">
        <h1 className="text-2xl font-semibold text-mono">
          <FormattedMessage id="notFound.title" />
        </h1>
        <p className="text-sm text-muted-foreground max-w-md">
          <FormattedMessage id="notFound.description" />
        </p>
      </div>
      <Button asChild variant="mono">
        <Link to="/">
          <FormattedMessage id="notFound.back" />
        </Link>
      </Button>
    </div>
  );
}
