// 상세·필터·지도·로그인 상태가 담긴 기존 링크는 랜딩(/)이 아니라 탐색 화면에서 연다.
// nginx/app.conf의 `/` 308 규칙이 같은 판단을 하며 explorerState.test.ts가 둘을 맞춘다.
const EXPLORER_STATE_KEYS = new Set(['boundaryRegionCode', 'login', 'tab', 'inbox'])

export function hasExplorerState(search: string) {
  return Array.from(new URLSearchParams(search).keys()).some(key =>
    /^(complex|announcement|map)/.test(key) || EXPLORER_STATE_KEYS.has(key))
}
