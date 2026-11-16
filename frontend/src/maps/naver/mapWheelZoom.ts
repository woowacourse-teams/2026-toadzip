// NAVER GL 지도는 내부 Mapbox의 기본 휠 배율을 그대로 써서, 마우스 휠 한 칸에 약 0.18단계만 확대된다.
// 한 단계를 넘기려면 5~6칸을 굴려야 해 줌이 느리게 느껴지므로, 한 칸에 약 0.55단계가 되도록 휠 배율만 올린다.
// 트랙패드 배율은 그대로 둔다. getMapbox()는 공개 API가 아니므로 모양이 다르면 아무것도 하지 않는다.
export const WHEEL_ZOOM_RATE = 1 / 120

type ScrollZoomLike = { setWheelZoomRate: (rate: number) => void }

export function speedUpWheelZoom(map: naver.maps.Map): void {
  try {
    const mapbox: unknown = (map as unknown as { getMapbox?: () => unknown }).getMapbox?.()
    const scrollZoom: unknown = typeof mapbox === 'object' && mapbox !== null
      ? Reflect.get(mapbox, 'scrollZoom')
      : undefined
    if (isScrollZoom(scrollZoom)) scrollZoom.setWheelZoomRate(WHEEL_ZOOM_RATE)
  } catch {
    // 내부 구조가 바뀌어도 지도는 기본 휠 배율로 그대로 동작해야 한다.
  }
}

function isScrollZoom(value: unknown): value is ScrollZoomLike {
  return typeof value === 'object' && value !== null
    && typeof Reflect.get(value, 'setWheelZoomRate') === 'function'
}
