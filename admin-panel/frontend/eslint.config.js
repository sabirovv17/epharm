import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

export default defineConfig([
  globalIgnores(['dist']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      globals: globals.browser,
    },
    rules: {
      // Конвенция: параметр/переменная с префиксом `_` — намеренно не используется
      // (placeholder-сигнатуры, заглушки до подключения реальных данных). Не флагаем.
      '@typescript-eslint/no-unused-vars': [
        'error',
        {
          argsIgnorePattern: '^_',
          varsIgnorePattern: '^_',
          caughtErrorsIgnorePattern: '^_',
        },
      ],
      'react-refresh/only-export-components': [
        'error',
        {
          allowConstantExport: true,
          allowExportNames: ['computeMetrics', 'MONTHS', 'MONTHS_SHORT', 'formatPeriod', 'useToast'],
        },
      ],
    },
  },
  {
    // Playwright names its fixture continuation callback `use`; it is not a React Hook.
    files: ['e2e/**/*.{ts,tsx}'],
    languageOptions: {
      globals: { ...globals.browser, ...globals.node },
    },
    rules: {
      'react-hooks/rules-of-hooks': 'off',
      'react-refresh/only-export-components': 'off',
    },
  },
  {
    // Test provider modules intentionally export both components and factory helpers.
    files: ['src/test/**/*.{ts,tsx}'],
    rules: {
      'react-refresh/only-export-components': 'off',
    },
  },
  {
    // These controlled forms intentionally hydrate/reset local state when a modal opens
    // or remote data arrives. Keep the stricter rule enabled for every other component.
    files: [
      'src/features/pharmacies/CreatePharmacyModal.tsx',
      'src/features/pharmacies/PharmacyDetailPage.tsx',
      'src/features/promo/CreatePromoModal.tsx',
      'src/features/promo/PromoDetailPage.tsx',
      'src/features/promo/PromoProductPicker.tsx',
      'src/features/promo/PromoRulesEditor.tsx',
      'src/features/screens/BannersPanel.tsx',
      'src/layout/PeriodPicker.tsx',
    ],
    rules: {
      'react-hooks/set-state-in-effect': 'off',
    },
  },
])
