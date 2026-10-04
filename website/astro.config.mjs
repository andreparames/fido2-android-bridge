// @ts-check
import { defineConfig } from 'astro/config';
import sitemap from '@astrojs/sitemap';

// https://astro.build/config
export default defineConfig({
  site: 'https://gatebridge.app',
  build: {
    // Inline the small site CSS into each page to avoid a render-blocking request.
    inlineStylesheets: 'always',
  },
  integrations: [sitemap()],
});
