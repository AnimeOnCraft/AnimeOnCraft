import js from '@eslint/js';
import globals from 'globals';
import tseslint from 'typescript-eslint';

export default [
  { ignores: ['.tools/**', '**/dist/**', '**/node_modules/**', '**/target/**', '**/build/**', '**/generated/**'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  { files: ['**/*.mjs'], languageOptions: { globals: globals.node } },
  { files: ['apps/download-site/**/*.js', 'apps/download-site-glass/**/*.js'], languageOptions: { globals: globals.browser } },
  {
    files: ['**/*.{ts,tsx,mts}'],
    languageOptions: { globals: { ...globals.browser, ...globals.node } },
    rules: {
      '@typescript-eslint/no-explicit-any': 'error',
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }]
    }
  }
];
