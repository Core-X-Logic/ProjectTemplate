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
