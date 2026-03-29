import type { WsState } from '../types/dashboard'

function ensureTrailingSlash(value: string): string {
  return value.endsWith('/') ? value : `${value}/`
}

function normalizeWsBase(raw: string): string {
  if (raw.startsWith('https://')) {
    return `wss://${raw.slice('https://'.length)}`
  }
  if (raw.startsWith('http://')) {
    return `ws://${raw.slice('http://'.length)}`
  }
  return raw
}

export function apiUrl(path: string): string {
  const baseUrl = import.meta.env.VITE_API_BASE_URL
  if (!baseUrl) {
    return path
  }
  try {
    return new URL(path, ensureTrailingSlash(baseUrl)).toString()
  } catch {
    const trimmed = baseUrl.endsWith('/') ? baseUrl.slice(0, -1) : baseUrl
    return `${trimmed}${path}`
  }
}

export function wsUrlForControl(): string {
  const token = import.meta.env.VITE_WS_AUTH_TOKEN || ''
  const wsBase = import.meta.env.VITE_WS_BASE_URL
  let url: URL

  if (wsBase) {
    url = new URL('/control', ensureTrailingSlash(normalizeWsBase(wsBase)))
  } else {
    const apiBase = import.meta.env.VITE_API_BASE_URL
    if (apiBase) {
      const wsFromApi = normalizeWsBase(apiBase)
      url = new URL('/control', ensureTrailingSlash(wsFromApi))
    } else {
      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
      url = new URL(`${protocol}//${window.location.host}/control`)
    }
  }

  if (token) {
    url.searchParams.set('token', token)
  }
  return url.toString()
}

export function wsPillClass(state: WsState): string {
  if (state === 'open') {
    return 'border-emerald-400/40 bg-emerald-500/10 text-emerald-200'
  }
  if (state === 'connecting') {
    return 'border-amber-400/40 bg-amber-500/10 text-amber-100'
  }
  return 'border-rose-400/40 bg-rose-500/10 text-rose-100'
}
