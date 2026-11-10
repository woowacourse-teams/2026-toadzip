import { expect, it } from 'vitest'
import { organicSourceFromCampaignCode, organicSourceFromReferrer } from './organicSource'

it.each([
  ['https://www.google.com/', 'search_google'],
  ['https://www.google.co.kr/search?q=private', 'search_google'],
  ['https://search.naver.com/search.naver?query=private', 'search_naver'],
  ['https://m.search.naver.com/search.naver', 'search_naver'],
  ['https://search.daum.net/search?q=private', 'search_daum'],
  ['https://m.search.daum.net/', 'search_daum'],
  ['https://www.bing.com/search?q=private', 'search_bing'],
  ['https://duckduckgo.com/', 'search_duckduckgo'],
  ['https://chatgpt.com/', 'ai_chatgpt'],
  ['https://chat.openai.com/', 'ai_chatgpt'],
  ['https://www.perplexity.ai/search/private', 'ai_perplexity'],
  ['https://gemini.google.com/app', 'ai_gemini'],
  ['https://copilot.microsoft.com/', 'ai_copilot'],
  ['https://claude.ai/chat/private', 'ai_claude'],
])('검색·AI 리퍼러 %s는 %s로 분류한다', (referrer, source) => {
  expect(organicSourceFromReferrer(referrer)).toBe(source)
})

it.each([
  '', 'not a url', 'android-app://com.google.android.googlequicksearchbox/',
  // 로그인 제공자에서 돌아온 방문은 검색 유입이 아니다.
  'https://accounts.google.com/', 'https://nid.naver.com/', 'https://kauth.kakao.com/',
  // 검색이 아닌 같은 회사 서비스와 이름만 비슷한 도메인.
  'https://mail.google.com/', 'https://blog.naver.com/someone', 'https://cafe.daum.net/', 'https://www.naver.com/',
  'https://google.com.evil.example/', 'https://notchatgpt.com/', 'https://bokduckbang.com/explore',
  'http://www.google.com/',
])('그 밖의 리퍼러 %s는 분류하지 않는다', (referrer) => {
  expect(organicSourceFromReferrer(referrer)).toBeUndefined()
})

it('ChatGPT가 링크에 붙이는 출처 코드만 AI 유입으로 본다', () => {
  expect(organicSourceFromCampaignCode('chatgpt.com')).toBe('ai_chatgpt')
  expect(organicSourceFromCampaignCode('community_a')).toBeUndefined()
  expect(organicSourceFromCampaignCode('search_naver')).toBeUndefined()
})
