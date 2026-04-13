import { useCallback, useEffect, useRef, useState } from 'react'

/**
 * WebRTC hook for receiving audio stream from Android device.
 * 
 * Flow:
 * 1. Call startWebRTC() -> sends webrtc_start command
 * 2. Create RTCPeerConnection and generate offer
 * 3. Send offer via webrtc_offer command
 * 4. Device creates answer, sends back webrtc_answer
 * 5. Exchange ICE candidates
 * 6. Audio track connects and plays
 */

export type WebRTCState = 
  | 'idle'
  | 'connecting'
  | 'connected'
  | 'disconnected'
  | 'failed'

export type WebRTCStats = {
  state: WebRTCState
  iceState: string
  bitrate: number
  packetsLost: number
  jitter: number
  roundTripTime: number
}

export type UseWebRTCReturn = {
  stats: WebRTCStats
  start: (sendCommand: (cmd: string, extra?: Record<string, unknown>) => void) => void
  stop: (sendCommand: (cmd: string, extra?: Record<string, unknown>) => void) => void
  handleMessage: (msg: Record<string, unknown>) => void
}

const ICE_SERVERS: RTCIceServer[] = [
  { urls: 'stun:stun.l.google.com:19302' },
  { urls: 'stun:stun1.l.google.com:19302' },
]

export function useWebRTC(): UseWebRTCReturn {
  const [stats, setStats] = useState<WebRTCStats>({
    state: 'idle',
    iceState: 'new',
    bitrate: 0,
    packetsLost: 0,
    jitter: 0,
    roundTripTime: 0,
  })

  const pcRef = useRef<RTCPeerConnection | null>(null)
  const audioRef = useRef<HTMLAudioElement | null>(null)
  const sendCommandRef = useRef<((cmd: string, extra?: Record<string, unknown>) => void) | null>(null)
  const statsIntervalRef = useRef<number | null>(null)
  const pendingIceCandidatesRef = useRef<RTCIceCandidate[]>([])

  // Create audio element on mount
  useEffect(() => {
    const audio = new Audio()
    audio.autoplay = true
    audio.volume = 1.0
    audioRef.current = audio

    return () => {
      audio.pause()
      audio.srcObject = null
    }
  }, [])

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      if (statsIntervalRef.current) {
        clearInterval(statsIntervalRef.current)
      }
      if (pcRef.current) {
        pcRef.current.close()
      }
    }
  }, [])

  const updateStats = useCallback(async () => {
    const pc = pcRef.current
    if (!pc) return

    try {
      const reports = await pc.getStats()
      reports.forEach(report => {
        if (report.type === 'inbound-rtp' && report.kind === 'audio') {
          setStats(prev => ({
            ...prev,
            packetsLost: report.packetsLost || 0,
            jitter: (report.jitter || 0) * 1000,
          }))
        }
        if (report.type === 'candidate-pair' && report.state === 'succeeded') {
          setStats(prev => ({
            ...prev,
            roundTripTime: (report.currentRoundTripTime || 0) * 1000,
          }))
        }
      })
    } catch {
      // Stats not available
    }
  }, [])

  const start = useCallback((sendCommand: (cmd: string, extra?: Record<string, unknown>) => void) => {
    if (pcRef.current) {
      console.log('[WebRTC] Already active, stopping first')
      pcRef.current.close()
    }

    sendCommandRef.current = sendCommand
    pendingIceCandidatesRef.current = []

    setStats(prev => ({ ...prev, state: 'connecting' }))

    // First, tell device to start WebRTC
    sendCommand('webrtc_start')

    // Create peer connection
    const pc = new RTCPeerConnection({
      iceServers: ICE_SERVERS,
      iceCandidatePoolSize: 10,
    })
    pcRef.current = pc

    // Handle ICE candidates
    pc.onicecandidate = (event) => {
      if (event.candidate && sendCommandRef.current) {
        sendCommandRef.current('webrtc_ice', {
          candidate: {
            candidate: event.candidate.candidate,
            sdpMid: event.candidate.sdpMid,
            sdpMLineIndex: event.candidate.sdpMLineIndex,
          }
        })
      }
    }

    pc.oniceconnectionstatechange = () => {
      setStats(prev => ({ ...prev, iceState: pc.iceConnectionState }))
      
      switch (pc.iceConnectionState) {
        case 'connected':
        case 'completed':
          setStats(prev => ({ ...prev, state: 'connected' }))
          break
        case 'disconnected':
          setStats(prev => ({ ...prev, state: 'disconnected' }))
          break
        case 'failed':
          setStats(prev => ({ ...prev, state: 'failed' }))
          break
      }
    }

    pc.ontrack = (event) => {
      console.log('[WebRTC] Received track:', event.track.kind)
      if (event.streams[0] && audioRef.current) {
        audioRef.current.srcObject = event.streams[0]
        audioRef.current.play().catch(e => console.warn('[WebRTC] Autoplay blocked:', e))
      }
    }

    // Add transceiver for receiving audio
    pc.addTransceiver('audio', { direction: 'recvonly' })

    // Create offer
    pc.createOffer({
      offerToReceiveAudio: true,
      offerToReceiveVideo: false,
    }).then(offer => {
      return pc.setLocalDescription(offer)
    }).then(() => {
      if (pc.localDescription && sendCommandRef.current) {
        console.log('[WebRTC] Sending offer')
        sendCommandRef.current('webrtc_offer', {
          sdp: pc.localDescription.sdp
        })
      }
    }).catch(err => {
      console.error('[WebRTC] Failed to create offer:', err)
      setStats(prev => ({ ...prev, state: 'failed' }))
    })

    // Start stats monitoring
    if (statsIntervalRef.current) {
      clearInterval(statsIntervalRef.current)
    }
    statsIntervalRef.current = window.setInterval(updateStats, 2000)
  }, [updateStats])

  const stop = useCallback((sendCommand: (cmd: string, extra?: Record<string, unknown>) => void) => {
    if (statsIntervalRef.current) {
      clearInterval(statsIntervalRef.current)
      statsIntervalRef.current = null
    }

    if (pcRef.current) {
      pcRef.current.close()
      pcRef.current = null
    }

    if (audioRef.current) {
      audioRef.current.srcObject = null
    }

    sendCommand('webrtc_stop')
    
    setStats({
      state: 'idle',
      iceState: 'new',
      bitrate: 0,
      packetsLost: 0,
      jitter: 0,
      roundTripTime: 0,
    })
  }, [])

  const handleMessage = useCallback((msg: Record<string, unknown>) => {
    const type = String(msg.type || '')
    const pc = pcRef.current

    if (type === 'webrtc_answer' && pc) {
      const sdp = String(msg.sdp || '')
      if (!sdp) return

      console.log('[WebRTC] Received answer')
      pc.setRemoteDescription(new RTCSessionDescription({
        type: 'answer',
        sdp: sdp
      })).then(() => {
        // Add any pending ICE candidates
        pendingIceCandidatesRef.current.forEach(candidate => {
          pc.addIceCandidate(candidate).catch(e => 
            console.warn('[WebRTC] Failed to add pending ICE candidate:', e)
          )
        })
        pendingIceCandidatesRef.current = []
      }).catch(err => {
        console.error('[WebRTC] Failed to set remote description:', err)
        setStats(prev => ({ ...prev, state: 'failed' }))
      })
    }

    if (type === 'webrtc_ice' && pc) {
      const candidateData = msg.candidate as Record<string, unknown> | undefined
      if (!candidateData) return

      const candidate = new RTCIceCandidate({
        candidate: String(candidateData.candidate || ''),
        sdpMid: String(candidateData.sdpMid || ''),
        sdpMLineIndex: Number(candidateData.sdpMLineIndex || 0),
      })

      if (pc.remoteDescription) {
        pc.addIceCandidate(candidate).catch(e => 
          console.warn('[WebRTC] Failed to add ICE candidate:', e)
        )
      } else {
        // Queue for later
        pendingIceCandidatesRef.current.push(candidate)
      }
    }

    if (type === 'webrtc_state') {
      console.log('[WebRTC] Device state:', msg)
    }
  }, [])

  return {
    stats,
    start,
    stop,
    handleMessage,
  }
}
