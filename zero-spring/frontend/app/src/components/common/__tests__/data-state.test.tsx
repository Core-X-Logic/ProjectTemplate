import { Users } from 'lucide-react';
import { IntlProvider } from 'react-intl';
import type { ReactElement } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import enMessages from '@/i18n/messages/en';
import {
  DataEmpty,
  DataError,
  TableSkeleton,
} from '@/components/common/data-state';
import { PageHeader } from '@/components/common/page-header';
import { Button } from '@/components/ui/button';

/**
 * Behaviour tests for the shared presentation seam (page-header + the four-state
 * data bodies). They assert what a user perceives — a single page-level heading,
 * an empty state that invites action, and an error surface that is announced and
 * retryable — not implementation details.
 */

function renderIntl(ui: ReactElement) {
  return render(
    <IntlProvider locale="en" messages={enMessages}>
      {ui}
    </IntlProvider>,
  );
}

describe('PageHeader', () => {
  it('emits the title as the single page-level h1 with description and actions', () => {
    renderIntl(
      <PageHeader
        title="Users"
        description="Manage the user accounts."
        actions={<Button>Add user</Button>}
      />,
    );

    expect(
      screen.getByRole('heading', { level: 1, name: 'Users' }),
    ).toBeInTheDocument();
    expect(screen.getByText('Manage the user accounts.')).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Add user' }),
    ).toBeInTheDocument();
  });
});

describe('DataEmpty', () => {
  it('renders an invitation to act — title, description and primary action', () => {
    renderIntl(
      <DataEmpty
        icon={<Users />}
        title="No users found."
        description="Add the first user to get started."
        action={<Button>Create</Button>}
      />,
    );

    expect(screen.getByText('No users found.')).toBeInTheDocument();
    expect(
      screen.getByText('Add the first user to get started.'),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Create' })).toBeInTheDocument();
  });
});

describe('DataError', () => {
  it('announces the message via role=alert and retries on click', async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();

    renderIntl(
      <DataError message="Users could not be loaded." onRetry={onRetry} />,
    );

    expect(screen.getByRole('alert')).toHaveTextContent(
      'Users could not be loaded.',
    );
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('omits the Retry button when no handler is supplied', () => {
    renderIntl(<DataError message="Something went wrong." />);

    expect(
      screen.queryByRole('button', { name: 'Retry' }),
    ).not.toBeInTheDocument();
  });
});

describe('TableSkeleton', () => {
  it('exposes a loading status to assistive technology', () => {
    renderIntl(<TableSkeleton rows={3} cols={4} />);

    expect(screen.getByRole('status')).toBeInTheDocument();
  });
});
