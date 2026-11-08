import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import type { DataPipelineExecution } from './api'
import { PipelineExecutionSteps } from './PipelineExecutionSteps'

function execution(overrides: Partial<DataPipelineExecution> = {}): DataPipelineExecution {
  return { executionId: 'run-1', type: 'ANNOUNCEMENT_REGISTRATION', status: 'RUNNING',
    currentStepName: '마이홈 공고 수집', currentStepIndex: 1, totalStepCount: 4,
    completedSteps: [], skippedSteps: [], partiallyFailedSteps: [], failure: null,
    startedAt: '2026-10-08T01:00:00Z', ...overrides }
}
function show(value: DataPipelineExecution, onStop?: () => void) {
  return render(<MemoryRouter><PipelineExecutionSteps execution={value} onStop={onStop} /></MemoryRouter>)
}

describe('v2 공통 단계 표시', () => {
  it('실행 콜백이 없는 수집 실패 이력에서도 전체 원천 정제 화면으로 이동한다', () => {
    show(execution({ type: 'ANNOUNCEMENT_SYNC', status: 'FAILED', currentStepIndex: 0 }))
    expect(screen.getByRole('link', { name: '공고 정제 화면으로 이동' })).toHaveAttribute('href',
      '/admin/failures?domain=announcement&category=all&executionId=run-1')
    expect(screen.getByText(/정제는 현재 저장된 원천 전체/)).toBeVisible()
  })

  it.each(['COMPLEX_SYNC', 'ANNOUNCEMENT_SYNC', 'COMPLEX_REFINEMENT', 'ANNOUNCEMENT_REFINEMENT'] as const)(
    '%s 완료 경고는 종료 단계 인덱스와 관계없이 보강을 포함한 전체 실패 목록으로 연결한다', type => {
      show(execution({ type, status: 'COMPLETED_WARNINGS', currentStepIndex: 0 }))
      expect(screen.getByRole('link', { name: '실패 내역 확인' })).toHaveAttribute('href',
        `/admin/failures?domain=${type.startsWith('COMPLEX_') ? 'complex' : 'announcement'}&category=all&executionId=run-1`)
    },
  )

  it('SH 수집은 수집 뒤 정제 순서와 중지 버튼을 표시한다', () => {
    show(execution({ type: 'SH_ANNOUNCEMENT_COLLECTION', currentStepName: 'SH 공고 원천 수집', totalStepCount: 2 }), vi.fn())
    expect(screen.getByRole('list', { name: '실행 단계' }).children).toHaveLength(2)
    expect(screen.getByRole('button', { name: 'SH 공고 원천 수집 진행' })).toHaveAttribute('aria-current', 'step')
    expect(screen.getByRole('button', { name: 'SH 공고 수집 실행 중지' })).toBeVisible()
  })
  it('지난 단건 등록 실패 이력은 매칭 진입 없이 지원 종료를 안내한다', () => {
    show(execution({ status: 'FAILED', targetAnnouncementIdentifier: '21395',
      failure: { stepName: '마이홈 공고 정제', message: '공급행 매칭 실패: 주택형을 확정할 수 없습니다.', serverResponse: null } }))
    expect(screen.getByText('공급행 매칭 실패: 주택형을 확정할 수 없습니다.')).toBeVisible()
    expect(screen.queryByRole('button', { name: '단지·주택형 매칭' })).not.toBeInTheDocument()
    expect(screen.getByRole('note')).toHaveTextContent('마이홈 공고 단건 등록은 지원을 종료했습니다. 공고는 LH 공고 정제와 PDF 보정으로만 바뀝니다.')
  })

  it('공고 정제는 LH 공고 정제 한 단계이고 LH 공고 갱신은 목록 수집 뒤 수집·정제 한 단계로 이어진다', () => {
    show(execution({ type: 'ANNOUNCEMENT_REFINEMENT', currentStepName: 'LH 공고 정제', totalStepCount: 1 }))
    const refinement = screen.getByRole('list', { name: '실행 단계' })
    expect(within(refinement).getAllByRole('listitem')).toHaveLength(1)
    expect(within(refinement).getByRole('button', { name: 'LH 공고 정제 진행' })).toHaveAttribute('aria-current', 'step')
    cleanup()
    show(execution({ type: 'ANNOUNCEMENT_SYNC', currentStepName: 'LH 공고 목록 수집', totalStepCount: 2 }))
    expect(within(screen.getByRole('list', { name: '실행 단계' })).getAllByRole('button').map(item => item.getAttribute('aria-label'))).toEqual([
      'LH 공고 목록 수집 진행', 'LH 공고 수집·정제 대기',
    ])
  })

  it('수집·정제를 합치기 전 LH 공고 갱신 이력은 기록된 네 단계로 보여 준다', () => {
    show(execution({ type: 'ANNOUNCEMENT_SYNC', status: 'COMPLETED', currentStepName: null, totalStepCount: 4,
      completedSteps: ['LH 공고 목록 수집', 'LH 공고 공급 원본 수집', 'LH 공고 상세 원본 수집', 'LH 공고 정제'] }))
    expect(within(screen.getByRole('list', { name: '실행 단계' })).getAllByRole('button').map(item => item.getAttribute('aria-label'))).toEqual([
      'LH 공고 목록 수집 성공', 'LH 공고 공급 원본 수집 성공', 'LH 공고 상세 원본 수집 성공', 'LH 공고 정제 성공',
    ])
  })

  it('마이홈 공고 수집으로 시작한 지난 공고 수집 이력은 그 단계를 앞에 보여 준다', () => {
    show(execution({ type: 'ANNOUNCEMENT_COLLECTION', status: 'COMPLETED', currentStepName: null, totalStepCount: 3,
      completedSteps: ['마이홈 공고 수집', 'LH 공고 목록 수집', 'LH 공고 공급 원본 수집', 'LH 공고 상세 원본 수집'] }))
    expect(within(screen.getByRole('list', { name: '실행 단계' })).getAllByRole('button').map(item => item.getAttribute('aria-label'))).toEqual([
      '마이홈 공고 수집 성공', 'LH 공고 목록 수집 성공', 'LH 공고 공급 원본 수집 성공', 'LH 공고 상세 원본 수집 성공',
    ])
  })

  it('옛 정제 단계로 끝난 지난 공고 정제 이력은 기록된 옛 단계 이름으로 보여 준다', () => {
    show(execution({ type: 'ANNOUNCEMENT_REFINEMENT', status: 'COMPLETED_WARNINGS', currentStepName: null,
      completedSteps: ['마이홈 공고 정제', 'LH 공고 상세·공급 정보 보강'],
      partiallyFailedSteps: [{ step: 'MAP_MYHOME_ANNOUNCEMENTS', stepName: '마이홈 공고 정제', report: {} }] }))
    const steps = screen.getByRole('list', { name: '실행 단계' })
    expect(within(steps).getAllByRole('listitem')).toHaveLength(2)
    expect(within(steps).getByRole('button', { name: '마이홈 공고 정제 부분 실패' })).toBeVisible()
    expect(within(steps).getByRole('button', { name: 'LH 공고 상세·공급 정보 보강 성공' })).toBeVisible()
    expect(within(steps).queryByRole('button', { name: /LH 공고 정제/ })).not.toBeInTheDocument()
  })
  it('단건은 실제 네 단계와 진행·대기를 표시하며 개별 시각을 추정하지 않는다', () => {
    show(execution())
    const steps = screen.getByRole('list', { name: '실행 단계' })
    expect(within(steps).getAllByRole('listitem')).toHaveLength(4)
    expect(within(steps).getByRole('button', { name: /마이홈 원천 확보 진행/ })).toHaveAttribute('aria-current', 'step')
    expect(within(steps).getByRole('button', { name: /LH 공급 수집 대기/ })).toBeVisible()
    expect(screen.getByText(/종료: 기록 없음/)).toBeVisible()
  })

  it('완료 단계 표시 이름만 남은 지난 공고 정제 이력도 옛 두 단계 성공으로 보여 준다', () => {
    show(execution({ type: 'ANNOUNCEMENT_REFINEMENT', status: 'COMPLETED', currentStepName: null,
      completedSteps: ['마이홈 공고 정제', 'LH 공고 상세·공급 정보 보강'] }))
    expect(within(screen.getByRole('list', { name: '실행 단계' })).getAllByRole('button').map(item => item.getAttribute('aria-label'))).toEqual([
      '마이홈 공고 정제 성공', 'LH 공고 상세·공급 정보 보강 성공',
    ])
  })

  it('옛 정제 단계에서 실패한 지난 공고 동기화 이력은 수집 네 단계와 옛 두 단계로 보여 준다', () => {
    show(execution({ type: 'ANNOUNCEMENT_SYNC', status: 'FAILED', currentStepName: null, totalStepCount: 6,
      completedSteps: ['마이홈 공고 수집', 'LH 공고 목록 수집', 'LH 공고 공급 원본 수집', 'LH 공고 상세 원본 수집'],
      failure: { stepName: '마이홈 공고 정제', message: '정제 실패', serverResponse: null } }))
    expect(within(screen.getByRole('list', { name: '실행 단계' })).getAllByRole('button').map(item => item.getAttribute('aria-label'))).toEqual([
      '마이홈 공고 수집 성공', 'LH 공고 목록 수집 성공', 'LH 공고 공급 원본 수집 성공', 'LH 공고 상세 원본 수집 성공',
      '마이홈 공고 정제 실패', 'LH 공고 상세·공급 정보 보강 미실행',
    ])
  })

  it('부분 실패와 치명 실패를 구분하고 후속 단계를 미실행으로 표시한다', () => {
    show(execution({ type: 'ANNOUNCEMENT_SYNC', status: 'FAILED',
      completedSteps: ['마이홈 공고 수집'],
      partiallyFailedSteps: [{ step: 'COLLECT_LH_ANNOUNCEMENT_CATALOG', stepName: 'LH 공고 목록 수집', report: { failedRequestCount: 1 } }],
      skippedSteps: [{ stepName: 'LH 공고 공급 원본 수집', reason: '호출 제한', serverResponse: {} }],
      failure: { stepName: 'LH 공고 상세 원본 수집', message: '외부 API 실패', serverResponse: { failedRequestCount: 2 } },
    }))
    const steps = screen.getByRole('list', { name: '실행 단계' })
    expect(within(steps).getByRole('button', { name: /마이홈 공고 수집 성공/ })).toBeVisible()
    expect(within(steps).getByRole('button', { name: /LH 공고 목록 수집 부분 실패/ })).toBeVisible()
    expect(within(steps).getByRole('button', { name: /공급 원본 수집 생략/ })).toBeVisible()
    fireEvent.click(within(steps).getByRole('button', { name: /상세 원본 수집 실패/ }))
    const detail = screen.getByRole('region', { name: '단계 상세' })
    expect(within(detail).getByText('외부 API 실패')).toBeVisible()
    expect(within(detail).getByRole('link', { name: '실패 대상 확인' })).toHaveAttribute('href',
      '/admin/failures?domain=announcement&category=collection&executionId=run-1')
    expect(within(detail).getByText('상세 보고서').closest('details')).not.toHaveAttribute('open')
    expect(within(steps).getByRole('button', { name: /LH 공고 정제 미실행/ })).toBeVisible()
  })

  it('부분 실패 뒤 후속 성공을 유지하고 최종 실패 사유를 중복 치명 실패로 표시하지 않는다', () => {
    show(execution({ status: 'FAILED', completedSteps: ['마이홈 공고 정제'],
      partiallyFailedSteps: [{ step: 'COLLECT_MYHOME_ANNOUNCEMENTS', stepName: '마이홈 공고 수집', report: {} }],
      failure: { stepName: '마이홈 공고 수집', message: '일부 실패', serverResponse: {} } }))
    expect(screen.getByRole('button', { name: /마이홈 원천 확보 부분 실패/ })).toBeVisible()
    expect(screen.getByRole('button', { name: /정제·저장 성공/ })).toBeVisible()
  })

  it('LH 외 기관은 해당 없음으로 표시하고 정제 성공과 관리 링크를 유지한다', () => {
    show(execution({ status: 'COMPLETED_WITH_SKIPS', currentStepName: null,
      completedSteps: ['마이홈 공고 수집', '마이홈 공고 정제'],
      skippedSteps: ['LH 공고 공급 원본 수집', 'LH 공고 상세 원본 수집'].map(stepName =>
        ({ stepName, reason: '해당 없음: LH 외 기관입니다.', serverResponse: {} })) }))
    expect(screen.getAllByRole('button', { name: /해당 없음/ })).toHaveLength(2)
    expect(screen.getByRole('button', { name: /정제·저장 성공/ })).toBeVisible()
    expect(screen.getByRole('link', { name: '공고 관리로 이동' })).toBeVisible()
  })

  it('중지한 현재 단계와 아직 실행하지 않은 다음 단계를 구분한다', () => {
    show(execution({ status: 'STOPPED', currentStepName: 'LH 공고 공급 원본 수집',
      completedSteps: ['마이홈 공고 수집'] }))
    expect(screen.getByRole('button', { name: /LH 공급 수집 중지/ })).toBeVisible()
    expect(screen.getByRole('button', { name: /LH 상세 수집 미실행/ })).toBeVisible()
  })

  it('실행 화면에만 중지를 제공하고 요청 후에는 잠근다', () => {
    const stop = vi.fn()
    show(execution({ stopRequested: true }), stop)
    expect(screen.getByRole('button', { name: '중지 요청 중…' })).toBeDisabled()
    expect(stop).not.toHaveBeenCalled()
  })

  it('단지 수집·정제의 실제 네 단계와 정제 실패 대상 링크를 표시한다', () => {
    show(execution({ type: 'COMPLEX_SYNC', status: 'FAILED',
      failure: { stepName: '마이홈 단지 정제', message: '단지 매칭 실패', serverResponse: null } }))
    expect(screen.getByRole('list', { name: '실행 단계' }).children).toHaveLength(4)
    fireEvent.click(screen.getByRole('button', { name: /마이홈 단지 정제 실패/ }))
    expect(screen.getByRole('link', { name: '실패 대상 확인' })).toHaveAttribute('href',
      '/admin/failures?domain=complex&category=complex&executionId=run-1')
    expect(screen.queryByRole('button', { name: /실행 중지/ })).not.toBeInTheDocument()
  })
})

it.each(['SH', 'GH'] as const)('%s 정제 단계에서 원천별 처리 결과를 읽는다', agency => {
  show(execution({ type: `${agency}_ANNOUNCEMENT_COLLECTION`, status: 'COMPLETED', totalStepCount: 2,
    currentStepName: null, completedSteps: [`${agency} 공고 원천 수집`, `${agency} 공고 정제`],
    completedStepResults: [{ step: `MAP_${agency}_ANNOUNCEMENTS`, stepName: `${agency} 공고 정제`,
      report: { targetCount: 8, created: 2, updated: 1, unchanged: 3, reviewRequired: 1, trashed: 1, failed: 0,
        ...(agency === 'GH' ? { supplyRecheckRequired: 1 } : {}) } }] }))
  fireEvent.click(screen.getByRole('button', { name: `${agency} 공고 정제 성공` }))
  for (const label of ['대상 공고', '신규 공고', '갱신 공고', '변경 없는 공고', '원천 검토 필요', '휴지통 공고', '정제 실패']) {
    expect(screen.getByText(label)).toBeVisible()
  }
  expect(screen.getByText('대상 공고').parentElement).toHaveTextContent('8')
  if (agency === 'GH') expect(screen.getByText('공급 재확인 필요')).toBeVisible()
})
it.each(['SH', 'GH'] as const)('%s 수집·정제 단계는 한 단계로 보이고 수집·정제 수치를 나눠 보여 준다', agency => {
  show(execution({ type: `${agency}_ANNOUNCEMENT_COLLECTION`, status: 'COMPLETED', totalStepCount: 1, currentStepName: null,
    completedSteps: [`${agency} 공고 수집·정제`],
    completedStepResults: [{ step: `COLLECT_REFINE_${agency}_ANNOUNCEMENTS`, stepName: `${agency} 공고 수집·정제`,
      report: { collection: { storedRowCount: 7, failedRequestCount: 0 }, refinement: { targetCount: 8, created: 2, revisionsLinked: 1, revisionsUnlinked: 0, failed: 0, failures: [] } } }] }))
  expect(screen.getByRole('list', { name: '실행 단계' }).children).toHaveLength(1)
  fireEvent.click(screen.getByRole('button', { name: `${agency} 공고 수집·정제 성공` }))
  expect(screen.getByText('수집')).toBeVisible()
  expect(screen.getByText('정제')).toBeVisible()
  expect(screen.getByText('저장한 행').parentElement).toHaveTextContent('7')
  expect(screen.getByText('신규 공고').parentElement).toHaveTextContent('2')
  expect(screen.getByText('정정공고 연결').parentElement).toHaveTextContent('1')
})
it.each(['SH', 'GH'] as const)('%s 수집·정제의 정제 실패 원천은 해당 기관 원천으로 안내하고 수집 실패 내역도 연결한다', agency => {
  show(execution({ type: `${agency}_ANNOUNCEMENT_COLLECTION`, status: 'FAILED', totalStepCount: 1,
    failure: { stepName: `${agency} 공고 수집·정제`, message: '일부 실패', serverResponse: {
      collection: { failedRequestCount: 0 },
      refinement: { failed: 1, failures: [{ sourceKey: `${agency}:100`, detail: '원천 확인 필요' }] },
    } } }))
  fireEvent.click(screen.getByRole('button', { name: `${agency} 공고 수집·정제 실패` }))
  expect(screen.getByRole('link', { name: `${agency}:100` })).toHaveAttribute('href',
    `/admin/sources?category=${agency}_ANNOUNCEMENT&keyword=${agency}%3A100`)
  expect(screen.getByRole('link', { name: '실패 대상 확인' })).toHaveAttribute('href',
    '/admin/failures?domain=announcement&category=collection&executionId=run-1')
})
it.each(['SH', 'GH'] as const)('%s의 정제 연결 전 성공 이력은 수행했던 수집 한 단계만 보여 준다', agency => {
  show(execution({ type: `${agency}_ANNOUNCEMENT_COLLECTION`, status: 'COMPLETED', currentStepName: null,
    completedSteps: [`${agency} 공고 원천 수집`] }))
  expect(screen.getByRole('list', { name: '실행 단계' }).children).toHaveLength(1)
  expect(screen.queryByRole('button', { name: `${agency} 공고 정제 미실행` })).not.toBeInTheDocument()
})


it.each(['SH', 'GH'] as const)('%s 정제 실패는 대상·원인과 해당 기관 원천을 보여 주고 LH 정제로 보내지 않는다', agency => {
  show(execution({ type: `${agency}_ANNOUNCEMENT_COLLECTION`, status: 'FAILED', totalStepCount: 2,
    failure: { stepName: `${agency} 공고 정제`, message: '정제 실패', serverResponse: {
      failed: 1, failures: [{ sourceKey: `${agency}:100`, detail: '원천 확인 필요' }],
    } } }))
  fireEvent.click(screen.getByRole('button', { name: `${agency} 공고 정제 실패` }))
  expect(screen.getByRole('list', { name: '정제 실패 원천' })).toHaveTextContent('원천 확인 필요')
  expect(screen.getByRole('link', { name: `${agency}:100` })).toHaveAttribute('href',
    `/admin/sources?category=${agency}_ANNOUNCEMENT&keyword=${agency}%3A100`)
  expect(screen.queryByRole('link', { name: '실패 대상 확인' })).not.toBeInTheDocument()
  expect(screen.queryByRole('link', { name: '실패 내역 확인' })).not.toBeInTheDocument()
  expect(screen.getByText(new RegExp(`위의 ${agency} 공고 수집`))).toBeVisible()
})

it('성공한 GH 정제에는 실패 재실행 안내를 표시하지 않는다', () => {
  show(execution({ type: 'GH_ANNOUNCEMENT_COLLECTION', status: 'COMPLETED', totalStepCount: 2,
    completedSteps: ['GH 공고 원천 수집', 'GH 공고 정제'],
    completedStepResults: [{ step: 'MAP_GH_ANNOUNCEMENTS', stepName: 'GH 공고 정제', report: { created: 1, failed: 0, failures: [] } }],
  }))
  fireEvent.click(screen.getByRole('button', { name: 'GH 공고 정제 성공' }))
  expect(screen.queryByText(/실패한 원천을 확인한 뒤/)).not.toBeInTheDocument()
  expect(screen.queryByRole('list', { name: '정제 실패 원천' })).not.toBeInTheDocument()
})
