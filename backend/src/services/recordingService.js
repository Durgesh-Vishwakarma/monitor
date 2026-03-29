/**
 * Recording and MP3 encoding service
 */

const fs = require("fs");
const path = require("path");
const { Mp3Encoder } = require("../utils/lamejs");
const { RECORDINGS_DIR } = require("../config");
const { MP3_BITRATE, DEFAULT_SAMPLE_RATE } = require("../config/audio.config");
const { broadcastToDashboard } = require("./dashboardService");

function startDeviceRecording(deviceId, device) {
  if (device.recordingChunks) return; // already recording
  device.recordingChunks = [];
  device.recordingSampleRate = DEFAULT_SAMPLE_RATE;
  const filename = `rec_${deviceId.slice(0, 8)}_${Date.now()}.mp3`;
  device.recordingFilename = filename;
  console.log(`⏺️  Recording started: ${filename}`);
  broadcastToDashboard({ type: "recording_started", deviceId, filename });
}

function stopDeviceRecording(deviceId, device) {
  if (!device.recordingChunks || device.recordingChunks.length === 0) {
    device.recordingChunks = null;
    device.recordingFilename = null;
    broadcastToDashboard({
      type: "recording_stopped",
      deviceId,
      filename: null,
    });
    return;
  }
  saveMp3(deviceId, device);
}

function saveMp3(deviceId, device) {
  const chunks = device.recordingChunks;
  device.recordingChunks = null;
  const filename =
    device.recordingFilename || `rec_${deviceId.slice(0, 8)}_${Date.now()}.mp3`;
  device.recordingFilename = null;

  try {
    const allPcm = Buffer.concat(chunks);
    const mp3Buffer = pcmToMp3(allPcm, device.recordingSampleRate || DEFAULT_SAMPLE_RATE);
    const filepath = path.join(RECORDINGS_DIR, filename);
    fs.writeFileSync(filepath, mp3Buffer);
    console.log(
      `⏹️  Recording saved: ${filename} (${(mp3Buffer.length / 1024).toFixed(1)} KB)`,
    );
  } catch (err) {
    console.error(`❌ Failed to encode/save MP3 for ${deviceId}:`, err.message);
  }

  broadcastToDashboard({ type: "recording_stopped", deviceId, filename });
}

function pcmToMp3(pcmBuffer, sampleRate = DEFAULT_SAMPLE_RATE) {
  const encoder = new Mp3Encoder(1, sampleRate, MP3_BITRATE);
  const samples = new Int16Array(
    pcmBuffer.buffer,
    pcmBuffer.byteOffset,
    pcmBuffer.byteLength / 2,
  );
  const blockSize = 1152;
  const mp3Parts = [];

  for (let i = 0; i < samples.length; i += blockSize) {
    const chunk = samples.subarray(i, i + blockSize);
    const encoded = encoder.encodeBuffer(chunk);
    if (encoded.length > 0) mp3Parts.push(Buffer.from(encoded));
  }
  const flushed = encoder.flush();
  if (flushed.length > 0) mp3Parts.push(Buffer.from(flushed));

  return Buffer.concat(mp3Parts);
}

module.exports = {
  startDeviceRecording,
  stopDeviceRecording,
  saveMp3,
  pcmToMp3,
};
