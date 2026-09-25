import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { DetailTable } from './DetailPrimitives'

afterEach(() => vi.restoreAllMocks())

function renderComparison() {
  return render(
    <DetailTable caption="임대조건 비교" minWidth={520}>
      <thead><tr><th scope="col">주택형</th><th scope="col">보증금</th></tr></thead>
      <tbody><tr><th scope="row">26A</th><td>2,880만원</td></tr></tbody>
    </DetailTable>,
  )
}

describe('DetailTable 가로 이동', () => {
  it('넘치는 표에도 안내 문구나 이동 버튼 없이 이름 있는 스크롤 영역을 제공한다', () => {
    vi.spyOn(Element.prototype, 'clientWidth', 'get').mockReturnValue(300)
    vi.spyOn(Element.prototype, 'scrollWidth', 'get').mockReturnValue(520)
    renderComparison()
    const viewport = screen.getByRole('region', { name: '임대조건 비교 표' })
    const table = screen.getByRole('table', { name: '임대조건 비교' })

    expect(viewport).toContainElement(table)
    expect(table).toHaveStyle({ minWidth: '520px' })
    expect(viewport).toHaveAttribute('tabindex', '0')
    expect(screen.queryByText('좌우로 이동해 비교하세요')).not.toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    viewport.focus()
    expect(viewport).toHaveFocus()
    viewport.scrollLeft = 220
    fireEvent.scroll(viewport)
    expect(viewport).toHaveFocus()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(screen.getByRole('cell', { name: '2,880만원' })).toBeVisible()
  })

  it('표가 화면에 모두 들어오면 불필요한 이동 도구를 숨긴다', () => {
    vi.spyOn(Element.prototype, 'clientWidth', 'get').mockReturnValue(600)
    vi.spyOn(Element.prototype, 'scrollWidth', 'get').mockReturnValue(600)
    renderComparison()
    expect(screen.getByRole('table', { name: '임대조건 비교' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /임대조건 비교.*이동/ })).not.toBeInTheDocument()
  })
})
