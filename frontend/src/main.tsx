import { StrictMode, useSyncExternalStore } from 'react';
import { createRoot } from 'react-dom/client';
import { createApiClient } from './api/client.ts';
import { AppController } from './app/controller.ts';
import { AppView } from './ui/AppView.tsx';
import './style.css';

// Outside React: StrictMode remounts must not create another paid operation or timer.
const controller = new AppController({
  api: createApiClient(),
  storage: { getItem: key => localStorage.getItem(key), setItem: (key, value) => localStorage.setItem(key, value) },
  path: window.location.pathname,
  visible: () => document.visibilityState === 'visible',
  copy: text => navigator.clipboard ? navigator.clipboard.writeText(text) : Promise.reject(new Error('Clipboard unavailable')),
  navigate: path => { window.location.assign(path); },
  locked: true,
});

function App() {
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot);
  return <AppView state={state} actions={controller.actions} />;
}

createRoot(document.getElementById('root')!).render(<StrictMode><App /></StrictMode>);

// A route has one writable tab. Browser releases the lock on unload/crash.
// No session cookie is copied into local storage. Unsupported browsers stay read-only.
if (navigator.locks) {
  void navigator.locks.request(`worldcup:tab:${window.location.pathname}`, { ifAvailable: true }, async lock => {
    if (!lock) return;
    controller.setLocked(false);
    void controller.resume();
    await new Promise<void>(resolve => {
      window.addEventListener('pagehide', () => { controller.dispose(); resolve(); }, { once: true });
    });
  });
}
const ticker = window.setInterval(() => controller.tick(), 50);
document.addEventListener('visibilitychange', () => controller.tick());
window.addEventListener('storage', () => controller.storageChanged());
window.addEventListener('pagehide', () => window.clearInterval(ticker), { once: true });
window.addEventListener('pageshow', event => { if (event.persisted) window.location.reload(); });
