# Gatebridge Marketing Site

Marketing website for Gatebridge (`gatebridge.app`), built with [Astro](https://astro.build).

**Plan:** [`../WEBSITE_PLAN.md`](../WEBSITE_PLAN.md)

## Quick start

```bash
# Requires Node.js 22+
npm install
npm run dev        # http://localhost:4321
npm run build      # static output to dist/
npm run preview    # preview the production build
```

## Structure

```
src/
├── layouts/
│   └── Base.astro          # Single HTML shell (head, header, footer)
├── components/
│   ├── Header.astro        # Nav, theme toggle, mobile menu
│   └── Footer.astro        # Footer columns
├── pages/
│   ├── index.astro         # Homepage
│   ├── how-it-works.astro
│   ├── pricing.astro
│   ├── security.astro
│   ├── open-source.astro
│   ├── company.astro
│   ├── business.astro
│   ├── use-cases/          # Index + [slug] (content collection)
│   ├── docs/               # Index + [slug] (content collection)
│   └── blog/               # Index + [slug] (content collection)
├── content/
│   ├── blog/               # Markdown posts
│   ├── docs/               # Markdown docs pages
│   └── use-cases/          # Markdown use-case scenarios
├── styles/
│   ├── tokens.css          # Design tokens (colors, type, spacing)
│   └── global.css          # Reset, base, utilities
└── content.config.ts       # Collection schemas
```

## Design tokens

All colors, typography, and spacing live in `src/styles/tokens.css`.
Dark mode is toggled via `data-theme="dark"` on `<html>` and persisted in `localStorage`.

## Content

| Collection | Location | Schema |
|---|---|---|
| Blog | `src/content/blog/*.md` | title, description, pubDate, tags |
| Docs | `src/content/docs/*.md` | title, description, order |
| Use cases | `src/content/use-cases/*.md` | title, description, segment |

## Related

- [`../BUSINESS_PLAN.md`](../BUSINESS_PLAN.md) — product scope, pricing, GTM
- [`../BRAND.md`](../BRAND.md) — naming, domain, brand decisions
- [`../PROTOCOL.md`](../PROTOCOL.md) — wire format (source for security docs)
