import type { Config } from 'tailwindcss';

const config: Config = {
  content: [
    './src/pages/**/*.{js,ts,jsx,tsx,mdx}',
    './src/components/**/*.{js,ts,jsx,tsx,mdx}',
    './src/app/**/*.{js,ts,jsx,tsx,mdx}',
  ],
  theme: {
    extend: {
      colors: {
        // Core palette matching original theme
        bg: '#0b0c1a',
        bg2: '#0f1123',
        surface: '#13152a',
        card: '#181a2e',
        card2: '#1c1f38',
        border: '#252847',
        border2: '#323672',
        // Accents
        violet: {
          DEFAULT: '#7c3aed',
          light: '#a78bfa',
        },
        teal: {
          DEFAULT: '#14b8a6',
          light: '#5eead4',
        },
        pink: '#ec4899',
        green: {
          DEFAULT: '#10b981',
          light: '#6ee7b7',
        },
        red: '#f43f5e',
        amber: '#f59e0b',
        blue: '#3b82f6',
        // Text
        text: {
          DEFAULT: '#e2e8f0',
          muted: '#94a3b8',
          dim: '#4b5578',
        },
      },
      fontFamily: {
        sans: ['Inter', 'Segoe UI', 'sans-serif'],
        mono: ['JetBrains Mono', 'monospace'],
      },
      boxShadow: {
        'glow-violet': '0 0 24px rgba(124, 58, 237, 0.45)',
        'glow-teal': '0 0 24px rgba(20, 184, 166, 0.45)',
        'glow-green': '0 0 10px rgba(16, 185, 129, 0.45)',
        'glow-red': '0 0 10px rgba(244, 63, 94, 0.45)',
      },
      animation: {
        pulse: 'pulse 2s infinite',
        'rec-pulse': 'recPulse 1.2s ease infinite',
        'clip-blink': 'clipBlink 0.3s infinite alternate',
      },
      keyframes: {
        pulse: {
          '0%, 100%': { boxShadow: '0 0 6px rgba(16, 185, 129, 0.45)' },
          '50%': { boxShadow: '0 0 18px rgba(16, 185, 129, 0.45)' },
        },
        recPulse: {
          '0%, 100%': { boxShadow: 'none' },
          '50%': { boxShadow: '0 0 10px rgba(244, 63, 94, 0.5)' },
        },
        clipBlink: {
          from: { opacity: '1' },
          to: { opacity: '0.15' },
        },
      },
    },
  },
  plugins: [],
};

export default config;
