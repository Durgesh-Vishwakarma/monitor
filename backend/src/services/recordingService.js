/**
 * Recording and MP3 encoding service
 *
 * S-C2 fix: Stream PCM chunks to a temp file on disk instead of accumulating
 * in RAM. A 24-hour recording at 16kHz mono PCM16 = ~2.7 GB — storing that
 * in-memory crashes Node.js on Render's 512 MB free tier.
 */

const fs = require("fs");
const path = require("path");
const { Mp3Encoder } = require("../utils/lamejs");
const { RECORDINGS_DIR } = require("../config");
const { MP3_BITRATE, DEFAULT_SAMPLE_RATE } = require("../config/audio.config");
const { broadcastToDashboard } = require("./dashboardService");

function startDeviceRecording(deviceId, device) {
  if (device.recordingStream) return; // already recording
  device.recordingSampleRate = DEFAULT_SAMPLE_RATE;
  const filename = `rec_${deviceId.slice(0, 8)}_${Date.now()}.mp3`;
  device.recordingFilename = filename;
  const tempPath = path.join(RECORDINGS_DIR, `_tmp_${filename}.pcm`);
  device._recordingTempPath = tempPath;
  try {
    device.recordingStream = fs.createWriteStream(tempPath, { flags: "w" });
    // Legacy compat: keep recordingChunks as a truthy sentinel for deviceController checks
    device.recordingChunks = [];
    console.log(`⏺️  Recording started: ${filename} (streaming to disk)`);
    broadcastToDashboard({ type: "recording_started", deviceId, filename });
  } catch (err) {
    console.error(`❌ Failed to start recording for ${deviceId}:`, err.message);
    device.recordingStream = null;
    device._recordingTempPath = null;
    device.recordingChunks = null;
  }
}

function appendRecordingChunk(device, pcm16Buffer) {
  if (!device.recordingStream || !pcm16Buffer || pcm16Buffer.length === 0) return;
  try {
    device.recordingStream.write(pcm16Buffer);
  } catch (_) {
    // Ignore write errors — the recording will be shorter but won't crash the server.
  }
}

function stopDeviceRecording(deviceId, device) {
  if (!device.recordingStream) {
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
  const stream = device.recordingStream;
  const tempPath = device._recordingTempPath;
  const filename =
    device.recordingFilename || `rec_${deviceId.slice(0, 8)}_${Date.now()}.mp3`;

  // Clear recording state immediately
  device.recordingStream = null;
  device.recordingChunks = null;
  device.recordingFilename = null;
  device._recordingTempPath = null;

  if (!stream || !tempPath) return;

  // Close the write stream, then encode MP3 from the temp file
  stream.end(() => {
    try {
      if (!fs.existsSync(tempPath)) {
        console.error(`❌ Recording temp file not found: ${tempPath}`);
        return;
      }
      
      const stats = fs.statSync(tempPath);
      if (stats.size < 2) {
        console.log(`⚠️  Recording for ${deviceId} was empty, skipping MP3 encode`);
        fs.unlinkSync(tempPath);
        return;
      }

      const filepath = path.join(RECORDINGS_DIR, filename);
      const readStream = fs.createReadStream(tempPath, { highWaterMark: 1152 * 2 * 10 }); // Read chunks of 11520 samples (23040 bytes)
      const writeStream = fs.createWriteStream(filepath);
      const encoder = new Mp3Encoder(1, device.recordingSampleRate || DEFAULT_SAMPLE_RATE, MP3_BITRATE);
      let remainder = Buffer.alloc(0);

      readStream.on('data', (chunk) => {
        // Concatenate remainder from previous chunk
        const buffer = Buffer.concat([remainder, chunk]);
        
        // We need an even number of bytes for Int16Array
        const processableBytes = buffer.length - (buffer.length % 2);
        remainder = buffer.subarray(processableBytes);

        if (processableBytes > 0) {
          const processableBuffer = buffer.subarray(0, processableBytes);
          const samples = new Int16Array(
            processableBuffer.buffer,
            processableBuffer.byteOffset,
            processableBytes / 2
          );
          
          const encoded = encoder.encodeBuffer(samples);
          if (encoded.length > 0) {
            writeStream.write(Buffer.from(encoded));
          }
        }
      });

      readStream.on('end', () => {
        // Process any final remainder if it somehow got left (though very unlikely)
        if (remainder.length >= 2) {
           const processableBytes = remainder.length - (remainder.length % 2);
           const processableBuffer = remainder.subarray(0, processableBytes);
           const samples = new Int16Array(processableBuffer.buffer, processableBuffer.byteOffset, processableBytes / 2);
           const encoded = encoder.encodeBuffer(samples);
           if (encoded.length > 0) writeStream.write(Buffer.from(encoded));
        }
        
        const flushed = encoder.flush();
        if (flushed.length > 0) writeStream.write(Buffer.from(flushed));
        writeStream.end();
      });

      writeStream.on('finish', () => {
        // Clean up temp file
        try { fs.unlinkSync(tempPath); } catch (_) {}
        const finalStats = fs.statSync(filepath);
        console.log(`⏹️  Recording saved: ${filename} (${(finalStats.size / 1024).toFixed(1)} KB)`);
        broadcastToDashboard({ type: "recording_saved", deviceId, filename, size: finalStats.size });
      });

    } catch (err) {
      console.error(`❌ Failed to encode/save MP3 for ${deviceId}:`, err.message);
      try { fs.unlinkSync(tempPath); } catch (_) {}
    }
  });
}

module.exports = {
  startDeviceRecording,
  stopDeviceRecording,
  appendRecordingChunk,
  saveMp3,
};
