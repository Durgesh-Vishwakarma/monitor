/**
 * Audio parsing, µ-law decoding, and server-side PCM amplification helpers.
 *
 * amplifyPcm16(): Boosts PCM samples for low-network / far-voice mode on the
 * server side so the dashboard player always receives loud, clear audio even
 * when the device sends compressed or low-gain audio over a weak link.
 */

const {
  AUDIO_MAGIC_0,
  AUDIO_MAGIC_1,
  AUDIO_HEADER_VERSION,
  AUDIO_HEADER_VERSION_HQ,
  AUDIO_CODEC_PCM16_16K,
  AUDIO_CODEC_MULAW_8K,
  AUDIO_CODEC_HQ_PCM16_16K,
  AUDIO_CODEC_HQ_MULAW,
} = require("../config/audio.config");

function parseAudioPayload(buffer) {
  if (
    buffer.length >= 4 &&
    buffer[0] === AUDIO_MAGIC_0 &&
    buffer[1] === AUDIO_MAGIC_1 &&
    buffer[2] === AUDIO_HEADER_VERSION
  ) {
    const codec = buffer[3];
    const audioData = buffer.subarray(4);
    if (codec === AUDIO_CODEC_PCM16_16K) {
      return {
        forwardPayload: buffer,
        pcm16: audioData,
        sampleRate: 16000,
        isHqMode: false,
      };
    }
    if (codec === AUDIO_CODEC_MULAW_8K) {
      // Client decimates 16 kHz → 8 kHz before µ-law; one byte per 8 kHz sample.
      return {
        forwardPayload: buffer,
        pcm16: muLawToPcm16Buffer(audioData),
        sampleRate: 8000,
        isHqMode: false,
      };
    }
  }

  if (
    buffer.length >= 8 &&
    buffer[0] === AUDIO_MAGIC_0 &&
    buffer[1] === AUDIO_MAGIC_1 &&
    buffer[2] === AUDIO_HEADER_VERSION_HQ
  ) {
    const codec = buffer[3];
    const payloadLen =
      (buffer[4] << 24) | (buffer[5] << 16) | (buffer[6] << 8) | buffer[7];
    const audioData = buffer.subarray(8, 8 + payloadLen);

    console.log(
      `📦 HQ audio packet: codec=${codec.toString(16)}, len=${payloadLen} bytes`,
    );

    if (codec === AUDIO_CODEC_HQ_PCM16_16K) {
      return {
        forwardPayload: buffer,
        pcm16: audioData,
        sampleRate: 16000,
        isHqMode: true,
      };
    }
    if (codec === AUDIO_CODEC_HQ_MULAW) {
      return {
        forwardPayload: buffer,
        pcm16: muLawToPcm16Buffer(audioData),
        sampleRate: 8000,
        isHqMode: true,
      };
    }
  }

  return {
    forwardPayload: buffer,
    pcm16: buffer,
    sampleRate: 16000,
    isHqMode: false,
  };
}

function muLawToPcm16Buffer(muLawBuffer) {
  const pcm = Buffer.allocUnsafe(muLawBuffer.length * 2);
  for (let i = 0; i < muLawBuffer.length; i++) {
    const sample = muLawByteToLinearSample(muLawBuffer[i]);
    pcm.writeInt16LE(sample, i * 2);
  }
  return pcm;
}

function muLawByteToLinearSample(value) {
  const mu = ~value & 0xff;
  const sign = mu & 0x80;
  const exponent = (mu >> 4) & 0x07;
  const mantissa = mu & 0x0f;
  let sample = ((mantissa << 3) + 0x84) << exponent;
  sample -= 0x84;
  return sign ? -sample : sample;
}

/**
 * Amplify a PCM16 LE buffer in-place (or return new buffer) by `gainFactor`.
 * Samples are clamped to the signed 16-bit range [-32768, 32767].
 *
 * Use this server-side to boost far-voice / low-network audio before
 * forwarding to dashboard, so the operator always hears loud, clear audio.
 *
 * @param {Buffer} pcmBuffer  Raw PCM16 little-endian buffer
 * @param {number} gainFactor Multiplier (e.g. 2.0 = twice as loud)
 * @returns {Buffer} Amplified buffer (new allocation)
 */
function amplifyPcm16(pcmBuffer, gainFactor = 1.3) {  // Bug 2.2: Change default from 1.0 to 1.3
  if (!pcmBuffer || pcmBuffer.length < 2 || gainFactor === 1.0) return pcmBuffer;

  const numSamples = Math.floor(pcmBuffer.length / 2);
  let peak = 0;
  for (let i = 0; i < numSamples; i++) {
    const sample = Math.abs(pcmBuffer.readInt16LE(i * 2));
    if (sample > peak) peak = sample;
  }

  if (peak <= 0) return pcmBuffer;

  const maxTarget = 30000;
  const effectiveGain = Math.max(1.0, Math.min(gainFactor, maxTarget / peak));
  const out = Buffer.allocUnsafe(pcmBuffer.length);
  for (let i = 0; i < numSamples; i++) {
    const offset = i * 2;
    const raw = pcmBuffer.readInt16LE(offset);
    const normalized = raw / 32768;
    const boosted = normalized * effectiveGain;
    const limited = Math.tanh(boosted * 1.15) / Math.tanh(1.15);
    out.writeInt16LE(Math.round(limited * 32767), offset);
  }
  // Copy any trailing odd byte unchanged
  if (pcmBuffer.length % 2 !== 0) {
    out[pcmBuffer.length - 1] = pcmBuffer[pcmBuffer.length - 1];
  }
  return out;
}

/**
 * Build an amplified forward payload for PCM16 streams.
 * Replaces the PCM data portion of a standard audio header packet.
 * Returns the original buffer unchanged if gainFactor <= 1.05 (no perceptible boost).
 *
 * @param {Buffer} originalForwardPayload
 * @param {Buffer} pcm16Data  Decoded PCM16 data (from parseAudioPayload)
 * @param {number} gainFactor
 * @param {boolean} hasHeader   True if forwardPayload includes the 4-byte header
 * @returns {Buffer}
 */
function buildAmplifiedPayload(originalForwardPayload, pcm16Data, gainFactor, hasHeader = true) {
  // Return the original payload intact if no meaningful amplification is applied.
  if (gainFactor <= 1.0) return originalForwardPayload;
  const amplified = amplifyPcm16(pcm16Data, gainFactor);
  if (hasHeader) {
    const header = originalForwardPayload.subarray(0, 4);
    return Buffer.concat([header, amplified]);
  }
  return amplified;
}

module.exports = {
  parseAudioPayload,
  muLawToPcm16Buffer,
  muLawByteToLinearSample,
  amplifyPcm16,
  buildAmplifiedPayload,
};
