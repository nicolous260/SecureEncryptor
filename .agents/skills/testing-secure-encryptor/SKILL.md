---
name: testing-secure-encryptor
description: Test the SecureEncryptor PWA end-to-end. Use when verifying encryption/decryption, UI alerts, password strength, manifest, or service worker changes.
---

# Testing SecureEncryptor

## Local Dev Setup

This is a static PWA — no build step required. Serve files locally:

```bash
cd /path/to/SecureEncryptor
python -m http.server 8000
# or: npx serve .
```

Then open http://localhost:8000. Note: Web Crypto API requires HTTPS in production, but works on localhost.

## Netlify Preview

PRs automatically get a Netlify deploy preview. Check PR comments for the preview URL (format: `https://deploy-preview-{N}--secure-encryptor.netlify.app`).

## Key Test Flows

### 1. Password Strength Meter
- Type a short password (e.g., `abc`) — expect "Very Weak" / red
- Type a complex password (e.g., `MyStr0ng!Pass99` — length>12, uppercase, lowercase, digit, special) — expect "Very Strong" / green
- Check browser console for errors — there should be none
- The strength logic is in `js/crypto.js` (`checkPasswordStrength`) and the UI handler is in `js/app.js` (`updatePasswordStrength`)

### 2. Alert Visibility
- **Error alert**: Enter mismatched passwords, click Encrypt → red banner "Passwords do not match" should appear
- **Success alert**: Enter matching passwords (min 6 chars) + text, click Encrypt → green banner "Text encrypted successfully!"
- Alerts auto-hide after 5 seconds
- Alert CSS: `.alert.active` controls visibility, `.alert-success` / `.alert-error` control colors

### 3. Encrypt/Decrypt Roundtrip
- Enter matching passwords + plaintext, click Encrypt
- Copy the Base64 output, paste into input, click Decrypt
- Verify decrypted text matches original

### 4. File Encryption
- Switch to Files tab
- Drag/drop or select a file
- Encrypt with matching passwords → downloads `.encrypted` file
- Select the encrypted file, same password → downloads original file

### 5. Manifest & PWA
- DevTools > Application > Manifest — verify icon loads (SVG)
- Check console for 404 errors on icon files
- The manifest references `assets/icon.svg` — only this SVG exists in the assets folder

### 6. Service Worker
- DevTools > Application > Service Workers — verify SW is registered
- DevTools > Application > Cache Storage — verify `secure-encryptor-v2` cache contains static assets including `offline.html`

## Architecture Notes
- `js/crypto.js` — Encryption engine (Web Crypto API, AES-256-GCM, PBKDF2)
- `js/app.js` — UI logic, event handling, module pattern (IIFE)
- `css/style.css` — All styles including alert visibility rules
- `sw.js` — Service worker with stale-while-revalidate caching
- `manifest.json` — PWA manifest with SVG icon

## Common Pitfalls
- Password strength handler: there should be only ONE input listener on the password field (inside the `app` IIFE). Watch for duplicate handlers appended outside the module.
- Alert visibility: `.alert` has `display:none` by default. The `.alert.active` rule must exist to make alerts visible.
- Manifest icons: only `assets/icon.svg` exists. Do not reference PNG icon files without adding them first.

## Devin Secrets Needed
None — this is a fully client-side app with no authentication required.
