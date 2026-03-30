# Secure Encryptor - Web Version

A Progressive Web App (PWA) implementing military-grade AES-256-GCM encryption that works entirely in your browser. Compatible with the Android app.

## 📁 Project Structure

```
SecureEncryptor-Web/
├── index.html          # Main HTML file (clean, semantic markup)
├── manifest.json       # PWA manifest for installability
├── sw.js              # Service Worker for offline support
├── css/
│   └── style.css      # All styles (variables, responsive, dark mode)
├── js/
│   ├── crypto.js      # Encryption engine (Web Crypto API)
│   └── app.js         # UI logic and event handling
└── assets/
    └── icon.svg       # App icon
```

## 🚀 Quick Start

### Local Development
Simply open `index.html` in any modern browser:
```bash
cd SecureEncryptor-Web
# On macOS
open index.html
# On Linux
xdg-open index.html
# On Windows
start index.html
```

Or use a local server (recommended):
```bash
# Python 3
python -m http.server 8000

# Node.js
npx serve .

# PHP
php -S localhost:8000
```

Then open http://localhost:8000

## 🛠️ Development

### File Organization

**index.html**
- Semantic HTML5 structure
- ARIA accessibility labels
- No inline styles or scripts (clean separation)
- Links to external CSS and JS files

**css/style.css**
- CSS Custom Properties (variables) for theming
- Dark mode support via `prefers-color-scheme`
- Mobile-first responsive design
- Print styles included
- Reduced motion support for accessibility

**js/crypto.js**
- Self-contained module pattern
- Uses Web Crypto API (hardware accelerated)
- Same encryption as Android app:
  - AES-256-GCM
  - PBKDF2-HMAC-SHA256 (100,000 iterations)
  - Compatible file format
- No external dependencies

**js/app.js**
- UI state management
- Event listeners
- DOM manipulation
- File handling (drag & drop)
- PWA registration

**sw.js**
- Caches static assets for offline use
- Update-in-background strategy
- Offline fallback support

**manifest.json**
- PWA configuration
- Icons, theme colors
- Shortcuts for quick actions

## 📱 Deployment

### GitHub Pages
1. Push to GitHub repository
2. Settings → Pages → Source: main branch
3. Site live at `username.github.io/repo-name`

### Netlify
1. Drag and drop folder to [netlify.com](https://netlify.com)
2. Auto-deployed with HTTPS

### Vercel
1. Install Vercel CLI: `npm i -g vercel`
2. Run: `vercel`
3. Follow prompts

### Firebase Hosting
```bash
npm install -g firebase-tools
firebase login
firebase init hosting
firebase deploy
```

## 🔐 Security Notes

- All encryption happens client-side
- No data sent to servers
- HTTPS required (Web Crypto API requirement)
- Keys derived using PBKDF2 with 100k iterations
- Random salt and IV for each operation

## 🎨 Customization

Edit `css/style.css` variables:
```css
:root {
    --primary: #6366f1;        /* Change brand color */
    --accent: #10b981;         /* Change success color */
    --background: #ffffff;     /* Change background */
}
```

## 🌐 Browser Support

- Chrome 90+
- Firefox 88+
- Safari 14+
- Edge 90+
- Chrome Android
- Safari iOS

Requires Web Crypto API support.

## 📄 License

MIT License - See LICENSE file for details.

---

**Made with ❤️ for privacy-conscious users**
