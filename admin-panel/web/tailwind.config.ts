import type { Config } from 'tailwindcss'

// Source-of-truth — admin-panel/design-tokens-admin.md.
// Любая палитра / тень / радиус / типографика берётся отсюда. Хексы в коде не хардкодим.

export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        brand: {
          green: {
            50: '#EDFBF3',
            100: '#D7F5E4',
            200: '#A9EBC6',
            300: '#6FDDA0',
            400: '#3DCDA2',
            500: '#21D17A',
            600: '#16C97A',
            700: '#0F8F55',
            800: '#0B6E42',
          },
          blue: {
            100: '#E8EAFE',
            200: '#C9CCFF',
            300: '#8189FF',
            400: '#5560FB',
            500: '#3F47F0',
            600: '#2A2BE2',
            700: '#1F1FCC',
          },
        },
        ink: {
          50: '#F7F8FB',
          100: '#EEF0F5',
          200: '#E1E4EC',
          300: '#C2C7D2',
          400: '#9098A6',
          500: '#5A6173',
          600: '#3F465A',
          700: '#2A2F40',
          800: '#1A1D2B',
          900: '#0F1424',
        },
        paper: {
          DEFAULT: '#F4F6FA',
          card: '#FFFFFF',
          input: '#F2F4F8',
          hover: '#F7F9FC',
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
        card: '0 1px 2px rgba(15,20,36,0.04), 0 4px 16px rgba(15,20,36,0.06)',
        elevated: '0 4px 8px rgba(15,20,36,0.06), 0 12px 32px rgba(15,20,36,0.10)',
        fab: '0 8px 20px rgba(22,201,122,0.35)',
        sidebar: 'inset -1px 0 0 rgba(15,20,36,0.06)',
        kbd: '0 1px 0 rgba(15,20,36,0.12), inset 0 -1px 0 rgba(15,20,36,0.08)',
        'sidebar-tab': '4px 0 12px rgba(15,20,36,0.18)',
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
