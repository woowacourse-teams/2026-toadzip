/**
 * 공고문 표기 이름(주택형(신청형)·단지 주택타입) 비교 키. 서버 HousingTypeNames.key와 같은 규칙이다. 공급행은 공고 단지마다 신청형 하나이고, 형명 끝의
 * 신청자 표기("36형 일반(고령자)", "26A[청년]")는 그 신청형의 대상이라 뗀다. 공백·대소문자·끝 '형'·앞자리 0("055.7200A")은 무시한다.
 * 신청자 표기를 뗀 이름이 다르면(55A1·55C(주거약자)) 같은 전용면적이어도 다른 신청형이다.
 */
export function applicationTypeKey(name: string | null | undefined): string {
  return withoutApplicant((name ?? '').normalize('NFKC')).replace(/\s+/g, '').toLowerCase().replace(/형$/, '').replace(/^0+(?=\d)/, '')
}

/** 끝의 신청자 표기를 모두 뗀 형명("26A-1형[고령자(주거약자용)]" → "26A-1형"). 표기는 바꾸지 않는다. */
export function withoutApplicant(name: string | null | undefined): string {
  let result = (name ?? '').trim()
  for (let stripped = withoutTrailingGroup(result); stripped !== result; stripped = withoutTrailingGroup(result)) result = stripped
  return result
}

/** 표시명이 가리키는 신청형 키. 여러 표기를 '·'로 이은 이름("55A1 · 55C(주거약자)")은 표기마다 키를 둔다. */
export function applicationTypeKeys(name: string | null | undefined): Set<string> {
  return new Set((name ?? '').split('·').map(applicationTypeKey).filter(Boolean))
}

/**
 * 단지 주택타입 이름 비교 키(서버 HousingTypeNames.typeKey). applicationTypeKey에 더해 면적 숫자로 적은 형명("059.9100A")을 공고문 표기
 * ("59A")와 같게 본다. 공급행 신청형 비교에는 쓰지 않는다.
 */
export function housingTypeKey(name: string | null | undefined): string {
  return applicationTypeKey(name).replace(/^(\d+)\.\d+(?=[a-z])/, '$1')
}

/** 두 표시명이 같은 신청형을 하나라도 가리키는지 */
export function sameApplicationType(left: string | null | undefined, right: string | null | undefined) {
  const keys = applicationTypeKeys(left)
  return [...applicationTypeKeys(right)].some(key => keys.has(key))
}

// 끝의 () 또는 [] 묶음 하나를 뗀다. 이름 전체가 묶음이면 떼지 않는다.
function withoutTrailingGroup(value: string) {
  if (!value.endsWith(')') && !value.endsWith(']')) return value
  let depth = 0
  for (let at = value.length - 1; at >= 0; at--) {
    if (value[at] === ')' || value[at] === ']') depth++
    else if ((value[at] === '(' || value[at] === '[') && --depth === 0) return at === 0 ? value : value.slice(0, at).trim()
  }
  return value
}
