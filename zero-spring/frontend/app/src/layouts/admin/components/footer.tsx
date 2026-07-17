import { useIntl } from 'react-intl';

export function Footer() {
  const intl = useIntl();
  const currentYear = new Date().getFullYear();
  const appName = intl.formatMessage({ id: 'app.name' });

  return (
    <footer className="footer">
      <div className="container">
        <div className="flex flex-col md:flex-row justify-center md:justify-between items-center gap-3 py-5">
          <div className="flex order-2 md:order-1 gap-2 font-normal text-sm">
            <span className="text-muted-foreground">
              {currentYear} &copy; {appName}
            </span>
          </div>
        </div>
      </div>
    </footer>
  );
}
