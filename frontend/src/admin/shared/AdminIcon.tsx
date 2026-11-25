// 관리자 화면 아이콘은 이 세트 하나만 쓴다.
// 20px 격자, 선 1.5, 둥근 끝, 안쪽 여백 3px(내용은 3~17 안)으로 그려 크기·굵기·모서리가 서로 맞는다.
// 화살표·꺾쇠는 같은 각도(45°)와 같은 팔 길이(4.5)를 쓰고, 모서리는 반지름 1.5로 통일한다.
const icons = {
  // 방향
  chevronDown: 'M5.5 8 10 12.5 14.5 8', // admin-system.css의 선택 상자 화살표도 같은 경로를 쓴다.
  chevronUp: 'M5.5 12 10 7.5l4.5 4.5',
  chevronLeft: 'M12 5.5 7.5 10l4.5 4.5',
  chevronRight: 'M8 5.5l4.5 4.5L8 14.5',
  arrowRight: 'M4 10h12M11.5 5.5 16 10l-4.5 4.5',
  arrowLeft: 'M16 10H4M8.5 5.5 4 10l4.5 4.5',
  external: 'M8.5 4.5h-3A1.5 1.5 0 0 0 4 6v8.5A1.5 1.5 0 0 0 5.5 16H14a1.5 1.5 0 0 0 1.5-1.5v-3M11.5 4H16v4.5M16 4l-6.5 6.5',
  // 동작
  close: 'M5.5 5.5l9 9M14.5 5.5l-9 9',
  check: 'M4.5 10.5 8 14l7.5-8',
  plus: 'M10 4.5v11M4.5 10h11',
  search: 'M9 3.5a5.5 5.5 0 1 1 0 11 5.5 5.5 0 0 1 0-11zM13 13l3.5 3.5',
  filter: 'M3.5 6.5h3M10.5 6.5h6M3.5 13.5h6M13.5 13.5h3M8.5 5a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3zM11.5 12a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3z',
  refresh: 'M16 10a6 6 0 1 1-1.76-4.24M16 3.5v3h-3',
  menu: 'M3.5 5.5h13M3.5 10h13M3.5 14.5h13',
  // 상태
  clock: 'M10 3.5a6.5 6.5 0 1 1 0 13 6.5 6.5 0 0 1 0-13zM10 6.5V10l2.5 1.5',
  progress: 'M10 3.5a6.5 6.5 0 1 1-6.5 6.5',
  dash: 'M6 10h8',
  square: 'M7 5.5h6A1.5 1.5 0 0 1 14.5 7v6a1.5 1.5 0 0 1-1.5 1.5H7A1.5 1.5 0 0 1 5.5 13V7A1.5 1.5 0 0 1 7 5.5z',
  info: 'M10 3.5a6.5 6.5 0 1 1 0 13 6.5 6.5 0 0 1 0-13zM10 9v4.5M10 6.5v.01',
  alert: 'M8.7 4.25a1.5 1.5 0 0 1 2.6 0l5.7 10a1.5 1.5 0 0 1-1.3 2.25H4.3A1.5 1.5 0 0 1 3 14.25zM10 8.5v3M10 14v.01',
  sidebarClose: 'M4.5 4h11A1.5 1.5 0 0 1 17 5.5v9a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 3 14.5v-9A1.5 1.5 0 0 1 4.5 4zM7.5 4v12M13 8l-2 2 2 2',
  sidebarOpen: 'M4.5 4h11A1.5 1.5 0 0 1 17 5.5v9a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 3 14.5v-9A1.5 1.5 0 0 1 4.5 4zM7.5 4v12M11 8l2 2-2 2',
  // 메뉴
  home: 'M3.5 9 10 3.5 16.5 9M5 8v8.5h3.5v-4h3v4H15V8',
  building: 'M3 16.5h14M4.5 16.5v-12A1 1 0 0 1 5.5 3.5h6a1 1 0 0 1 1 1v12M12.5 8.5h3a1 1 0 0 1 1 1v7M7 6.5h3M7 9.5h3M7 12.5h3',
  document: 'M11.5 3.5h-5A1.5 1.5 0 0 0 5 5v10a1.5 1.5 0 0 0 1.5 1.5h7A1.5 1.5 0 0 0 15 15V7zM11.5 3.5V7H15M7.5 10.5h5M7.5 13.5h3',
  clipboard: 'M7.5 3.5h5v2.5h-5zM7.5 4.75H6A1.5 1.5 0 0 0 4.5 6.25v8.75A1.5 1.5 0 0 0 6 16.5h8a1.5 1.5 0 0 0 1.5-1.5V6.25A1.5 1.5 0 0 0 14 4.75h-1.5M7.5 11l2 2 3.5-3.5',
  download: 'M10 3.5v9M6.5 9 10 12.5 13.5 9M4 13.5v1.5A1.5 1.5 0 0 0 5.5 16.5h9a1.5 1.5 0 0 0 1.5-1.5v-1.5',
  layers: 'M10 3.5 17 7.25 10 11 3 7.25zM3 11 10 14.75 17 11',
  database: 'M4 5.5c0-1.1 2.7-2 6-2s6 .9 6 2-2.7 2-6 2-6-.9-6-2zM4 5.5v9c0 1.1 2.7 2 6 2s6-.9 6-2v-9M4 10c0 1.1 2.7 2 6 2s6-.9 6-2',
  pin: 'M10 16.5s5.5-4.75 5.5-9a5.5 5.5 0 0 0-11 0c0 4.25 5.5 9 5.5 9zM10 5.75a1.75 1.75 0 1 1 0 3.5 1.75 1.75 0 0 1 0-3.5z',
  user: 'M10 3.5a3 3 0 1 1 0 6 3 3 0 0 1 0-6zM4 16.5a6 6 0 0 1 12 0',
  bell: 'M5 13.5V9a5 5 0 0 1 10 0v4.5l1.5 2h-13zM8.5 17.5h3',
  send: 'M16.5 3.5 3.5 8.75l5.5 2.25 2.25 5.5zM16.5 3.5 9 11',
  message: 'M5 3.5h10A1.5 1.5 0 0 1 16.5 5v7A1.5 1.5 0 0 1 15 13.5H8.5L5 16.5v-3A1.5 1.5 0 0 1 3.5 12V5A1.5 1.5 0 0 1 5 3.5zM7 7.5h6M7 10h4',
} as const

export type AdminIconName = keyof typeof icons | 'stop'

// size를 주지 않으면 글자 크기(1em)를 따른다. 버튼·메뉴처럼 자리가 정해진 곳은 size나 CSS로 정한다.
// label을 주면 장식이 아니라 뜻이 있는 그림(role=img)으로 읽힌다.
export function AdminIcon({ name, size, className, label }: { name: AdminIconName; size?: number; className?: string; label?: string }) {
  const dimension = size ?? '1em'
  const classes = className ? `admin-icon ${className}` : 'admin-icon'
  const a11y = label ? { role: 'img', 'aria-label': label } : { 'aria-hidden': true }
  if (name === 'stop') {
    return <svg className={classes} width={dimension} height={dimension} viewBox="0 0 20 20" fill="currentColor" {...a11y} focusable="false"><rect x="5.5" y="5.5" width="9" height="9" rx="1.5" /></svg>
  }
  return <svg className={classes} width={dimension} height={dimension} viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" {...a11y} focusable="false"><path d={icons[name]} /></svg>
}

// 새 창으로 여는 링크 끝에 둔다. 아이콘 이름으로 보조기기에 '(새 창)'을 읽힌다.
// 숨김 글자(position:absolute)를 쓰지 않는다: 표 스크롤 상자 밖을 기준으로 놓여 문서를 표 폭만큼 넓혔다.
export function NewWindowMark() {
  return <AdminIcon name="external" label="(새 창)" />
}
