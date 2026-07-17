import { ChevronDown, LoaderCircle } from 'lucide-react';
import { useIntl } from 'react-intl';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { useRoleOptions } from '@/features/users/hooks';

interface RoleSelectProps {
  /** Selected role names (`RoleDto.name`). */
  value: string[];
  onChange: (roleNames: string[]) => void;
  disabled?: boolean;
  id?: string;
}

/**
 * Simple multi-select over the tenant's roles (`GET /api/roles`).
 * Selection travels as role *names* — the shape `AssignRolesRequest` and
 * `CreateUserRequest.roleNames` expect.
 */
export function RoleSelect({ value, onChange, disabled, id }: RoleSelectProps) {
  const intl = useIntl();
  const { data: roles = [], isLoading } = useRoleOptions();

  const toggle = (roleName: string, checked: boolean) => {
    onChange(
      checked
        ? [...value, roleName]
        : value.filter((name) => name !== roleName),
    );
  };

  const label =
    value.length > 0
      ? value.join(', ')
      : intl.formatMessage({ id: 'users.form.rolesPlaceholder' });

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          id={id}
          type="button"
          variant="outline"
          disabled={disabled}
          className="w-full justify-between font-normal"
        >
          <span className="truncate">{label}</span>
          {isLoading ? (
            <LoaderCircle className="size-4 animate-spin" />
          ) : (
            <ChevronDown className="size-4 opacity-60" />
          )}
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent
        align="start"
        className="w-(--radix-dropdown-menu-trigger-width) max-h-64 overflow-y-auto"
      >
        {roles.map((role) => {
          const name = role.name ?? '';
          return (
            <DropdownMenuCheckboxItem
              key={role.id ?? name}
              checked={value.includes(name)}
              onCheckedChange={(checked) => toggle(name, checked === true)}
              onSelect={(event) => event.preventDefault()}
            >
              {role.displayName ?? name}
            </DropdownMenuCheckboxItem>
          );
        })}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
