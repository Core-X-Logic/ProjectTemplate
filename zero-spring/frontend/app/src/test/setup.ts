// Registers jest-dom matchers on Vitest's `expect` and augments its
// `Assertion` types (e.g. `toBeInTheDocument`, `toBeDisabled`).
import '@testing-library/jest-dom/vitest';

// Node >= 25 ships an experimental Web Storage global that SHADOWS jsdom's:
// without `--localstorage-file` the global `localStorage` getter yields
// `undefined`, so any test touching `localStorage` crashes with
// "Cannot read properties of undefined (reading 'clear')". Measured on
// Node 26.5 in a project derived from this template: 146/168 tests and 27/32
// files red. CI pins Node 22 and is unaffected, so the gate never sees it —
// only the developer's machine does. In Vitest's jsdom environment
// `globalThis === window`, so jsdom's own implementation is unreachable once
// Node's getter wins. The getter is configurable, so replace it with an
// in-memory Storage whose lifetime matches jsdom's (per test file).
// `sessionStorage` gets the same treatment: Node's own copy works but would
// outlive the per-file window.
class MemoryStorageStub implements Storage {
  private store = new Map<string, string>();
  get length(): number {
    return this.store.size;
  }
  key(index: number): string | null {
    return [...this.store.keys()][index] ?? null;
  }
  getItem(key: string): string | null {
    return this.store.get(key) ?? null;
  }
  setItem(key: string, value: string): void {
    this.store.set(key, String(value));
  }
  removeItem(key: string): void {
    this.store.delete(key);
  }
  clear(): void {
    this.store.clear();
  }
}

for (const key of ['localStorage', 'sessionStorage'] as const) {
  const existing = globalThis[key] as Storage | undefined;
  // Node 22: `clear` is a function, the stub does not engage and behaviour is
  // unchanged. The check is on the CAPABILITY, not on a version number.
  if (!existing || typeof existing.clear !== 'function') {
    Object.defineProperty(globalThis, key, {
      value: new MemoryStorageStub(),
      writable: true,
      configurable: true,
    });
  }
}

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
