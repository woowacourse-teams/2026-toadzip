const RECENT_PREVIEW_KEY = 'toadzipRecentComplexPreview'

export interface RecentComplexPreview {
  readonly complexId: string
  readonly expanded: boolean
}

export function readRecentComplexPreview(state: unknown): RecentComplexPreview | null {
  if (!isRecord(state)) return null
  const value = state[RECENT_PREVIEW_KEY]
  if (!isRecord(value) || typeof value.complexId !== 'string' || !value.complexId
    || typeof value.expanded !== 'boolean') return null
  return { complexId: value.complexId, expanded: value.expanded }
}

export function withRecentComplexPreview(state: unknown, preview: RecentComplexPreview | null) {
  const next = isRecord(state) ? { ...state } : {}
  if (preview) next[RECENT_PREVIEW_KEY] = preview
  else delete next[RECENT_PREVIEW_KEY]
  return next
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
