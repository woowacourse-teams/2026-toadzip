import type { UserProvider } from './api'

export const providerLabels: Record<UserProvider, string> = { GOOGLE: 'Google', KAKAO: '카카오', UNKNOWN: '미확인' }
export function joinedAt(value: string) { return value.replace('T', ' ') }
