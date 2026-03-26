/** @type {import('next').NextConfig} */
const nextConfig = {
  output: 'export',
  trailingSlash: true,
  images: {
    unoptimized: true,
  },
  // For static export, we don't need these but keeping for development
  async rewrites() {
    return process.env.NODE_ENV === 'development'
      ? [
          {
            source: '/api/:path*',
            destination: 'http://localhost:3000/api/:path*',
          },
          {
            source: '/photos/:path*',
            destination: 'http://localhost:3000/photos/:path*',
          },
          {
            source: '/recordings/:path*',
            destination: 'http://localhost:3000/recordings/:path*',
          },
        ]
      : [];
  },
};

module.exports = nextConfig;
