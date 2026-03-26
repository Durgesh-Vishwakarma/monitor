'use client';

import { useState } from 'react';
import type { Photo } from '@/types';

interface PhotoGalleryProps {
  photos: Photo[];
}

export function PhotoGallery({ photos }: PhotoGalleryProps) {
  const [selectedPhoto, setSelectedPhoto] = useState<Photo | null>(null);

  if (photos.length === 0) return null;

  return (
    <>
      <div className="mt-2.5">
        <div className="text-[10px] font-bold tracking-widest uppercase text-text-dim mb-2">
          Captured Photos
        </div>
        <div className="flex flex-col gap-1.5">
          {photos.slice(0, 5).map((photo, index) => (
            <div 
              key={index}
              className="grid grid-cols-[110px_1fr] gap-2 items-center bg-bg2 border border-border rounded-lg p-1.5 cursor-pointer hover:border-border2 transition-colors"
              onClick={() => setSelectedPhoto(photo)}
            >
              <img 
                src={photo.url} 
                alt={`Photo ${index + 1}`}
                className="w-[100px] h-[148px] object-cover rounded-md border border-border2"
              />
              <div className="flex flex-col gap-1">
                <span className={`text-[10px] font-bold tracking-wider px-2 py-0.5 rounded-full w-fit ${
                  photo.camera === 'front'
                    ? 'bg-violet/15 text-violet-light border border-violet/25'
                    : 'bg-teal/15 text-teal-light border border-teal/25'
                }`}>
                  {photo.camera.toUpperCase()}
                </span>
                <span className="text-[10px] text-text-dim font-mono">
                  {new Date(photo.timestamp).toLocaleString()}
                </span>
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Photo Modal */}
      {selectedPhoto && (
        <div 
          className="fixed inset-0 bg-black/80 flex items-center justify-center z-50 p-4"
          onClick={() => setSelectedPhoto(null)}
        >
          <div 
            className="max-w-[90vw] max-h-[90vh] relative"
            onClick={(e) => e.stopPropagation()}
          >
            <img 
              src={selectedPhoto.url} 
              alt="Full size photo"
              className="max-w-full max-h-[90vh] object-contain rounded-lg"
            />
            <button 
              className="absolute top-2 right-2 w-8 h-8 bg-black/50 rounded-full flex items-center justify-center text-white hover:bg-black/70"
              onClick={() => setSelectedPhoto(null)}
            >
              ✕
            </button>
            <div className="absolute bottom-2 left-2 bg-black/50 rounded-lg px-3 py-1.5">
              <span className="text-xs text-white font-mono">
                {selectedPhoto.camera.toUpperCase()} • {new Date(selectedPhoto.timestamp).toLocaleString()}
              </span>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
