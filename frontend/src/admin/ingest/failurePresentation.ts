import regionIndex from '../../public-housing/regions/regionBoundaryIndex.json'
import type { IngestFailure } from './api'

const regionNames = new Map(regionIndex.regions.map(region => [region.regionCode, region.name]))
const sources: Record<string,string> = {
  MYHOME_COMPLEX:'마이홈 단지 수집', MYHOME_ANNOUNCEMENT:'마이홈 공고 수집',
  LH_LEASE_CATALOG:'LH 임대 단지 목록', LH_ANNOUNCEMENT_CATALOG:'LH 공고 목록',
  LH_ANNOUNCEMENT_DETAIL:'LH 공고 상세', LH_ANNOUNCEMENT_SUPPLY:'LH 공급정보',
}
const supplies: Record<string,string> = {'01':'영구임대','02':'국민임대','03':'50년 공공임대',
  '05':'10년 공공임대','06':'5년 공공임대','10':'행복주택','12':'통합공공임대'}

export function failureTarget(row: IngestFailure) {
  const source = row.source ? sources[row.source] ?? row.source : null
  if (!row.source || !sources[row.source]) return {title:row.target, context:null, source}
  const request = typeof row.raw.requestDescription === 'string' ? row.raw.requestDescription : row.target
  const params = new URLSearchParams(request)
  let title = source ?? '외부 데이터 요청'
  const province = params.get('brtcCode')
  const district = params.get('signguCode')
  if (province && district) {
    const code = district.length === 5 ? district : province + district
    title = regionNames.get(code) ?? `지역 코드 ${code}`
  } else if (params.has('suplyTy')) {
    const code = params.get('suplyTy') ?? ''
    title = supplies[code] ?? `공급 유형 코드 ${code}`
  } else if (params.has('PAN_ID')) {
    title = `공고 ${params.get('PAN_ID')}`
  }
  const page = params.get('pageNo') ?? params.get('PAGE')
  const size = params.get('numOfRows') ?? params.get('PG_SZ')
  const parts = [page && /^[0-9]+$/.test(page) ? `요청 ${page}페이지` : null,
    size && /^[0-9]+$/.test(size) ? `페이지당 ${size}건` : null].filter(Boolean)
  return {title, context:parts.join(' · ') || null, source}
}

export function collectionCause(row: IngestFailure) {
  const message = row.detail
  if (/resultCode=22\b|일일.*요청.*초과/.test(message)) return {
    title:'일일 호출 한도 초과', action:'제공기관의 한도가 초기화된 뒤 다시 수집하세요.',
  }
  if (/resultCode=23\b|초당.*요청.*초과/.test(message)) return {
    title:'요청 속도 제한', action:'호출 간격과 동시 요청 수를 확인하고, 제한 해제 후 다시 수집하세요.',
  }
  if (/HTTP\s*429\b/.test(message)) return {
    title:'외부 API 호출 제한', action:'제공기관의 제한 종류와 해제 시점을 확인한 뒤 다시 수집하세요.',
  }
  if (/HTTP\s*(401|403)\b/.test(message)) return {
    title:'인증·접근 권한 확인 필요', action:'API 인증키와 이용 권한을 확인하세요.',
  }
  if (/HTTP\s*5[0-9]{2}\b|timeout|timed out|시간 초과/i.test(message)) return {
    title:'외부 서버 응답 지연·오류', action:'잠시 뒤 다시 수집하고, 반복되면 제공기관 상태를 확인하세요.',
  }
  if (/내용 없는 행|필수.*누락/.test(message)) return {
    title:'응답 내용 누락', action:'공식 원문과 요청 조건을 확인하세요. 원천에 내용이 없으면 재시도해도 같은 결과가 반복될 수 있습니다.',
  }
  return {title:'외부 응답 확인 필요', action:'상세 기록의 원문 오류를 확인하세요. 같은 요청의 반복 실행만으로 해결되지 않을 수 있습니다.'}
}
