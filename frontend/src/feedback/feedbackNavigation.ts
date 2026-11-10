import type { Location } from 'react-router'
import { explorerPagePath } from '../public-housing/navigation/detailLocation'

export function feedbackBackground(state: unknown): Location | null {
  if (!state || typeof state !== 'object' || !('feedbackBackground' in state)) return null
  const value = state.feedbackBackground
  if (!value || typeof value !== 'object') return null
  const candidate = value as Partial<Location>
  if (typeof candidate.pathname !== 'string' || !/^\/(explore|mypage(?:\/(?:account|bookmarks|notifications|conditions))?|notifications)$/.test(explorerPagePath(candidate.pathname))
    || typeof candidate.search !== 'string' || typeof candidate.hash !== 'string' || typeof candidate.key !== 'string') return null
  return candidate as Location
}
