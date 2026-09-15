import type { Config } from 'tailwindcss'

// Source-of-truth — admin-panel/design-tokens-admin.md.
// Любая палитра / тень / радиус / типографика берётся отсюда. Хексы в коде не хардкодим.

export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        brand: {
          // Сдержанный терракотовый коралл: один акцент для действий и active-state.
          // Имена классов brand-green-* НЕ переименованы — поменяны только значения.
          green: {
            50: '#FBF5F2',
            100: '#F6E7E0',
            200: '#EBC9BC',
            300: '#DBA18B',
            400: '#CC7A5C',
            500: '#C26747',
            600: '#B95336',
            700: '#9C4029',
            800: '#7C3222',
          },
          // Акцент-коралл (моно-бренд, чуть глубже). Класс brand-blue-* сохранён.
          blue: {
            100: '#F6E7E0',
            200: '#EBC9BC',
            300: '#DBA18B',
            400: '#CC7A5C',
            500: '#C26747',
            600: '#B95336',
            700: '#9C4029',
          },
        },
        // Нейтральный тёплый графит без коричневого визуального шума.
        ink: {
          50: '#F5F5F3',
          100: '#ECECE9',
          200: '#DDDDD8',
          300: '#C8C7C1',
          400: '#96938D',
          500: '#6B6862',
          600: '#4D4A45',
          700: '#373531',
          800: '#272522',
          900: '#1C1B19',
        },
        // Спокойные рабочие поверхности: canvas отличается от карточки на один шаг.
        paper: {
          DEFAULT: '#F7F7F5',
          card: '#FFFFFF',
          input: '#F3F3F0',
          hover: '#F1F1EE',
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
        'h1': ['22px', { lineHeight: '28px', fontWeight: '700' }],
        'h2': ['14px', { lineHeight: '20px', fontWeight: '700' }],
        'kpi': ['26px', { lineHeight: '28px', fontWeight: '700', letterSpacing: '-0.015em' }],
        'th': ['11px', { lineHeight: '14px', fontWeight: '600', letterSpacing: '0.04em' }],
      },
      borderRadius: {
        xs: '4px',
        sm: '6px',
        md: '8px',
        lg: '10px',
        xl: '12px',
        '2xl': '16px',
      },
      boxShadow: {
        card: '0 1px 2px rgba(28,27,25,0.025), 0 0 0 1px rgba(28,27,25,0.075)',
        elevated: '0 12px 32px rgba(28,27,25,0.12), 0 0 0 1px rgba(28,27,25,0.08)',
        fab: '0 1px 2px rgba(28,27,25,0.16)',
        sidebar: 'inset -1px 0 0 rgba(255,255,255,0.04)',
        kbd: 'inset 0 0 0 1px rgba(28,27,25,0.10)',
        'sidebar-tab': '4px 0 12px rgba(28,27,25,0.18)',
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
