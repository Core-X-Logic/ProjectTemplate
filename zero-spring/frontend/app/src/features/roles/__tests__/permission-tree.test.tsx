import { useState } from 'react';
import { PermissionTree } from '@/features/roles/components/permission-tree';
import { rolesMessages } from '@/features/roles/messages';
import type { PermissionNodeDto } from '@/features/roles/types';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { IntlProvider } from 'react-intl';
import { describe, expect, it, vi } from 'vitest';

/**
 * Permission tree behaviour tests: toggling a parent node selects/clears the
 * whole subtree; partially selected parents render indeterminate.
 */

const nodes: PermissionNodeDto[] = [
  {
    name: 'users',
    displayName: 'User Management',
    children: [
      { name: 'users.read', displayName: 'Read users', children: [] },
      { name: 'users.create', displayName: 'Create users', children: [] },
    ],
  },
  { name: 'roles.read', displayName: 'Read roles', children: [] },
];

interface HarnessProps {
  initial?: string[];
  onChange: (next: string[]) => void;
}

function Harness({ initial = [], onChange }: HarnessProps) {
  const [value, setValue] = useState<string[]>(initial);
  return (
    <PermissionTree
      nodes={nodes}
      value={value}
      onChange={(next) => {
        setValue(next);
        onChange(next);
      }}
    />
  );
}

function renderTree(props: HarnessProps) {
  return render(
    <IntlProvider locale="en" messages={rolesMessages.en}>
      <Harness {...props} />
    </IntlProvider>,
  );
}

describe('PermissionTree', () => {
  it('checking a parent selects the node and all of its children', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderTree({ onChange });

    await user.click(screen.getByRole('checkbox', { name: 'User Management' }));

    expect(onChange).toHaveBeenLastCalledWith([
      'users',
      'users.read',
      'users.create',
    ]);
    expect(screen.getByRole('checkbox', { name: 'Read users' })).toBeChecked();
    expect(
      screen.getByRole('checkbox', { name: 'Create users' }),
    ).toBeChecked();
    // Sibling subtree stays untouched.
    expect(
      screen.getByRole('checkbox', { name: 'Read roles' }),
    ).not.toBeChecked();
  });

  it('unchecking a fully selected parent clears its whole subtree only', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderTree({
      initial: ['users', 'users.read', 'users.create', 'roles.read'],
      onChange,
    });

    await user.click(screen.getByRole('checkbox', { name: 'User Management' }));

    expect(onChange).toHaveBeenLastCalledWith(['roles.read']);
    expect(screen.getByRole('checkbox', { name: 'Read roles' })).toBeChecked();
  });

  it('renders the parent indeterminate when only part of the subtree is selected', () => {
    renderTree({ initial: ['users.read'], onChange: vi.fn() });

    expect(
      screen.getByRole('checkbox', { name: 'User Management' }),
    ).toHaveAttribute('aria-checked', 'mixed');
  });

  it('toggling a leaf only affects that permission', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderTree({ onChange });

    await user.click(screen.getByRole('checkbox', { name: 'Read users' }));

    expect(onChange).toHaveBeenLastCalledWith(['users.read']);
  });
});
