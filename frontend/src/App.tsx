import { Route, Routes } from 'react-router'

function Home() {
  return (
    <main>
      <h1>두꺼비집</h1>
      <p>프론트엔드 개발 환경이 준비되었습니다.</p>
    </main>
  )
}

function NotFound() {
  return (
    <main>
      <h1>페이지를 찾을 수 없습니다.</h1>
      <p>입력한 주소를 다시 확인해 주세요.</p>
    </main>
  )
}

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Home />} />
      <Route path="*" element={<NotFound />} />
    </Routes>
  )
}
