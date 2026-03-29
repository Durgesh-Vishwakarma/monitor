/**
 * Dashboard WebSocket client store
 */

/** @type {Set<any>} */
const dashboardClients = new Set();

function addClient(ws) {
  dashboardClients.add(ws);
}

function removeClient(ws) {
  dashboardClients.delete(ws);
}

function size() {
  return dashboardClients.size;
}

function forEachClient(callback) {
  dashboardClients.forEach(callback);
}

module.exports = {
  addClient,
  removeClient,
  size,
  forEachClient,
  dashboardClients,
};
