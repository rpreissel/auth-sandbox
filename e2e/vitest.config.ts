import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    // Explicit include — avoids relying on glob pattern inference with NodeNext modules
    include: ["tests/**/*.test.ts"],
    // Each test file gets its own worker — stack is shared via globalSetup
    pool: "forks",
    poolOptions: {
      forks: {
        // Run test files sequentially to avoid race conditions on shared state
        singleFork: true,
      },
    },
    globalSetup: ["./global-setup.ts"],
    testTimeout: 120_000,
    hookTimeout: 300_000,
  },
});
