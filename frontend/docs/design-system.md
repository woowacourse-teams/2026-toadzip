# 프런트엔드 디자인 시스템

현재 공공주택 복덕방의 사용자용 공고·단지 검색 화면을 기준으로, 새 화면에 같은 토큰과 컴포넌트를 적용하기 위한 문서다.
UI 작업 전에 이 문서에서 기존 사용처를 찾고, 해당 코드와 가까운 테스트를 함께 읽는다.

## 원본과 적용 범위

| 대상 | 원본 |
|---|---|
| 색·글자·간격·컨트롤 크기 | [tokens.css](../src/design-system/tokens.css) |
| 주 행동 버튼 | [Button](../src/design-system/components/Button.tsx), [스타일](../src/design-system/components/Button.module.css), [테스트](../src/design-system/components/Button.test.tsx) |
| 아이콘 버튼 | [IconButton](../src/design-system/components/IconButton.tsx), [스타일](../src/design-system/components/IconButton.module.css), [테스트](../src/design-system/components/IconButton.test.tsx) |
| 앱·지도·목록 레이아웃 | [index.css](../src/index.css) |
| 기능별 UI와 업무 표시 규칙 | 아래 서비스 패턴의 코드·테스트 |

토큰은 `index.css`에서 한 번 로드한다. 실제 값을 문서나 별도 JSON에 복제하지 않는다.
컴포넌트별 CSS Modules에서 토큰을 사용하고, 화면 상태는 가장 가까운 기능 컴포넌트가 소유한다.
현재 버전은 같은 프런트엔드 저장소에서 관리하며 Storybook, Figma 동기화, 별도 견본 사이트는 포함하지 않는다.
관리자 페이지의 디자인은 이 시스템의 참고 기준이나 공통 UI 추출 대상에 포함하지 않는다.
관리자 화면의 스타일을 사용자용 화면에 가져오지 않는다. 지도 SDK 구현도 이번 추출 범위에서 제외한다.

## 토큰 선택

| 역할 | 사용할 토큰 |
|---|---|
| 밝은 브랜드 채움·그 위의 글자 | `--ds-color-brand`, `--ds-color-on-brand` |
| 링크·초점·작은 강조 | `--ds-color-action` |
| hover·선택한 목록 배경 | `--ds-color-action-soft`, `--ds-color-action-selected` |
| 본문·항목 이름·보조 글자 | `--ds-color-text`, `--ds-color-label`, `--ds-color-muted` |
| 표면·보조 표면·구분선 | `--ds-color-surface`, `--ds-color-surface-subtle`, `--ds-color-divider` |
| 접수 상태·기관·마감 표시 | `--ds-color-status-*`, `--ds-color-agency-*`, `--ds-color-deadline` |
| 목록·필터 글꼴과 제목·본문·설명·금액 | `--ds-font-family-ui`, `--ds-text-*`, `--ds-line-height-*` |
| 반복 간격·버튼 모서리·컨트롤 크기 | `--ds-space-*`, `--ds-radius-*`, `--ds-control-*`, `--ds-icon-*` |

현재 초록색 브랜드와 파란색 접수중 배지는 서로 다른 역할이다. 같은 색상값을 쓰는 경우에도
역할이 다른 토큰을 임의로 합치지 않는다. 기관색을 행동 버튼이나 접수 상태에 사용하지 않는다.
밝은 브랜드색을 작은 글자나 포커스에 직접 쓰지 않고 읽기 쉬운 action 색을 사용한다.

```css
.summary {
  padding: var(--ds-space-4);
  background: var(--ds-color-surface);
  color: var(--ds-color-text);
  font-family: var(--ds-font-family-ui);
  font-size: var(--ds-text-body);
  line-height: var(--ds-line-height-body);
}
```

기존 전역 `--brand`, `--focus`, `--surface`와 목록의 `--list-*`는 호환 별칭이다.
새 공통 표현에는 `--ds-*`를 사용하되, 목록 여백처럼 부모가 좁은 폭에 맞춰 바꾸는 값은
`--list-inset`을 계속 소비한다. 위치 계산, 이미지 비율, 한 화면에만 쓰는 치수까지 전부 토큰화하지 않는다.

## Button과 IconButton

`Button`은 주 행동용이다. `md`가 기본이며, 모바일 하단의 주 행동에는 `lg`를 사용한다.
기본 `type`은 `button`이고 폼을 제출할 때만 `submit`을 명시한다.
disabled, ref, aria 속성과 native button 이벤트를 전달할 수 있다. async 요청·로딩·라우팅은 호출자가 처리한다.

```tsx
// frontend/src 아래 화면에서의 import 예시
import { Button } from '../design-system/components/Button'
import { IconButton } from '../design-system/components/IconButton'

<Button type="submit">조건 적용</Button>
<Button size="lg" disabled={isSubmitting} type="submit">결과 보기</Button>
<IconButton label="필터 닫기" onClick={closeFilter}>
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor"
    strokeWidth="1.8" aria-hidden="true" focusable="false">
    <path d="m5 5 14 14M19 5 5 19" />
  </svg>
</IconButton>
```

`IconButton`의 `label`에는 화면에서 수행할 동작을 넣는다. 아이콘은 장식 처리한다.
`md`는 데스크톱 필터 닫기, `lg`는 모바일 필터 닫기에 사용한다.
닫은 뒤 포커스를 돌릴 위치는 필터가 관리하며 `ref`를 실제 버튼까지 전달한다.
각 컴포넌트는 기본·hover·focus-visible·disabled 표현을 제공한다.

폭·배치는 호출자의 `className`으로 지정한다. 색·모서리·포커스 규칙을 사용처에서 덮어쓰지 않는다.
내비게이션은 실제 링크를 사용하고, 링크를 버튼으로 위장하거나 버튼 안에 또 버튼을 넣지 않는다.
텍스트 닫기 버튼, 보조 동작, 탭, 상태 배지는 주 행동 Button으로 일괄 교체하지 않는다.

실제 적용 예시는 [공고 필터](../src/public-housing/filters/SearchFilterPanel.tsx)와
[단지 필터](../src/public-housing/filters/ComplexFilterToolbar.tsx)다.

## 서비스 패턴

| 필요 | 재사용할 구현과 확인할 계약 |
|---|---|
| 단지 목록 | [HousingComplexCard](../src/public-housing/components/HousingComplexCard.tsx): 단지 선택과 대표 공고 열기는 독립 버튼. 이미지 실패·금액 0·긴 이름을 보존 |
| 공고 목록 | [HousingAnnouncementCard](../src/public-housing/components/HousingAnnouncementCard.tsx): 상태/기관, 제목, 지역/유형, 기간, 공급/조회 순서 |
| 접수 상태 표시 | [AnnouncementStatusBadge](../src/public-housing/components/AnnouncementStatusBadge.tsx): label·tone·countdown을 전달. 상태 판단은 호출자에서 수행 |
| 상세 구역·항목/값·표·닫기 | [DetailPrimitives](../src/public-housing/components/DetailPrimitives.tsx): 기존 heading·dl·table·키보드 계약 유지 |
| 단지·공고 상세 | [HousingComplexDetailPanel](../src/public-housing/components/HousingComplexDetailPanel.tsx), [HousingAnnouncementDetailPanel](../src/public-housing/components/HousingAnnouncementDetailPanel.tsx): 제목 포커스, Escape, 내부 스크롤과 원문 이동 |
| 통합 검색 | [IntegratedSearch](../src/public-housing/search/IntegratedSearch.tsx): 지역·단지·공고 결과, 로딩·빈 결과·부분 실패 구분 |
| 필터 | [ComplexFilterToolbar](../src/public-housing/filters/ComplexFilterToolbar.tsx), [SearchFilterPanel](../src/public-housing/filters/SearchFilterPanel.tsx): 즉시 적용과 명시적 적용의 현재 구분, 초기화, 닫기·포커스 복귀 |
| 금액·면적 범위 | [DualRangeFilter](../src/public-housing/filters/DualRangeFilter.tsx): 두 범위 입력, 키보드, 터치 영역, forced-colors 유지 |

각 기능의 행동 테스트는 같은 디렉터리의 `*.test.tsx`다. 기본 컴포넌트가 API 요청·모집 상태 판단·
금액 계산·URL 상태·지도 SDK를 소유하지 않도록 한다. 위 서비스 패턴은 기능 폴더에 유지한다.

금액은 [housingMoney](../src/public-housing/presentation/housingMoney.ts), 누락 표시는
[missingData](../src/public-housing/presentation/missingData.ts), 상세 데이터 변환은
[complexDetailPresentation](../src/public-housing/presentation/complexDetailPresentation.ts)와
[announcementDetailPresentation](../src/public-housing/presentation/announcementDetailPresentation.ts)를 따른다.
0과 false를 누락으로 바꾸지 않고, 기존 단위·날짜·마감 계산을 시각 컴포넌트에서 다시 구현하지 않는다.
미확인 자격이나 공급 규모를 추정하지 않으며 실제 공식 출처와 기준 시점을 유지한다.

## 반응형과 접근성

- 기준은 브라우저 전체 폭과 패널 폭을 구분한다. 지도 옆 패널은 데스크톱에서도 좁아질 수 있다.
- 목록 컨테이너는 기존 400/340px 여백 규칙과 공고 카드 380px 규칙을 보존한다.
- 필터는 767px 이하의 모바일 시트·패널 동작을 유지한다. 화면 회전과 낮은 높이에서도 적용 버튼에 도달해야 한다.
- 상세는 기존 420/330/270px container 규칙을 각 컴포넌트에서 확인한다. media/container 조건에 CSS 변수를 넣지 않는다.
- 목록은 hover·selected·focus를 구분하며 상태를 색만으로 전달하지 않는다. 긴 제목은 전체 접근성 이름을 유지한다.
- Tab·Shift+Tab·Enter·Space·Escape와 닫은 후 포커스 복귀를 실제 브라우저에서 확인한다.
- reduced-motion·forced-colors 규칙과 원래 지도/필터/상세 레이어 순서를 유지한다.
- 로딩·빈 결과·오류·재시도를 구분한다. 오류를 빈 성공으로 표시하지 않는다.

## 새 화면과 변경 절차

1. 기존 서비스 패턴과 공통 UI를 찾고 코드·테스트를 읽는다.
2. 기존 토큰과 컴포넌트로 화면을 조합한다. 화면 레이아웃과 업무 상태는 해당 기능에 둔다.
3. 표현이 부족하면 같은 역할의 기존 변형으로 해결 가능한지 먼저 확인한다.
4. 새 변형은 실제 사용처를 제시한다. 별도 공통 컴포넌트는 두 번째 실제 사용처에서 추출을 검토한다.
5. 토큰 역할이나 컴포넌트 계약을 바꾸면 이 문서와 가까운 행동 테스트도 같은 변경에서 갱신한다.
6. [품질 게이트](quality-gates.md)에 따라 검사와 실제 화면을 확인하고 의도된 시각 변화를 PR에 기록한다.

토큰·코드는 실행되는 기준이며 이 문서는 선택 이유와 사용법의 기준이다. 둘이 어긋나면 실제
사용처를 조사해 함께 갱신한다. 특정 에이전트의 개인 스킬·계획 파일·이전 대화에 의존하지 않는다.
AGENTS를 읽지 않는 도구에는 해당 도구의 저장소 진입 지침에서 이 문서를 연결한다.

## 보존한 차이와 현재 범위

- 전역 폰트와 목록·필터의 UI 폰트, 지도 마커 폰트의 기존 적용 범위를 유지한다.
- DetailCloseButton은 상세용 아이콘 크기·여백·포커스를 갖는다. 필터 IconButton과 역할을 구분한다.
- 오류·문서·이미지 등 기능별로만 사용되는 값은 가까운 CSS에 남는다. 새 공통 역할이 생길 때 추출한다.
- 모든 버튼을 같은 모양으로 바꾸거나 모든 값을 토큰으로 만드는 것을 완료 기준으로 삼지 않는다.
- 공통 적용 버튼의 좌우 여백은 기존 공고 필터 기준으로 맞췄다. 모바일 아이콘 버튼에도 공통 hover·focus·disabled 규칙을 제공한다.
