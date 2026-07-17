import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NotificationBell } from '@/features/notifications/components/notification-bell';
import { NotificationsInboxPage } from '@/features/notifications/pages/notifications-inbox';
import type {
  NotificationDto,
  NotificationPage,
} from '@/features/notifications/types';
import { renderWithProviders, screen, waitFor } from '@/test/utils';

/**
 * Behaviour tests for the notifications inbox (FRONTEND-ARCHITECTURE.md §9).
 *
 * The feature's endpoint module is mocked so the tests assert the
 * hook → component contract (list rendering, unread emphasis, mutations,
 * polling counter) without touching the network. `sonner` is stubbed so
 * mutation toasts are inert.
 */

const { listMock, unreadCountMock, markReadMock, markAllReadMock } = vi.hoisted(
  () => ({
    listMock: vi.fn(),
    unreadCountMock: vi.fn(),
    markReadMock: vi.fn(),
    markAllReadMock: vi.fn(),
  }),
);

vi.mock('sonner', () => ({
  toast: { error: vi.fn(), success: vi.fn(), message: vi.fn() },
}));

vi.mock('@/features/notifications/api', () => ({
  listNotifications: listMock,
  getUnreadCount: unreadCountMock,
  markRead: markReadMock,
  markAllRead: markAllReadMock,
}));

const unreadNotification: NotificationDto = {
  id: 1,
  notificationName: 'user.welcome',
  level: 'INFO',
  title: 'Welcome aboard',
  body: 'Your account is ready.',
  isRead: false,
  createdAt: '2026-07-01T10:00:00Z',
};

const readNotification: NotificationDto = {
  id: 2,
  notificationName: 'export.finished',
  level: 'SUCCESS',
  title: 'Export finished',
  body: 'users.csv is ready to download.',
  isRead: true,
  createdAt: '2026-06-30T09:00:00Z',
};

function pageOf(content: NotificationDto[]): NotificationPage {
  return {
    content,
    totalElements: content.length,
    totalPages: 1,
    number: 0,
    size: 10,
    numberOfElements: content.length,
    first: true,
    last: true,
    empty: content.length === 0,
  };
}

beforeEach(() => {
  listMock.mockReset();
  unreadCountMock.mockReset();
  markReadMock.mockReset();
  markAllReadMock.mockReset();
  localStorage.clear();

  listMock.mockResolvedValue(pageOf([unreadNotification, readNotification]));
  unreadCountMock.mockResolvedValue({ count: 3 });
  markReadMock.mockResolvedValue(undefined);
  markAllReadMock.mockResolvedValue(undefined);
});

describe('NotificationsInboxPage', () => {
  it('renders the inbox list with level badges and unread emphasis', async () => {
    renderWithProviders(<NotificationsInboxPage />);

    // Unread row is highlighted via the data attribute; read row is not.
    const unreadRow = (await screen.findByText('Welcome aboard')).closest('tr');
    expect(unreadRow).toHaveAttribute('data-unread', 'true');
    const readRow = screen.getByText('Export finished').closest('tr');
    expect(readRow).not.toHaveAttribute('data-unread');

    // Localized level badges resolve through the message catalogue.
    expect(screen.getByText('Info')).toBeInTheDocument();
    expect(screen.getByText('Success')).toBeInTheDocument();

    // The row action only appears on unread notifications.
    expect(
      screen.getAllByRole('button', { name: 'Mark as read' }),
    ).toHaveLength(1);
  });

  it('calls markRead with the notification id from the row action', async () => {
    const user = userEvent.setup();
    renderWithProviders(<NotificationsInboxPage />);

    await screen.findByText('Welcome aboard');
    await user.click(screen.getByRole('button', { name: 'Mark as read' }));

    await waitFor(() => expect(markReadMock).toHaveBeenCalledTimes(1));
    expect(markReadMock).toHaveBeenCalledWith(1);
  });

  it('calls markAllRead when the toolbar button is clicked', async () => {
    const user = userEvent.setup();
    renderWithProviders(<NotificationsInboxPage />);

    await screen.findByText('Welcome aboard');
    await user.click(
      screen.getByRole('button', { name: 'Mark all as read' }),
    );

    await waitFor(() => expect(markAllReadMock).toHaveBeenCalledTimes(1));
  });
});

describe('NotificationBell', () => {
  it('shows the unread count from useUnreadCount as a badge', async () => {
    renderWithProviders(<NotificationBell />);

    expect(await screen.findByText('3')).toBeInTheDocument();
    expect(unreadCountMock).toHaveBeenCalled();
    expect(
      screen.getByRole('button', { name: 'Notifications' }),
    ).toBeInTheDocument();
  });

  it('hides the badge when there is nothing unread', async () => {
    unreadCountMock.mockResolvedValue({ count: 0 });
    renderWithProviders(<NotificationBell />);

    await waitFor(() => expect(unreadCountMock).toHaveBeenCalled());
    expect(screen.queryByText('0')).not.toBeInTheDocument();
  });
});
