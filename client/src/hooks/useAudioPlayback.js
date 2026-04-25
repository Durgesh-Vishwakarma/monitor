import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Audio format from APK:
 * - Sample Rate: 16kHz
 * - Bit Depth: 16-bit signed PCM
 * - Channels: Mono
 * 
 * Binary frame format from server:
 * [2-byte BE length][deviceId UTF-8][audio payload]
 * 
 * Audio payload format:
 * [0x4D][0x4D][version][codec][...data]
 * version 0x01: [codec][pcm/mulaw data]
 * version 0x02: [codec][4-byte BE length][pcm/mulaw data]
 * 
 * Codecs:
 * 0x00 = PCM16 16kHz
 * 0x01 = µ-law 8kHz
 * 0x10 = HQ PCM16 16kHz
 * 0x11 = HQ µ-law
 */

const SAMPLE_RATE = 16000;
const BUFFER_SIZE = 4096;

// µ-law decompression table
const MULAW_DECODE_TABLE = new Int16Array(256);
for (let i = 0; i < 256; i++) {
  const mu = ~i & 0xff;
  const sign = mu & 0x80 ? -1 : 1;
  const exponent = mu >> 4 & 0x07;
  const mantissa = mu & 0x0f;
  const sample = sign * (((mantissa << 1) + 33 << exponent) - 33);
  MULAW_DECODE_TABLE[i] = sample;
}
export function useAudioPlayback() {
  const [state, setState] = useState({
    isPlaying: false,
    volume: 1.0,
    latencyMs: 0,
    bufferHealth: 0,
    lastDeviceId: null,
    waveform: null
  });
  const audioContextRef = useRef(null);
  const gainNodeRef = useRef(null);
  const audioQueueRef = useRef([]);
  const isPlayingRef = useRef(false);
  const scriptProcessorRef = useRef(null);
  const waveformRef = useRef(new Float32Array(128));
  const lastDeviceIdRef = useRef(null);
  const targetDeviceIdRef = useRef(null);
  // S-M5 fix: Use ref for volume to avoid stale closure in initAudioContext
  const volumeRef = useRef(1.0);
  // S-M1 fix: Throttle setState to ~10Hz instead of every audio frame
  const lastStateUpdateRef = useRef(0);

  // Parse audio frame from server
  const parseAudioFrame = useCallback((data, explicitDeviceId) => {
    if (data.byteLength < 4) return null;

    const audioData = new Uint8Array(data);

    // Check magic bytes
    if (audioData[0] !== 0x4d || audioData[1] !== 0x4d) {
      // Fallback: treat entire payload as raw PCM16
      const floats = new Float32Array(Math.floor(audioData.length / 2));
      const pcmView = new DataView(data);
      for (let i = 0; i < floats.length; i++) {
        floats[i] = pcmView.getInt16(i * 2, true) / 32768.0;
      }
      return {
        deviceId: explicitDeviceId || 'unknown',
        audio: floats,
        sampleRate: SAMPLE_RATE
      };
    }
    const version = audioData[2];
    const codec = audioData[3];
    let payloadStart = 4;
    let payloadLength = audioData.length - 4;

    // HQ mode (version 0x02) has 4-byte length prefix
    if (version === 0x02) {
      if (audioData.length < 8) return null;
      const audioView = new DataView(audioData.buffer, audioData.byteOffset);
      payloadLength = audioView.getUint32(4, false);
      payloadStart = 8;
      if (audioData.length < payloadStart + payloadLength) {
        payloadLength = audioData.length - payloadStart;
      }
    }
    const payload = audioData.slice(payloadStart, payloadStart + payloadLength);

    // Decode based on codec
    if (codec === 0x01 || codec === 0x11) {
      // µ-law: each byte → 16-bit sample, then upsample 8k→16k
      const mulaw8k = decodeMulaw(payload);
      const upsampled = upsample8kTo16k(mulaw8k);
      return {
        deviceId: explicitDeviceId || 'unknown',
        audio: upsampled,
        sampleRate: SAMPLE_RATE
      };
    } else {
      // PCM16: convert to float
      const floats = new Float32Array(Math.floor(payload.length / 2));
      const pcmView = new DataView(payload.buffer, payload.byteOffset, payload.length);
      for (let i = 0; i < floats.length; i++) {
        floats[i] = pcmView.getInt16(i * 2, true) / 32768.0;
      }
      return {
        deviceId: explicitDeviceId || 'unknown',
        audio: floats,
        sampleRate: SAMPLE_RATE
      };
    }
  }, []);

  // Initialize audio context
  // S-M5 fix: No dependency on state.volume — use volumeRef instead
  const initAudioContext = useCallback(() => {
    if (audioContextRef.current) return;
    const ctx = new AudioContext({
      sampleRate: SAMPLE_RATE
    });
    audioContextRef.current = ctx;
    const gainNode = ctx.createGain();
    gainNode.gain.value = volumeRef.current;
    gainNode.connect(ctx.destination);
    gainNodeRef.current = gainNode;

    // Use ScriptProcessorNode for audio output (deprecated but widely supported)
    const scriptProcessor = ctx.createScriptProcessor(BUFFER_SIZE, 1, 1);
    scriptProcessor.onaudioprocess = e => {
      const output = e.outputBuffer.getChannelData(0);
      const queue = audioQueueRef.current;
      let samplesNeeded = output.length;
      let outputOffset = 0;
      while (samplesNeeded > 0 && queue.length > 0) {
        const chunk = queue[0];
        const samplesToTake = Math.min(samplesNeeded, chunk.length);
        output.set(chunk.subarray(0, samplesToTake), outputOffset);
        outputOffset += samplesToTake;
        samplesNeeded -= samplesToTake;
        if (samplesToTake === chunk.length) {
          queue.shift();
        } else {
          queue[0] = chunk.subarray(samplesToTake);
        }
      }

      // Fill remaining with silence
      if (samplesNeeded > 0) {
        output.fill(0, outputOffset);
      }

      // Update waveform visualization (downsample output)
      const waveform = waveformRef.current;
      const step = Math.floor(output.length / waveform.length);
      for (let i = 0; i < waveform.length; i++) {
        let sum = 0;
        for (let j = 0; j < step; j++) {
          sum += Math.abs(output[i * step + j]);
        }
        waveform[i] = sum / step;
      }

      // S-M1 fix: Throttle setState to ~10Hz (every 100ms) instead of every audio frame.
      // This prevents 60 React re-renders/sec that cause dashboard UI jank.
      const now = Date.now();
      if (now - lastStateUpdateRef.current >= 100) {
        lastStateUpdateRef.current = now;
        const bufferHealth = Math.min(1, queue.reduce((acc, c) => acc + c.length, 0) / (SAMPLE_RATE * 0.5));
        const totalSamples = queue.reduce((acc, c) => acc + c.length, 0);
        setState(prev => ({
          ...prev,
          bufferHealth,
          latencyMs: Math.round(totalSamples / SAMPLE_RATE * 1000),
          lastDeviceId: lastDeviceIdRef.current,
          waveform: new Float32Array(waveform)
        }));
      }
    };
    scriptProcessor.connect(gainNode);
    scriptProcessorRef.current = scriptProcessor;
  }, []); // S-M5 fix: Empty dependency — volume is accessed via volumeRef

  // Start playback
  const start = useCallback(() => {
    initAudioContext();
    const ctx = audioContextRef.current;
    if (ctx && ctx.state === 'suspended') {
      ctx.resume();
    }
    isPlayingRef.current = true;
    setState(prev => ({
      ...prev,
      isPlaying: true
    }));
  }, [initAudioContext]);

  // Stop playback
  const stop = useCallback(() => {
    isPlayingRef.current = false;
    audioQueueRef.current = [];
    setState(prev => ({
      ...prev,
      isPlaying: false,
      bufferHealth: 0
    }));
  }, []);

  // Feed audio data
  const feedAudio = useCallback((data, deviceId) => {
    if (!isPlayingRef.current || !audioContextRef.current) return;

    // Route only selected device audio when a target is set.
    const targetDeviceId = targetDeviceIdRef.current;
    if (targetDeviceId && deviceId && deviceId !== targetDeviceId) {
      return;
    }
    if (deviceId) {
      lastDeviceIdRef.current = deviceId;
    }

    const parsed = parseAudioFrame(data, deviceId);
    if (!parsed) return;
    
    lastDeviceIdRef.current = parsed.deviceId;

    // Add to queue
    audioQueueRef.current.push(parsed.audio);

    // S-H4 fix: Limit buffer to 0.5 seconds (was 2s) for low latency.
    // ScriptProcessorNode runs on the main thread and can drift behind the audio clock;
    // a smaller buffer cap keeps latency under 500ms instead of up to 2000ms.
    const maxSamples = SAMPLE_RATE * 0.5;
    let totalSamples = audioQueueRef.current.reduce((acc, c) => acc + c.length, 0);
    while (totalSamples > maxSamples && audioQueueRef.current.length > 1) {
      const removed = audioQueueRef.current.shift();
      if (removed) totalSamples -= removed.length;
    }

    // Track latency and device via refs — the throttled state update in
    // onaudioprocess will pick them up. DO NOT setState here: calling
    // setState on every audio frame (~60/sec) causes cascading React
    // re-renders that triggered the 80-commands/sec start_stream flood.
    lastDeviceIdRef.current = parsed.deviceId;
    // latencyMs is derived from queue in the throttled update
  }, [parseAudioFrame]);

  const setTargetDevice = useCallback(deviceId => {
    targetDeviceIdRef.current = deviceId || null;
    lastDeviceIdRef.current = deviceId || null;
    audioQueueRef.current = [];
    setState(prev => ({
      ...prev,
      latencyMs: 0,
      bufferHealth: 0,
      lastDeviceId: deviceId || null
    }));
  }, []);

  // Set volume
  const setVolume = useCallback(volume => {
    const clamped = Math.max(0, Math.min(1, volume));
    // S-M5 fix: Update ref so initAudioContext always has current volume
    volumeRef.current = clamped;
    if (gainNodeRef.current) {
      gainNodeRef.current.gain.value = clamped;
    }
    setState(prev => ({
      ...prev,
      volume: clamped
    }));
  }, []);

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      if (scriptProcessorRef.current) {
        scriptProcessorRef.current.disconnect();
      }
      if (audioContextRef.current) {
        audioContextRef.current.close();
      }
    };
  }, []);
  return {
    state,
    feedAudio,
    setTargetDevice,
    setVolume,
    start,
    stop
  };
}

// Decode µ-law to Float32
function decodeMulaw(mulaw) {
  const output = new Float32Array(mulaw.length);
  for (let i = 0; i < mulaw.length; i++) {
    output[i] = MULAW_DECODE_TABLE[mulaw[i]] / 32768.0;
  }
  return output;
}

// Upsample 8kHz to 16kHz using linear interpolation
function upsample8kTo16k(input) {
  const output = new Float32Array(input.length * 2);
  for (let i = 0; i < input.length - 1; i++) {
    output[i * 2] = input[i];
    output[i * 2 + 1] = (input[i] + input[i + 1]) / 2;
  }
  // Last sample
  output[(input.length - 1) * 2] = input[input.length - 1];
  output[(input.length - 1) * 2 + 1] = input[input.length - 1];
  return output;
}