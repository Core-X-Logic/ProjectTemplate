// Registers jest-dom matchers on Vitest's `expect` and augments its
// `Assertion` types (e.g. `toBeInTheDocument`, `toBeDisabled`).
import '@testing-library/jest-dom/vitest';

// jsdom has no `ResizeObserver`, which some Radix primitives (e.g. Checkbox)
// touch during their layout effects. A no-op stub keeps behaviour tests that
// render those components from crashing.
class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}

if (!('ResizeObserver' in globalThis)) {
  globalThis.ResizeObserver =
    ResizeObserverStub as unknown as typeof ResizeObserver;
}

// jsdom does not implement `document.elementFromPoint`. `input-otp` calls it on
// a timer to keep the caret in sync; without this stub the resulting
// `TypeError` surfaces as an unhandled error and fails any test that renders an
// OTP field (the 2FA login step and the profile 2FA card).
if (
  typeof document !== 'undefined' &&
  typeof document.elementFromPoint !== 'function'
) {
  document.elementFromPoint = () => null;
}
