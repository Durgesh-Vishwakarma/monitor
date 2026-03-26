'use client';

import { useRef, useEffect } from 'react';

interface AudioVisualizerProps {
  type: 'waveform' | 'spectrum';
  data?: number[];
}

export function AudioVisualizer({ type, data }: AudioVisualizerProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    // Set canvas dimensions
    const dpr = window.devicePixelRatio || 1;
    const rect = canvas.getBoundingClientRect();
    canvas.width = rect.width * dpr;
    canvas.height = rect.height * dpr;
    ctx.scale(dpr, dpr);

    // Clear canvas
    ctx.fillStyle = '#0f1123';
    ctx.fillRect(0, 0, rect.width, rect.height);

    if (!data || data.length === 0) {
      // Draw flat line for no data
      ctx.strokeStyle = '#323672';
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(0, rect.height / 2);
      ctx.lineTo(rect.width, rect.height / 2);
      ctx.stroke();
      return;
    }

    if (type === 'waveform') {
      drawWaveform(ctx, data, rect.width, rect.height);
    } else {
      drawSpectrum(ctx, data, rect.width, rect.height);
    }
  }, [data, type]);

  return (
    <canvas 
      ref={canvasRef} 
      className={`w-full ${type === 'spectrum' ? 'h-[78px]' : 'h-[60px]'}`}
    />
  );
}

function drawWaveform(
  ctx: CanvasRenderingContext2D, 
  data: number[], 
  width: number, 
  height: number
) {
  const gradient = ctx.createLinearGradient(0, 0, width, 0);
  gradient.addColorStop(0, '#7c3aed');
  gradient.addColorStop(0.5, '#14b8a6');
  gradient.addColorStop(1, '#7c3aed');

  ctx.strokeStyle = gradient;
  ctx.lineWidth = 1.5;
  ctx.beginPath();

  const sliceWidth = width / data.length;
  let x = 0;

  for (let i = 0; i < data.length; i++) {
    const v = data[i] / 128.0;
    const y = (v * height) / 2;

    if (i === 0) {
      ctx.moveTo(x, y);
    } else {
      ctx.lineTo(x, y);
    }

    x += sliceWidth;
  }

  ctx.stroke();

  // Add glow effect
  ctx.shadowBlur = 8;
  ctx.shadowColor = 'rgba(124, 58, 237, 0.4)';
  ctx.stroke();
}

function drawSpectrum(
  ctx: CanvasRenderingContext2D, 
  data: number[], 
  width: number, 
  height: number
) {
  const barCount = Math.min(data.length, 64);
  const barWidth = width / barCount - 2;

  for (let i = 0; i < barCount; i++) {
    const barHeight = (data[i] / 255) * height;
    const x = i * (barWidth + 2);
    const y = height - barHeight;

    // Gradient for each bar
    const gradient = ctx.createLinearGradient(x, height, x, y);
    gradient.addColorStop(0, '#7c3aed');
    gradient.addColorStop(0.5, '#14b8a6');
    gradient.addColorStop(1, '#5eead4');

    ctx.fillStyle = gradient;
    ctx.fillRect(x, y, barWidth, barHeight);
  }
}
