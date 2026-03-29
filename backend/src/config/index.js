const serverConfig = require("./server.config");
const audioConfig = require("./audio.config");
const webrtcConfig = require("./webrtc.config");

module.exports = {
  ...serverConfig,
  ...audioConfig,
  ...webrtcConfig,
};
