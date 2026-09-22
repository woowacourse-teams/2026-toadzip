import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { RegionBoundaryControl } from './RegionBoundaryControl.tsx'

function props() {
  return {
    name: '경기도 수원시 장안구',
    status: 'ready' as const,
    supported: true,
    canRecenter: true,
    onRecenter: vi.fn(),
    onClear: vi.fn(),
    onRetry: vi.fn(),
  }
}

describe('RegionBoundaryControl', () => {
  it('짧은 버튼과 닫기 기호에도 접근 가능한 이름과 독립 동작을 유지한다', () => {
    const callbacks = props()
    render(<RegionBoundaryControl {...callbacks} />)
    const recenter = screen.getByRole('button', { name: '지역 다시 보기' })
    const clear = screen.getByRole('button', { name: '표시 해제' })
    expect(recenter).toHaveTextContent('다시 보기')
    expect(clear).toHaveTextContent('×')
    recenter.focus()
    expect(recenter).toHaveFocus()
    fireEvent.click(recenter)
    expect(callbacks.onRecenter).toHaveBeenCalledOnce()
    expect(callbacks.onClear).not.toHaveBeenCalled()
    fireEvent.click(clear)
    expect(callbacks.onClear).toHaveBeenCalledOnce()
  })

  it('긴 지역명의 전체 텍스트와 출처·라이선스 링크를 제공한다', () => {
    const name = '제주특별자치도 서귀포시 아주 긴 지역 이름'
    render(<RegionBoundaryControl {...props()} name={name} />)
    expect(screen.getByText(`검색 지역: ${name}`)).toHaveAttribute('title', `검색 지역: ${name}`)
    expect(screen.getByRole('link', { name: '국토교통부 · VWorld' })).toHaveAttribute('href', 'https://www.vworld.kr/dtmk/dtmk_ntads_s002.do?dsId=21')
    expect(screen.getByRole('link', { name: 'CC BY 2.0 KR' })).toHaveAttribute('href', 'https://creativecommons.org/licenses/by/2.0/kr/')
  })

  it('실패 안내의 재시도는 지역 다시 보기와 별도로 동작한다', () => {
    const callbacks = props()
    render(<RegionBoundaryControl {...callbacks} status="error" />)
    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent('지역 경계를 불러오지 못했습니다.')
    fireEvent.click(within(alert).getByRole('button', { name: '경계 다시 시도' }))
    expect(callbacks.onRetry).toHaveBeenCalledOnce()
    expect(callbacks.onRecenter).not.toHaveBeenCalled()
  })

  it('로딩과 미지원 안내를 유지하고 좌표 없는 미지원 지역의 다시 보기는 비활성화한다', () => {
    const callbacks = props()
    const { rerender } = render(<RegionBoundaryControl {...callbacks} status="loading" />)
    expect(screen.getByRole('status')).toHaveTextContent('지역 경계를 불러오는 중입니다.')
    rerender(<RegionBoundaryControl {...callbacks} supported={false} canRecenter={false} status="idle" />)
    expect(screen.getByRole('status')).toHaveTextContent('이 지역은 경계 정보를 제공하지 않습니다.')
    expect(screen.getByRole('button', { name: '지역 다시 보기' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '표시 해제' })).toBeEnabled()
  })
})
