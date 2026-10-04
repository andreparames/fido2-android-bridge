// @ts-check
import { defineConfig } from 'astro/config';

// https://astro.build/config
export default defineConfig({
  build: {
    // Inline the small site CSS into each page to avoid a render-blocking request.
    inlineStylesheets: 'always',
  },
});
