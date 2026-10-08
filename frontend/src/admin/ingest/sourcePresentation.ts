import type { SourceCategory } from './sourceApi'

// 마이홈 명칭은 공공데이터포털 활용명세, LH 저장 필드는 수집 DTO의 의미를 따른다.
const commonLabels: Record<string, string> = {
  id: '저장 행 ID', source_key: '원천 식별자', source_order: '원천 행 순서',
  collected_at: '수집 시각', request_hash: '조회 조건 해시',
  hsmp_nm: '단지명', brtc_nm: '시·도', signgu_nm: '시·군·구',
  house_ty_nm: '주택유형', suply_ty_nm: '공급유형', pnu: '토지 고유번호',
  complex_label: '단지명', exclusive_area: '전용면적', total_unit_count: '세대수',
  deposit_text: '임대보증금 원천값', monthly_rent_text: '월임대료 원천값',
  pan_id: '공고 식별자', type_name: '주택형명', supplied_unit_count: '공급세대수',
  supply_area: '공급면적', application_begin_date: '접수 시작일',
  application_end_date: '접수 마감일', winner_announcement_date: '당첨자 발표일',
}

const fieldLabels: Record<SourceCategory, Record<string, string>> = {
  MYHOME_COMPLEX: {
    hsmp_sn: '단지 식별자', instt_nm: '공급기관', brtc_code: '시·도 코드',
    signgu_code: '시·군·구 코드', rn_adres: '도로명주소', compet_de: '준공일',
    hshld_co: '세대수', style_nm: '주택형명', suply_prvuse_ar: '전용면적',
    suply_cmnuse_ar: '공용면적', heat_mthd_detail_nm: '난방방식',
    buld_stle_nm: '건물 형태', elvtr_instl_at_nm: '승강기 설치 여부', parkng_co: '주차대수',
    bass_rent_gtn: '기본 임대보증금', bass_mt_rntchrg: '기본 월임대료',
    bass_cnvrs_gtn_lmt: '기본 전환보증금',
  },
  LH_LEASE_CATALOG: {
    area_name: '지역명', supply_type_name: '공급유형명',
    complex_total_unit_count: '단지 전체 세대수',
  },
  MYHOME_ANNOUNCEMENT: {
    pblanc_id: '공고 식별자', house_sn: '주택 일련번호', sttus_nm: '공고 상태',
    pblanc_nm: '공고명', suply_instt_nm: '공급기관', before_pblanc_id: '이전 공고 식별자',
    rcrit_pblanc_de: '모집 공고일', przwner_presnatn_de: '당첨자 발표일',
    begin_de: '모집 시작일', end_de: '모집 종료일', refrnc: '문의처',
    url: '모집공고 URL', pc_url: '마이홈 PC URL', mobile_url: '마이홈 모바일 URL',
    full_adres: '전체 주소', rn_code_nm: '도로명', refrn_legaldong_nm: '참조 법정동명',
    heat_mthd_nm: '난방방식', tot_hshld_co: '총세대수', sum_suply_co: '공급호수',
    suply_ho_co: '전세임대 공급호수', rent_gtn: '최소 임대보증금', enty: '최소 계약금',
    prtpay: '최소 중도금', surlus: '최소 잔금', mt_rntchrg: '최소 월임대료',
    last_seen_run_id: '마지막 확인 실행 ID', consecutive_miss_count: '연속 미조회 횟수',
    active: '원천 활성 여부',
  },
  LH_ANNOUNCEMENT_CATALOG: {
    PAN_NM: '공고명', PAN_ID: '공고 식별자', PAN_SS: '공고 상태', CNP_CD_NM: '지역명',
    AIS_TP_CD_NM: '공고유형명', UPP_AIS_TP_NM: '상위 공고유형명',
    PAN_NT_ST_DT: '공고일', PAN_DT: '게시일', CLSG_DT: '마감일',
    DTL_URL: '공고 원문 URL', DTL_URL_MOB: '모바일 공고 원문 URL',
    CCR_CNNT_SYS_DS_CD: '연계 시스템 구분코드', UPP_AIS_TP_CD: '상위 공고유형 코드',
    AIS_TP_CD: '공고유형 코드', SPL_INF_TP_CD: '공급정보 유형코드',
    ALL_CNT: '전체 응답 건수', RNUM: '원천 행번호',
  },
  LH_ANNOUNCEMENT_DETAIL: {
    dataset_type: '상세 데이터 종류', complex_name: '단지명', address: '주소',
    detail_address: '상세주소', heating_description: '난방방식',
    exclusive_area_range: '전용면적 범위', expected_move_in_year_month: '입주 예정 연월',
    guidance_text: '공급 안내', application_period: '접수 기간 원천값',
    document_target_announcement_date: '서류 제출 대상자 발표일',
    document_submission_begin_date: '서류 제출 시작일', document_submission_end_date: '서류 제출 마감일',
    contract_begin_date: '계약 시작일', contract_end_date: '계약 마감일',
    reception_address: '접수처 주소', reception_detail_address: '접수처 상세주소',
    operation_begin: '업무 시작 시각', operation_end: '업무 종료 시각', phone: '전화번호',
    reception_guidance: '접수 안내', kind: '첨부 종류', name: '첨부파일명', url: '첨부파일 URL',
    attachment_complex_name: '첨부 대상 단지명', correction_reason: '정정 사유', etc_contents: '기타 내용',
  },
  LH_ANNOUNCEMENT_SUPPLY: {},
}

export const sourcePresentation: Record<SourceCategory, {
  provider: string; documentationUrl: string; firstFields: string[]
}> = {
  MYHOME_COMPLEX: {
    provider: '국토교통부 · 마이홈', documentationUrl: 'https://www.data.go.kr/data/15110581/openapi.do',
    firstFields: ['hsmp_nm', 'suply_ty_nm', 'style_nm', 'suply_prvuse_ar', 'bass_rent_gtn',
      'bass_mt_rntchrg', 'bass_cnvrs_gtn_lmt', 'brtc_nm', 'signgu_nm', 'rn_adres', 'instt_nm', 'hshld_co'],
  },
  LH_LEASE_CATALOG: {
    provider: '한국토지주택공사 · LH', documentationUrl: 'https://www.data.go.kr/data/15059475/openapi.do',
    firstFields: ['complex_label', 'area_name', 'supply_type_name', 'exclusive_area',
      'deposit_text', 'monthly_rent_text', 'total_unit_count', 'complex_total_unit_count'],
  },
  MYHOME_ANNOUNCEMENT: {
    provider: '국토교통부 · 마이홈', documentationUrl: 'https://www.data.go.kr/data/15108420/openapi.do',
    firstFields: ['pblanc_nm', 'sttus_nm', 'suply_instt_nm', 'begin_de', 'end_de',
      'hsmp_nm', 'rent_gtn', 'mt_rntchrg', 'suply_ty_nm', 'full_adres', 'sum_suply_co'],
  },
  LH_ANNOUNCEMENT_CATALOG: {
    provider: '한국토지주택공사 · LH', documentationUrl: 'https://www.data.go.kr/data/15058530/openapi.do',
    firstFields: ['PAN_NM', 'PAN_SS', 'CNP_CD_NM', 'AIS_TP_CD_NM', 'PAN_NT_ST_DT', 'CLSG_DT', 'PAN_ID'],
  },
  LH_ANNOUNCEMENT_DETAIL: {
    provider: '한국토지주택공사 · LH', documentationUrl: 'https://www.data.go.kr/data/15057999/openapi.do',
    firstFields: ['dataset_type', 'complex_name', 'application_begin_date', 'application_end_date',
      'winner_announcement_date', 'address', 'name', 'url'],
  },
  LH_ANNOUNCEMENT_SUPPLY: {
    provider: '한국토지주택공사 · LH', documentationUrl: 'https://www.data.go.kr/data/15056765/openapi.do',
    firstFields: ['complex_label', 'type_name', 'exclusive_area', 'supply_area',
      'deposit_text', 'monthly_rent_text', 'supplied_unit_count', 'total_unit_count', 'pan_id'],
  },
}

export function sourceFieldLabel(category: SourceCategory, path: string): string {
  const key = path.replace(/\[\d+\]/g, '').split('.').at(-1) ?? path
  return fieldLabels[category][key] ?? commonLabels[key] ?? '의미 확인 필요'
}
