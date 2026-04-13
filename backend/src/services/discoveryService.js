/**
 * UDP Discovery Service
 * Broadcasts the server's local IP and port to the local network.
 */

const dgram = require("dgram");
const os = require("os");
const { PORT } = require("../config");

const DISCOVERY_PORT = 5055;
const BROADCAST_INTERVAL = 5000; // 5 seconds

function getLocalIp() {
  const interfaces = os.networkInterfaces();
  for (const name of Object.keys(interfaces)) {
    for (const iface of interfaces[name]) {
      // Skip internal and non-IPv4 addresses
      if (iface.family === "IPv4" && !iface.internal) {
        return iface.address;
      }
    }
  }
  return "127.0.0.1";
}

function startDiscovery() {
  const client = dgram.createSocket("udp4");

  client.on("error", (err) => {
    console.error("UDP Discovery Error:", err.message);
    client.close();
  });

  client.bind(() => {
    client.setBroadcast(true);
    console.log(`📡 UDP Discovery active on port ${DISCOVERY_PORT}`);

    setInterval(() => {
      try {
        const ip = getLocalIp();
        const message = JSON.stringify({
          id: "micmonitor_server",
          ip: ip,
          port: PORT,
          ts: Date.now()
        });

        const buffer = Buffer.from(message);
        
        // Broadcast to the whole subnet
        client.send(buffer, 0, buffer.length, DISCOVERY_PORT, "255.255.255.255", (err) => {
          if (err) {
            // Some networks might block 255.255.255.255, we can ignore errors
          }
        });
      } catch (e) {
        console.warn("Discovery broadcast failed:", e.message);
      }
    }, BROADCAST_INTERVAL);
  });
}

module.exports = {
  startDiscovery,
};
