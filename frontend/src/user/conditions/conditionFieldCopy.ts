import type { DateEventKind, FamilyType, ProfileField } from './conditionsModel'

export type FieldKind = 'date' | 'region' | 'bool' | 'money' | 'count' | 'familyType' | 'dateEvent'
export type StepNumber = 1 | 2 | 3

export interface FieldCopy {
  readonly label: string
  readonly question: string
  readonly kind: FieldKind
  readonly help?: string
  /** "나중에 입력할게요"를 고를 수 있는 항목 */
  readonly skippable?: boolean
  readonly monthly?: boolean
  /** 금액을 0으로 정하는 체크 문구 */
  readonly zeroLabel?: string
  readonly notApplicableLabel?: string
  readonly countUnit?: string
  /** 가족 정보(선택) 묶음 */
  readonly optional?: boolean
  readonly min?: number
  readonly kinds?: readonly (readonly [DateEventKind, string])[]
}

export const FIELD_COPY: Record<ProfileField, FieldCopy> = {
  BIRTH_DATE: { label: '생년월일', question: '생년월일을 알려주세요', kind: 'date', help: '만 19세 이상부터 이용할 수 있어요.' },
  RESIDENCE_REGION: { label: '거주지역', question: '어디에 살고 있나요?', kind: 'region', help: '주민등록상 주소 기준이에요.' },
  CURRENT_REGION_RESIDENCE_MONTHS: { label: '현재 지역 거주기간', question: '현재 지역에서 얼마나 살았나요?', kind: 'count', min: 0, countUnit: '개월', skippable: true, help: '현재 거주지역에서 계속 거주한 완료 개월 수예요. 현재 집에서 산 기간과는 달라요.' },
  CURRENT_LEASE_END_DATE: { label: '현재 집 계약 종료일', question: '현재 사는 집의 계약은 언제 끝나나요?', kind: 'date', skippable: true, notApplicableLabel: '계약 종료일이 없어요', help: '현재 집의 계약 종료일이에요. 모르면 나중에 입력할 수 있어요.' },
  IS_UNIVERSITY_STUDENT: { label: '대학생 여부', question: '대학생인가요?', kind: 'bool', help: '휴학생·졸업 예정자 인정 여부는 공고마다 달라요.' },
  IS_JOB_SEEKER: { label: '취업준비생 여부', question: '취업을 준비하고 있나요?', kind: 'bool', help: "학교를 졸업·중퇴한 지 2년 이내이고 지금 소득 활동을 하지 않으면 '예'예요. 인정 기준은 공고마다 달라요." },
  APPLICANT_OWNS_HOUSE: { label: '본인 주택 소유', question: '본인 명의의 주택이 있나요?', kind: 'bool', help: '분양권·입주권을 주택으로 보는지는 공고마다 달라요.' },
  APPLICANT_OWNS_CAR: { label: '본인 자동차 소유', question: '본인 명의의 자동차가 있나요?', kind: 'bool' },
  APPLICANT_CAR_VALUE: { label: '본인 차량 가격', question: '차량 가격은 얼마인가요?', kind: 'money', skippable: true, help: '여러 대라면 가장 비싼 차량 기준이에요.' },
  APPLICANT_ASSETS: { label: '본인 자산', question: '본인 자산은 모두 얼마인가요?', kind: 'money', skippable: true, zeroLabel: '자산이 없어요', help: '부동산·예금·주식 등을 합한 금액이에요. 정확한 산정 방식은 공고마다 달라요.' },
  APPLICANT_MONTHLY_INCOME: { label: '본인 월소득', question: '세전 월평균 소득은 얼마인가요?', kind: 'money', monthly: true, skippable: true, zeroLabel: '소득이 없어요', help: '연봉이라면 12로 나눈 금액이에요.' },
  FAMILY_TYPE: { label: '가족 형태', question: '가족 형태를 골라 주세요', kind: 'familyType' },
  IS_HOUSEHOLD_SEPARATED: { label: '세대분리 여부', question: '부모님과 세대가 분리되어 있나요?', kind: 'bool', help: "주민등록등본에 부모님과 따로 올라 있으면 '예'예요. 세대주인지와는 달라요." },
  PARENTS_MONTHLY_INCOME: { label: '부모님 월소득 합계', question: '부모님 월소득을 합하면 얼마인가요?', kind: 'money', monthly: true, skippable: true, help: '소득이 없는 대학생은 부모님 소득으로 비교하는 공고가 있어요.' },
  REGISTERED_FAMILY_COUNT: { label: '등본상 가족 수', question: '주민등록등본에 함께 올라 있는 가족은 몇 명인가요?', kind: 'count', min: 1, help: '본인을 포함하고 태아는 빼고 세어 주세요.' },
  MARRIAGE_EVENT: { label: '혼인신고일', question: '혼인신고일을 알려주세요', kind: 'dateEvent', skippable: true, kinds: [['REGISTERED', '신고했어요'], ['PLANNED', '신고 예정이에요']] },
  HAS_CHILDREN_OR_PREGNANCY: { label: '자녀·임신 여부', question: '자녀가 있거나 임신 중인가요?', kind: 'bool' },
  CHILDREN_COUNT_INCLUDING_FETUS: { label: '자녀 수', question: '자녀는 몇 명인가요?', kind: 'count', min: 1, help: '태아도 포함해 주세요.' },
  YOUNGEST_CHILD: { label: '막내 생년월일', question: '가장 어린 자녀의 생년월일을 알려주세요', kind: 'dateEvent', skippable: true, kinds: [['BORN', '태어났어요'], ['EXPECTED', '출산 예정이에요']] },
  IS_DUAL_INCOME: { label: '맞벌이 여부', question: '부부가 모두 소득이 있나요?', kind: 'bool' },
  FAMILY_OWNS_HOUSE: { label: '가족 주택 소유', question: '가족 중 주택을 가진 사람이 있나요?', kind: 'bool', optional: true, help: '본인을 포함해 주세요.' },
  FAMILY_OWNS_CAR: { label: '가족 자동차 소유', question: '가족 중 자동차를 가진 사람이 있나요?', kind: 'bool', optional: true, help: '본인을 포함해 주세요.' },
  FAMILY_CAR_VALUE: { label: '가족 차량 최고 가격', question: '가족 차량 중 가장 비싼 차량 가격은요?', kind: 'money', skippable: true, optional: true },
  FAMILY_TOTAL_ASSETS: { label: '가족 총자산', question: '가족의 총자산은 얼마인가요?', kind: 'money', skippable: true, optional: true, zeroLabel: '자산이 없어요', help: '본인을 포함한 금액이에요.' },
  HOUSEHOLD_MONTHLY_INCOME: { label: '세대 합산 월소득', question: '등본상 가족의 월소득을 합하면 얼마인가요?', kind: 'money', monthly: true, skippable: true, help: '세전 금액이고, 본인 소득도 포함해 주세요.' },
}

export const STEP_COPY: Record<StepNumber, { readonly name: string; readonly title: string; readonly description: string }> = {
  1: { name: '본인 정보', title: '나에 대해 알려주세요', description: '공고의 나이·거주지역·신분 조건과 비교해요.' },
  2: { name: '소득·자산', title: '소득과 자산을 알려주세요', description: '대략적인 금액도 괜찮아요. 꺼려지는 금액은 나중에 입력할 수 있어요.' },
  3: { name: '가족·세대', title: '가족 형태를 알려주세요', description: '고른 가족 형태에 필요한 질문만 이어서 보여 드려요.' },
}

export const FAMILY_TYPE_COPY: Record<FamilyType, { readonly label: string; readonly description: string }> = {
  UNMARRIED: { label: '미혼', description: '결혼하지 않았어요' },
  SINGLE_PARENT: { label: '한부모', description: '혼자 자녀를 키우고 있어요 (미혼모·미혼부 포함)' },
  COUPLE_OR_ENGAGED: { label: '부부·예비부부', description: '혼인신고를 했거나 할 예정이에요' },
}

export const FAMILY_DETAIL_FIELDS: readonly ProfileField[] = ['FAMILY_OWNS_HOUSE', 'FAMILY_OWNS_CAR', 'FAMILY_CAR_VALUE', 'FAMILY_TOTAL_ASSETS']
