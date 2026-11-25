import type { UserProvider } from './api'

export const providerLabels: Record<UserProvider, string> = { GOOGLE: 'Google', KAKAO: '카카오', UNKNOWN: '미확인' }
/** 가입 시각을 분 단위까지 보여 준다. 예: 2026-11-11 16:15 */
export function joinedAt(value: string) { return value.replace('T', ' ').slice(0, 16) }
