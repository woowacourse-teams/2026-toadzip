import { describe, expect, it } from 'vitest'
import { findPrerenderProblems, injectIntoTemplate, prerenderedFilePath } from './prerenderedDocument'

const body = '공공주택 위치와 임대 조건, 모집 공고와 신청 일정을 한곳에서 확인할 수 있는 서비스 화면입니다.'

describe('프리렌더 문서', () => {
  it('제목·본문·내부 링크가 있으면 문제가 없다', () => {
    expect(findPrerenderProblems(`<main><h1>공공주택 찾기</h1><p>${body}</p><a href="/explore">시작하기</a></main>`)).toEqual([])
  })

  it('빈 셸이면 제목·본문·링크가 모두 없다고 알린다', () => {
    expect(findPrerenderProblems('')).toEqual(['h1이 1개가 아님(0개)', '본문이 너무 짧음(0자)', '내부 링크 없음'])
  })

  it('h1이 여러 개면 알린다', () => {
    expect(findPrerenderProblems(`<h1>하나</h1><h1>둘</h1><p>${body}</p><a href="/explore">이동</a>`))
      .toEqual(['h1이 1개가 아님(2개)'])
  })

  it('외부·문서 내 링크만 있으면 내부 링크로 보지 않는다', () => {
    expect(findPrerenderProblems(`<h1>제목</h1><p>${body}</p><a href="https://example.com/">외부</a><a href="#top">위로</a><a href="//cdn.example.com/x">CDN</a>`))
      .toEqual(['내부 링크 없음'])
  })

  it('빌드 HTML의 빈 루트에 본문을 넣는다', () => {
    const template = '<html><body><div id="root"></div><script src="/assets/main.js"></script></body></html>'
    expect(injectIntoTemplate(template, '<h1>$& 제목</h1>'))
      .toBe('<html><body><div id="root"><h1>$& 제목</h1></div><script src="/assets/main.js"></script></body></html>')
  })

  it('빈 루트가 정확히 하나가 아니면 실패한다', () => {
    expect(() => injectIntoTemplate('<div id="root">x</div>', '<h1>제목</h1>')).toThrow('빈 루트')
    expect(() => injectIntoTemplate('<div id="root"></div><div id="root"></div>', '<h1>제목</h1>')).toThrow('빈 루트')
  })

  it('경로별 출력 파일은 prerendered 폴더 아래의 index.html이다', () => {
    expect(prerenderedFilePath('/')).toBe('prerendered/index.html')
    expect(prerenderedFilePath('/explore')).toBe('prerendered/explore/index.html')
  })
})
