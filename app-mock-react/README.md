# app-mock-react

Browser-based mock of the mobile app, simulating the full device registration and login flow against the local `device-login` backend.

Built with **React 19 / TypeScript / Vite / Tailwind CSS**.

---

## Purpose

Provides a browser UI to manually test the authentication flow end-to-end without a real mobile device:

1. **Device registration** — generate an RSA key pair in the browser, register the device with the backend using an activation code
2. **Login** — request a challenge, sign it with the stored private key (simulated biometric gate via a modal), and exchange the signed challenge for OIDC tokens

---

## Screens

| Screen | Description |
|---|---|
| `HomeScreen` | Registered device: shows login button and token state |
| `UnregisteredScreen` | No device registered: shows registration form |
| `AuthenticatedScreen` | After successful login: shows decoded OIDC tokens |

---

## Structure

```
src/
├── App.tsx               # Root component — screen routing based on device state
├── screens/
│   ├── HomeScreen.tsx
│   ├── UnregisteredScreen.tsx
│   └── AuthenticatedScreen.tsx
├── components/
│   ├── ActivityLog.tsx   # Scrollable log of API calls and events
│   ├── BiometricModal.tsx # Simulated biometric/PIN confirmation dialog
│   └── ui.tsx            # Shared UI primitives
├── services/
│   ├── api.ts            # device-login REST API calls
│   ├── crypto.ts         # Web Crypto API — RSA key generation and signing
│   └── storage.ts        # localStorage persistence for keys and device state
├── hooks/
│   └── useLog.ts         # Activity log state hook
└── types/
    └── index.ts          # Shared TypeScript types
```

---

## Build

```bash
npm install
npm run build   # output → dist/ (served by Caddy via volume mount)
```

No container restart is needed after rebuilding — Caddy serves `dist/` live.

The app is available at **https://app-mock.localhost:8443** when the Podman Compose stack is running.

## Dev server (optional)

```bash
npm run dev     # Vite dev server on http://localhost:5173
```

Note: in dev mode, API calls to `/api/*` are not proxied. Use the built `dist/` served by Caddy for full end-to-end testing.
