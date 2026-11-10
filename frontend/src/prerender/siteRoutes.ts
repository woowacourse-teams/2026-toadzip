// 경로별 검색 노출 정책의 원본. 프리렌더·sitemap이 사용하고, robots.txt와 nginx 설정은 테스트로 이 목록과 맞춘다.
export const SITE_ORIGIN = 'https://bokduckbang.com'

// 색인 대상. 빌드 시 프리렌더하고 sitemap에 넣는다.
export const PUBLIC_ROUTES = ['/', '/explore', '/privacy'] as const

// 로그인·관리·개인 설정 화면. robots.txt로 크롤링을 막고 nginx가 noindex 헤더를 붙인다.
// 각 항목은 그 경로와 하위 경로를 포함한다.
export const PRIVATE_ROUTE_PREFIXES = ['/admin', '/login', '/mypage', '/notifications', '/privacy/settings'] as const

// 누구나 볼 수 있지만 색인하지 않는 화면. noindex 헤더를 읽도록 크롤링은 막지 않는다.
// `/privacy/`는 버전별 방침 고정 주소(/privacy/:key/:version)처럼 그 아래 경로만 뜻한다.
export const NOINDEX_ROUTE_PREFIXES = ['/feedback', '/privacy/'] as const

export type PublicRoute = (typeof PUBLIC_ROUTES)[number]
