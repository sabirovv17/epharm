import type { Config } from 'tailwindcss'

// Source-of-truth — admin-panel/design-tokens-admin.md.
// Любая палитра / тень / радиус / типографика берётся отсюда. Хексы в коде не хардкодим.

export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        brand: {
          // Коралл Claude orange (бренд, замена зелёного; стопы по светлоте).
          // Имена классов brand-green-* НЕ переименованы — поменяны только значения.
          green: {
            50: '#FBF3EE',
            100: '#F8E7DD',
            200: '#F0C8B4',
            300: '#E4A485',
            400: '#E0916B',
            500: '#DB7F57',
            600: '#D97757',
            700: '#BE5A38',
            800: '#9A4427',
          },
          // Акцент-коралл (моно-бренд, чуть глубже). Класс brand-blue-* сохранён.
          blue: {
            100: '#F8E7DD',
            200: '#F0C8B4',
            300: '#E4A485',
            400: '#E0916B',
            500: '#DB7F57',
            600: '#BE5A38',
            700: '#A8472A',
          },
        },
        // Тёплая нейтральная шкала (бывшая холодно-серая ink).
        ink: {
          50: '#F6F3EE',
          100: '#EFEAE2',
          200: '#E2DCD2',
          300: '#D4CCC0',
          400: '#9D9388',
          500: '#6F665B',
          600: '#514A40',
          700: '#423B32',
          800: '#2E2820',
          900: '#221C16',
        },
        // Кремовые поверхности вместо холодно-серых.
        paper: {
          DEFAULT: '#FAF7F2',
          card: '#FFFFFF',
          input: '#F3EEE7',
          hover: '#F5F1EA',
        },
        accent: {
          success: '#16C97A',
          warning: '#F1B416',
          amber: '#F4B73A',
          danger: '#E5484D',
          purple: '#8B5CF6',
        },
        // Inline-blocks для red/amber surfaces из design-tokens-admin §2.5.
        surface: {
          danger: '#FEE2E2',
          'danger-strong': '#B91C1C',
          warning: '#FEF3C7',
          'warning-strong': '#B45309',
        },
      },
      fontFamily: {
        sans: ['Manrope', 'system-ui', 'sans-serif'],
        mono: ['"JetBrains Mono"', 'ui-monospace', 'monospace'],
      },
      fontSize: {
        // Роли из design-tokens-admin §3 (admin spec). Используй классы напрямую где удобно.
        'h1': ['24px', { lineHeight: '30px', fontWeight: '800' }],
        'h2': ['15px', { lineHeight: '22px', fontWeight: '800' }],
        'kpi': ['28px', { lineHeight: '28px', fontWeight: '800', letterSpacing: '-0.02em' }],
        'th': ['11px', { lineHeight: '14px', fontWeight: '600', letterSpacing: '0.04em' }],
      },
      borderRadius: {
        xs: '4px',
        sm: '6px',
        md: '10px',
        lg: '12px',
        xl: '16px',
        '2xl': '20px',
      },
      boxShadow: {
        // Тени на тёплом ink-900 (34,28,22). Свечение FAB — мягкий коралл, ниже интенсивность.
        card: '0 1px 2px rgba(34,28,22,0.04), 0 4px 16px rgba(34,28,22,0.06)',
        elevated: '0 4px 8px rgba(34,28,22,0.06), 0 12px 32px rgba(34,28,22,0.10)',
        fab: '0 8px 20px rgba(217,119,87,0.28)',
        sidebar: 'inset -1px 0 0 rgba(34,28,22,0.06)',
        kbd: '0 1px 0 rgba(34,28,22,0.12), inset 0 -1px 0 rgba(34,28,22,0.08)',
        'sidebar-tab': '4px 0 12px rgba(34,28,22,0.18)',
      },
      spacing: {
        // Sidebar geometry — design-tokens-admin §4.
        sidebar: '260px',
        'sidebar-collapsed': '72px',
        topbar: '64px',
      },
      minWidth: {
        screen: '1280px',
      },
    },
  },
  plugins: [],
} satisfies Config
