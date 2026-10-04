import { defineCollection, z } from "astro:content";
import { glob } from "astro/loaders";

const blog = defineCollection({
  loader: glob({ pattern: "**/*.md", base: "./src/content/blog" }),
  schema: z.object({
    title: z.string(),
    description: z.string(),
    pubDate: z.coerce.date(),
    tags: z.array(z.string()).default([]),
  }),
});

const docs = defineCollection({
  loader: glob({ pattern: "**/*.md", base: "./src/content/docs" }),
  schema: z.object({
    title: z.string(),
    description: z.string(),
    order: z.number().default(0),
  }),
});

const useCases = defineCollection({
  loader: glob({ pattern: "**/*.md", base: "./src/content/use-cases" }),
  schema: z.object({
    title: z.string(),
    description: z.string(),
    segment: z.string(),
  }),
});

export const collections = { blog, docs, useCases };
