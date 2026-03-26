# MicMonitor Dashboard (Next.js)

This is the Next.js frontend for MicMonitor dashboard.

## Development

```bash
# Install dependencies
npm install

# Run development server
npm run dev
```

The dev server runs on http://localhost:3001 and proxies API requests to the backend at http://localhost:3000.

## Production Build

For production, we use static export to serve from the same Node.js server:

```bash
# Build static files
npm run build

# Output is in the 'out' directory
```

The static files are then served by the main server at `server/server.js`.

## Project Structure

```
src/
├── app/
│   ├── globals.css    # Global styles with Tailwind
│   ├── layout.tsx     # Root layout
│   └── page.tsx       # Main dashboard page
├── components/
│   ├── Header.tsx           # App header
│   ├── ConnectionStatus.tsx # WebSocket status indicator
│   ├── DevicesContainer.tsx # Device list container
│   ├── DeviceCard.tsx       # Individual device card
│   ├── HealthGrid.tsx       # Device health metrics
│   ├── ControlButtons.tsx   # Command buttons
│   ├── AudioVisualizer.tsx  # Waveform/spectrum canvas
│   ├── VUMeter.tsx          # Audio level meter
│   ├── PhotoGallery.tsx     # Captured photos display
│   ├── DeviceTabs.tsx       # Calls/SMS/Contacts tabs
│   ├── RecordingsPanel.tsx  # Recordings list
│   └── QRModal.tsx          # QR code for device pairing
├── hooks/
│   ├── useWebSocket.ts  # WebSocket connection hook
│   └── useDevices.ts    # Device state management
├── types/
│   └── index.ts         # TypeScript interfaces
└── lib/                 # Utility functions
```

## Features

- Real-time device monitoring via WebSocket
- Audio visualization (waveform + spectrum)
- Photo capture and display
- Call logs, SMS, and contacts viewing
- Recording management
- Device health metrics
- QR code for easy device pairing
