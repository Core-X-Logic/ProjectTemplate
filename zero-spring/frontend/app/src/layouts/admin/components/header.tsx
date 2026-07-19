import { useEffect, useState } from 'react';
import { LogOut, Menu, Moon, Sun, UserCircle } from 'lucide-react';
import { useTheme } from 'next-themes';
import { FormattedMessage } from 'react-intl';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { getInitials, toAbsoluteUrl } from '@/lib/helpers';
import { cn } from '@/lib/utils';
import { useAuth } from '@/providers/auth-provider';
import { type Locale, useLocale } from '@/providers/i18n-provider';
import { useIsMobile } from '@/hooks/use-mobile';
import { useScrollPosition } from '@/hooks/use-scroll-position';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import {
  Sheet,
  SheetBody,
  SheetContent,
  SheetHeader,
  SheetTrigger,
} from '@/components/ui/sheet';
import { NotificationBell } from '@/features/notifications/components/notification-bell';
import { Breadcrumb } from './breadcrumb';
import { SidebarMenu } from './sidebar-menu';

const LOCALE_LABELS: Record<Locale, string> = {
  en: 'English',
  tr: 'Türkçe',
};

function LanguageSwitcher() {
  const { locale, setLocale, availableLocales } = useLocale();

  return (
    <Select
      value={locale}
      onValueChange={(value) => setLocale(value as Locale)}
      indicatorVisibility={false}
    >
      <SelectTrigger size="sm" className="w-auto gap-1.5">
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        {availableLocales.map((code) => (
          <SelectItem key={code} value={code}>
            {LOCALE_LABELS[code]}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}

function ThemeToggle() {
  const { resolvedTheme, setTheme } = useTheme();
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setMounted(true);
  }, []);

  const isDark = resolvedTheme === 'dark';

  return (
    <Button
      variant="ghost"
      mode="icon"
      shape="circle"
      aria-label="Toggle theme"
      onClick={() => setTheme(isDark ? 'light' : 'dark')}
      className="size-9"
    >
      {mounted && isDark ? (
        <Sun className="size-4.5!" />
      ) : (
        <Moon className="size-4.5!" />
      )}
    </Button>
  );
}

function UserMenu() {
  const navigate = useNavigate();
  const { user, logout } = useAuth();

  const handleLogout = async () => {
    await logout();
    navigate('/login', { replace: true });
  };

  const name = user?.username ?? user?.email ?? '';

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          type="button"
          className="flex size-9 items-center justify-center rounded-full border-2 border-green-500 bg-muted text-xs font-semibold text-mono uppercase shrink-0 cursor-pointer"
          aria-label={name}
        >
          {getInitials(name, 2) || 'U'}
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-56">
        <DropdownMenuLabel className="flex flex-col gap-0.5">
          <span className="text-sm font-medium text-mono">{name}</span>
          {user?.email && (
            <span className="text-xs font-normal text-muted-foreground">
              {user.email}
            </span>
          )}
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        {/* Own profile + password change — available to every authenticated
            user, so no permission guard (matches the route and the backend's
            `@PreAuthorize("isAuthenticated()")` on ProfileController). */}
        <DropdownMenuItem asChild className="cursor-pointer">
          <Link to="/profile">
            <UserCircle />
            <FormattedMessage id="nav.profile" />
          </Link>
        </DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuItem onClick={handleLogout} className="cursor-pointer">
          <LogOut />
          <FormattedMessage id="auth.logout" />
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

export function Header() {
  const [isSidebarSheetOpen, setIsSidebarSheetOpen] = useState(false);

  const { pathname } = useLocation();
  const mobileMode = useIsMobile();

  const scrollPosition = useScrollPosition();
  const headerSticky: boolean = scrollPosition > 0;

  // Close the mobile sidebar sheet on navigation.
  useEffect(() => {
    setIsSidebarSheetOpen(false);
  }, [pathname]);

  return (
    <header
      className={cn(
        'header fixed top-0 z-10 start-0 flex items-stretch shrink-0 border-b border-transparent bg-background end-0 pe-[var(--removed-body-scroll-bar-size,0px)]',
        headerSticky && 'border-b border-border',
      )}
    >
      <div className="container-fluid flex justify-between items-stretch lg:gap-4">
        {/* Mobile logo + sidebar trigger */}
        <div className="flex lg:hidden items-center gap-2.5">
          <Link to="/" className="shrink-0">
            <img
              src={toAbsoluteUrl('/media/app/mini-logo.svg')}
              className="h-[25px] w-full"
              alt="logo"
            />
          </Link>
          {mobileMode && (
            <Sheet
              open={isSidebarSheetOpen}
              onOpenChange={setIsSidebarSheetOpen}
            >
              <SheetTrigger asChild>
                <Button variant="ghost" mode="icon">
                  <Menu className="text-muted-foreground/70" />
                </Button>
              </SheetTrigger>
              <SheetContent
                className="p-0 gap-0 w-[275px]"
                side="left"
                close={false}
              >
                <SheetHeader className="p-0 space-y-0" />
                <SheetBody className="p-0 overflow-y-auto">
                  <SidebarMenu />
                </SheetBody>
              </SheetContent>
            </Sheet>
          )}
        </div>

        {/* Desktop breadcrumb */}
        {!mobileMode && (
          <div className="flex items-center">
            <Breadcrumb />
          </div>
        )}

        {/* Topbar */}
        <div className="flex items-center gap-2">
          <LanguageSwitcher />
          <NotificationBell />
          <ThemeToggle />
          <UserMenu />
        </div>
      </div>
    </header>
  );
}
