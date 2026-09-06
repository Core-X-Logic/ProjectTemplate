import { FormattedMessage } from 'react-intl';
import { Outlet } from 'react-router-dom';
import { toAbsoluteUrl } from '@/lib/helpers';

/**
 * Metronic demo1 "branded" auth layout, adapted for this product.
 *
 * Two-column grid: the auth card (rendered by the child route via <Outlet />)
 * is centered on the left; the right panel carries the brand background image
 * (light/dark variants via the `.branded-bg` style block), the mini logo and a
 * short, product-neutral pitch. On mobile the panel stacks FIRST (order-1) and
 * the form follows — same as the reference layout.
 *
 * Adaptations vs. the vendor file (frontend/vendor is never copied verbatim):
 *  - marketing copy replaced by i18n keys (en + tr, no hardcoded strings);
 *  - `text-mono` / `xxl:` utilities dropped — neither token exists in this
 *    project's Tailwind theme (`--color-mono` and `--breakpoint-xxl` are not
 *    defined in globals.css), so they would silently produce nothing;
 *  - `min-h-screen` added because no flex shell wraps the public routes here
 *    (the old standalone login page carried it itself).
 */
export function BrandedLayout() {
  return (
    <>
      <style>
        {`
          .branded-bg {
            background-image: url('${toAbsoluteUrl('/media/images/2600x1600/1.png')}');
          }
          .dark .branded-bg {
            background-image: url('${toAbsoluteUrl('/media/images/2600x1600/1-dark.png')}');
          }
        `}
      </style>
      <div className="grid lg:grid-cols-2 grow min-h-screen">
        <div className="flex justify-center items-center p-8 lg:p-10 order-2 lg:order-1">
          <Outlet />
        </div>

        <div className="lg:rounded-xl lg:border lg:border-border lg:m-5 order-1 lg:order-2 bg-top xl:bg-center xl:bg-cover bg-no-repeat branded-bg">
          <div className="flex flex-col p-8 lg:p-16 gap-4">
            {/* Decorative logo, deliberately NOT a link: on an anonymous auth
                screen `/` just bounces back to /login, and an image-only link
                with empty alt is a nameless link (WCAG 2.4.4). */}
            <img
              src={toAbsoluteUrl('/media/app/mini-logo.svg')}
              className="h-[28px] max-w-none self-start"
              alt=""
            />

            <div className="flex flex-col gap-3">
              <h3 className="text-2xl font-semibold text-foreground">
                <FormattedMessage id="auth.login.brandedTitle" />
              </h3>
              <div className="text-base font-medium text-secondary-foreground max-w-[400px]">
                <FormattedMessage id="auth.login.brandedDescription" />
              </div>
            </div>
          </div>
        </div>
      </div>
    </>
  );
}
