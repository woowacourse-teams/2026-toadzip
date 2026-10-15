const platforms = { IOS: 'iPhone', IPADOS: 'iPad', ANDROID: 'Android', MAC: 'Mac', WINDOWS: 'Windows', LINUX: 'Linux', OTHER: '기기' } as const
const browsers = { CHROME: 'Chrome', SAFARI: 'Safari', EDGE: 'Edge', FIREFOX: 'Firefox', SAMSUNG: '삼성 인터넷', HOME_SCREEN: '홈 화면 앱', OTHER: '브라우저' } as const
export function pushDeviceMetadata() {
  const ua = navigator.userAgent
  const ipad = /iPad/.test(ua) || navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1
  const platform = ipad ? 'IPADOS' : /iPhone|iPod/.test(ua) ? 'IOS' : /Android/.test(ua) ? 'ANDROID'
    : /Mac/.test(ua) ? 'MAC' : /Windows/.test(ua) ? 'WINDOWS' : /Linux/.test(ua) ? 'LINUX' : 'OTHER'
  const standalone = window.matchMedia?.('(display-mode: standalone)').matches || 'standalone' in navigator && navigator.standalone === true
  const browser = standalone ? 'HOME_SCREEN' : /Edg[AeiOS]*\//.test(ua) ? 'EDGE' : /SamsungBrowser\//.test(ua) ? 'SAMSUNG'
    : /Firefox|FxiOS/.test(ua) ? 'FIREFOX' : /Chrome|CriOS/.test(ua) ? 'CHROME' : /Safari/.test(ua) ? 'SAFARI' : 'OTHER'
  return { platform, browser }
}
export function pushDeviceLabel(platform: string | null, browser: string | null) {
  if (!platform && !browser) return '기기 정보 없음'
  const device = platforms[platform as keyof typeof platforms] ?? '기기'
  const agent = browsers[browser as keyof typeof browsers] ?? '브라우저'
  return `${device} · ${agent}`
}
export function lastUsedLabel(value: string, now = new Date()) {
  const date = new Date(value)
  const midnight = (day: Date) => Date.UTC(day.getFullYear(), day.getMonth(), day.getDate())
  const days = Math.max(0, Math.floor((midnight(now) - midnight(date)) / 86_400_000))
  if (days === 0) return '오늘'
  if (days === 1) return '어제'
  if (days < 30) return `${days}일 전`
  return new Intl.DateTimeFormat('ko-KR', { year: 'numeric', month: 'short', day: 'numeric' }).format(date)
}
