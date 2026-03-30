/**
 * Audio parsing and µ-law decoding helpers
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

module.exports = {
  parseAudioPayload,
  muLawToPcm16Buffer,
  muLawByteToLinearSample,
};
