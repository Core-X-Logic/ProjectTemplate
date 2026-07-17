import {
  createContext,
  ReactNode,
  useCallback,
  useContext,
  useMemo,
  useState,
} from 'react';
import { IntlProvider } from 'react-intl';
import { LOCALE_STORAGE_KEY } from '@/api/client';
import en from '@/i18n/messages/en';
import tr from '@/i18n/messages/tr';

export type Locale = 'en' | 'tr';

const MESSAGES: Record<Locale, Record<string, string>> = { en, tr };
export const AVAILABLE_LOCALES: Locale[] = ['en', 'tr'];

const DEFAULT_LOCALE: Locale = ((): Locale => {
  const configured = import.meta.env.VITE_DEFAULT_LOCALE;
  return configured === 'tr' ? 'tr' : 'en';
})();

function readStoredLocale(): Locale {
  try {
    const stored = localStorage.getItem(LOCALE_STORAGE_KEY);
    if (stored === 'en' || stored === 'tr') {
      return stored;
    }
  } catch {
    /* ignore */
  }
  return DEFAULT_LOCALE;
}

interface LocaleContextValue {
  locale: Locale;
  setLocale: (locale: Locale) => void;
  availableLocales: Locale[];
}

const LocaleContext = createContext<LocaleContextValue | undefined>(undefined);

/**
 * react-intl provider with runtime locale switching (FRONTEND-ARCHITECTURE.md §6).
 * The selected locale is persisted to localStorage and mirrored onto
 * `<html lang>`; `apiFetch` reads the same key for the `Accept-Language` header.
 */
export function I18nProvider({ children }: { children: ReactNode }) {
  const [locale, setLocaleState] = useState<Locale>(() => readStoredLocale());

  const setLocale = useCallback((next: Locale) => {
    try {
      localStorage.setItem(LOCALE_STORAGE_KEY, next);
    } catch {
      /* ignore */
    }
    if (typeof document !== 'undefined') {
      document.documentElement.lang = next;
    }
    setLocaleState(next);
  }, []);

  const value = useMemo<LocaleContextValue>(
    () => ({ locale, setLocale, availableLocales: AVAILABLE_LOCALES }),
    [locale, setLocale],
  );

  return (
    <LocaleContext.Provider value={value}>
      <IntlProvider
        locale={locale}
        defaultLocale="en"
        messages={MESSAGES[locale]}
        onError={(err) => {
          if (err.code !== 'MISSING_TRANSLATION') {
            console.error(err);
          }
        }}
      >
        {children}
      </IntlProvider>
    </LocaleContext.Provider>
  );
}

export function useLocale(): LocaleContextValue {
  const context = useContext(LocaleContext);
  if (!context) {
    throw new Error('useLocale must be used within an I18nProvider');
  }
  return context;
}
