import type { Metadata } from 'next';
import './globals.css';

export const metadata: Metadata = {
  title: 'MicMonitor Dashboard',
  description: 'Real-time device monitoring dashboard',
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <head>
        <link rel="preconnect" href="https://fonts.googleapis.com" />
        <link
          href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&family=JetBrains+Mono:wght@400;500&display=swap"
          rel="stylesheet"
          crossOrigin="anonymous"
        />
        <script src="https://cdn.jsdelivr.net/npm/qrcode@1.5.3/build/qrcode.min.js" defer />
      </head>
      <body className="antialiased">
        {children}
      </body>
    </html>
  );
}
