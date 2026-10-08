import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';

export default tseslint.config(
  { ignores: ['dist', 'node_modules', 'src/api/schema.d.ts'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  { files: ['scripts/**/*.mjs'], languageOptions: { globals: { console: 'readonly', process: 'readonly' } } },
  { plugins: { 'react-hooks': reactHooks }, rules: { ...reactHooks.configs.recommended.rules } },
);
