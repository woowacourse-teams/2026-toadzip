// 검색엔진·AI 서비스 유입의 분류값. 원시 리퍼러 대신 이 고정 값만 저장하고 전송한다.
// 계정·메일·블로그처럼 검색이 아닌 호스트는 넣지 않는다. 로그인 제공자에서 돌아온 방문이 검색 유입으로 세지기 때문이다.
const HOSTS: Record<string, string> = {
  'search.naver.com': 'search_naver', 'm.search.naver.com': 'search_naver',
  'search.daum.net': 'search_daum', 'm.search.daum.net': 'search_daum',
  'chatgpt.com': 'ai_chatgpt', 'chat.openai.com': 'ai_chatgpt',
  'perplexity.ai': 'ai_perplexity', 'www.perplexity.ai': 'ai_perplexity',
  'gemini.google.com': 'ai_gemini',
  'copilot.microsoft.com': 'ai_copilot',
  'claude.ai': 'ai_claude',
}
// 구글 검색은 국가별 도메인(www.google.co.kr 등)을 쓴다. 하위 서비스(mail., accounts.)는 제외한다.
const GOOGLE_SEARCH = /^(www\.)?google\.(com|[a-z]{2}|com?\.[a-z]{2})$/
// 리퍼러를 보내지 않는 앱에서도 ChatGPT는 링크에 이 출처 코드를 붙인다.
const CAMPAIGN_CODES: Record<string, string> = { 'chatgpt.com': 'ai_chatgpt' }
const SOURCES = new Set([...Object.values(HOSTS), 'search_google'])

export function isOrganicSource(value: unknown): value is string {
  return typeof value === 'string' && SOURCES.has(value)
}

export function organicSourceFromReferrer(referrer: string): string | undefined {
  let url: URL
  try { url = new URL(referrer) } catch { return undefined }
  if (url.protocol !== 'https:') return undefined
  return Object.hasOwn(HOSTS, url.hostname) ? HOSTS[url.hostname]
    : GOOGLE_SEARCH.test(url.hostname) ? 'search_google' : undefined
}

export function organicSourceFromCampaignCode(code: string): string | undefined {
  return Object.hasOwn(CAMPAIGN_CODES, code) ? CAMPAIGN_CODES[code] : undefined
}
