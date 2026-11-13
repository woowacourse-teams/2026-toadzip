import { expect, it } from 'vitest'
import { applicationTypeKey, applicationTypeKeys, housingTypeKey, sameApplicationType } from './applicationType'
import { sameTypeName } from '../announcement-pdf/areas'

it('끝의 신청자 표기와 공백·대소문자·끝 형·앞자리 0은 무시한다(서버 HousingTypeNames와 같은 규칙)', () => {
  expect(applicationTypeKey('36형 일반(고령자)')).toBe(applicationTypeKey('36형 일반 (청년)'))
  expect(applicationTypeKey('26A[청년]')).toBe(applicationTypeKey('26a [주거급여수급자]'))
  expect(applicationTypeKey('26A-1형[고령자(주거약자용)]')).toBe('26a-1')
  expect(applicationTypeKey('055.7200A1')).toBe(applicationTypeKey('55.7200A1'))
  expect(applicationTypeKey('(주거약자)')).toBe('(주거약자)')
  expect(applicationTypeKey(null)).toBe('')
})

it('신청자 표기를 뗀 이름이 다르면 같은 면적이어도 다른 신청형이고, 가운뎃점으로 이은 표시명은 표기마다 비교한다', () => {
  expect(sameApplicationType('55A1', '55C(주거약자)')).toBe(false)
  expect(sameApplicationType('55A1 · 55C(주거약자)', '55C (주거약자)')).toBe(true)
  expect([...applicationTypeKeys('26A·26B')]).toEqual(['26a', '26b'])
  expect(sameApplicationType('', '')).toBe(false)
})

it('같은 형명의 주거약자용 주택(26A·26A(주거약자))은 다른 신청형이고, 다른 신청자와 함께 적은 표기는 신청자 표기다(서버 HousingTypeNames.applicationKeys)', () => {
  expect(sameApplicationType('26A', '26A(주거약자)')).toBe(false)
  expect(sameApplicationType('26A · 26A(주거약자)', '26A (주거약자)')).toBe(true)
  expect(sameApplicationType('37B(주거약자용)', '37B')).toBe(false)
  expect([...applicationTypeKeys('26A · 26A(주거약자형)')]).toEqual(['26a', '26a(주거약자형)'])
  expect(sameApplicationType('26A-1형[고령자(주거약자용)]', '26A-1형[청년]')).toBe(true)
  expect(sameApplicationType('(주거약자)', '(주거약자)')).toBe(true)
  // 단지 주택타입 이름 비교는 그대로 둔다.
  expect(applicationTypeKey('26A(주거약자)')).toBe('26a')
})

it('단지 주택타입 이름은 면적 숫자로 적은 형명(059.9100A)을 공고문 표기(59A)와 같게 보고, 신청형 비교는 그대로 둔다', () => {
  expect(housingTypeKey('059.9100A')).toBe(housingTypeKey('59A'))
  expect(sameTypeName('059.9100A', '59A', 59.91, 59.91)).toBe(true)
  expect(sameTypeName('84.9600B', '84B형', 84.96, 84.96)).toBe(true)
  // 숫자 형명은 소수 자리(면적)를 버려 맞추므로 면적이 다르면 다른 집이다.
  expect(sameTypeName('059.9300A', '59A', 59.93, 59.91)).toBe(false)
  expect(sameTypeName('59A', '59a형', 59.93, 59.91)).toBe(true)
  expect(sameTypeName('59B-1', '59D-1')).toBe(false)
  expect(sameApplicationType('059.9100A', '59A')).toBe(false)
})
