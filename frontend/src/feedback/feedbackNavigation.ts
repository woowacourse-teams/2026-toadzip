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

/** 공고·단지 상세에서 연 의견 창이 함께 보낼 대상. 이름은 창에 보여 주기만 하고 서버에는 종류와 ID만 보낸다. */
export type FeedbackTarget = { readonly resource: 'ANNOUNCEMENT' | 'COMPLEX'; readonly id: number; readonly name: string }

export function feedbackTarget(state: unknown): FeedbackTarget | null {
  if (!state || typeof state !== 'object' || !('feedbackTarget' in state)) return null
  const value = state.feedbackTarget
  if (!value || typeof value !== 'object') return null
  const candidate = value as Partial<FeedbackTarget>
  if ((candidate.resource !== 'ANNOUNCEMENT' && candidate.resource !== 'COMPLEX')
    || typeof candidate.id !== 'number' || !Number.isSafeInteger(candidate.id) || candidate.id <= 0
    || typeof candidate.name !== 'string' || !candidate.name.trim()) return null
  return { resource: candidate.resource, id: candidate.id, name: candidate.name.trim() }
}

export const feedbackTargetLabels = { ANNOUNCEMENT: '공고', COMPLEX: '단지' } as const
