import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import { BrandLink } from '../BrandLink'
import styles from './LandingPage.module.css'

const steps = [
  {
    id: 'find', number: '01', title: '살고 싶은 동네부터', navigation: '동네 찾기',
    description: '지역을 검색하고, 지도 위에서 공공주택의 위치를 살펴보세요.',
    image: 'explore@2x.jpg', alt: '복덕방 지도에서 서울 지역 공공주택의 위치와 보증금, 월 임대료를 확인하는 실제 화면',
    crop: [50, 0, 700, 393.75], mobileCrop: [58, 8, 460, 345], focus: [67, 20, 379, 52],
  },
  {
    id: 'compare', number: '02', title: '내 생활에 맞는 조건으로', navigation: '조건 살펴보기',
    description: '보증금과 월세, 임대유형을 골라 나에게 맞는 집을 좁혀보세요.',
    image: 'filters@2x.jpg', alt: '복덕방에서 임대보증금과 월 임대료 범위를 선택하는 가격 필터 실제 화면',
    crop: [470, 64, 810, 455.625], mobileCrop: [832, 72, 438, 444], focus: [856, 151, 389, 345],
  },
  {
    id: 'check', number: '03', title: '모집 공고까지 한 번에', navigation: '공고 확인하기',
    description: '신청 일정과 공급 조건을 확인하고, 공식 공고문으로 자세히 살펴보세요.',
    image: 'announcement@2x.jpg', alt: '복덕방에서 모집 공고의 신청 일정과 공급 정보를 확인하는 실제 화면',
    crop: [465, 57, 720, 405], mobileCrop: [460, 65, 520, 390], focus: [496, 403, 324, 42],
  },
  {
    id: 'alerts', number: '04', title: '알림 켜고, 소식 받아보기', navigation: '알림 설정하기',
    description: '관심 있는 단지·공고에서 ‘알림 받기’를 눌러, 새 소식을 받아볼 준비를 해두세요.',
    image: 'notifications@2x.jpg', alt: '복덕방 단지 상세에서 알림 받기 버튼을 확인하는 실제 화면',
    crop: [65, 145, 640, 360], mobileCrop: [65, 154, 440, 330], focus: [296, 290, 113, 45],
  },
] as const

function ServiceScreen({ step, mobile = false, active = true }: {
  readonly step: (typeof steps)[number]
  readonly mobile?: boolean
  readonly active?: boolean
}) {
  const [x, y, width, height] = mobile ? step.mobileCrop : step.crop
  const [focusX, focusY, focusWidth, focusHeight] = step.focus
  return (
    <figure className={mobile ? styles.mobileScreen : styles.serviceScreen} data-active={active || undefined}
      aria-hidden={mobile ? undefined : true}>
      <div className={styles.screenViewport} style={{ aspectRatio: `${width} / ${height}` }}>
        <img src={`/landing/${step.image}`} alt={mobile ? step.alt : ''} width="1280" height="720" loading="lazy"
          style={{ width: `${1280 / width * 100}%`, left: `${-x / width * 100}%`, top: `${-y / height * 100}%` }} />
        <span className={styles.screenFocus} aria-hidden="true" style={{
          left: `${(focusX - x) / width * 100}%`, top: `${(focusY - y) / height * 100}%`,
          width: `${focusWidth / width * 100}%`, height: `${focusHeight / height * 100}%`,
        }} />
      </div>
      <figcaption className={styles.screenAttribution}><span>실제 서비스 화면 · 확대</span><span>© NAVER Corp.</span></figcaption>
    </figure>
  )
}

function StartLink({ compact = false }: { readonly compact?: boolean }) {
  return <Link to="/explore" className={`${styles.start} ${compact ? styles.compact : ''}`}>
    시작하기<span aria-hidden="true">↗</span>
  </Link>
}

export function LandingPage() {
  const rootRef = useRef<HTMLDivElement>(null)
  const [activeStep, setActiveStep] = useState(0)

  useEffect(() => {
    const root = rootRef.current
    if (!root || typeof IntersectionObserver === 'undefined') return
    const reveal = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue
        entry.target.setAttribute('data-visible', 'true')
        reveal.unobserve(entry.target)
      }
    }, { threshold: 0.12 })
    root.querySelectorAll('[data-reveal]').forEach((element) => reveal.observe(element))
    root.setAttribute('data-motion-ready', 'true')

    const chapters = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting) setActiveStep(Number(entry.target.getAttribute('data-step')))
      }
    }, { rootMargin: '-25% 0px -45% 0px', threshold: 0 })
    root.querySelectorAll('[data-step]').forEach((element) => chapters.observe(element))
    return () => { reveal.disconnect(); chapters.disconnect(); root.removeAttribute('data-motion-ready') }
  }, [])

  return (
    <div className={styles.page} ref={rootRef}>
      <a className={styles.skipLink} href="#landing-content">본문 바로가기</a>
      <header className={styles.header}>
        <BrandLink showName className={styles.brand} />
        <nav aria-label="랜딩페이지 메뉴" className={styles.navigation}>
          <a href="#about">서비스 소개</a>
          <a href="#how-it-works">사용법</a>
          <StartLink compact />
        </nav>
      </header>

      <main id="landing-content">
        <section className={styles.hero} id="about" aria-labelledby="landing-title">
          <svg className={styles.heroMap} viewBox="0 0 1000 760" fill="none" aria-hidden="true" focusable="false">
            <g className={styles.mapBlocks}>
              <path d="M120 85h135v110H120z M300 85h170v110H300z M515 85h175v110H515z M735 85h155v110H735z" />
              <path d="M145 240h110v130H145z M300 240h170v130H300z M515 240h125v130H515z M690 240h200v130H690z" />
              <path d="M70 415h185v140H70z M300 415h170v140H300z M515 415h175v140H515z M735 415h155v140H735z" />
              <path d="M120 600h135v110H120z M300 600h170v110H300z M515 600h175v110H515z M735 600h155v110H735z" />
            </g>
            <g className={styles.mapRoads}>
              <path d="M0 217h940 M0 393h1000 M50 577h950 M277 0v760 M492 0v760 M712 0v760" />
              <path d="M70 0c40 150 18 238 112 330s125 173 150 260 126 125 190 170" />
              <path d="M1000 118C880 140 850 245 760 282S611 320 583 451s-76 174-131 309" />
            </g>
          </svg>
          <div className={styles.heroCopy}>
            <p className={styles.eyebrow}>나에게 맞는 공공주택을 찾는 첫걸음</p>
            <h1 id="landing-title">
              <span className={styles.heroLine}><span>공공주택 찾기,</span></span>
              <span className={styles.heroLine}><span className={styles.heroAccent}>이제 한곳에서.</span></span>
            </h1>
            <p className={styles.description}>살고 싶은 동네의 공공주택,<br />보증금·월세부터 모집 공고까지 연결해 드려요.</p>
            <StartLink />
          </div>
          <div className={styles.heroVisual}>
            <div className={styles.heroOrbit} aria-hidden="true" />
            <figure className={styles.heroScreen}>
              <img src="/landing/explore.jpg" alt={steps[0].alt}
                width="1280" height="720" fetchPriority="high" />
              <figcaption>지도 위에서 만나는 우리 동네 공공주택</figcaption>
            </figure>
          </div>
          <a className={styles.scrollCue} href="#how-it-works" aria-label="사용법으로 내려가기">
            <span className={styles.scrollArrow} aria-hidden="true"><span /><span /></span>
          </a>
        </section>

        <section className={styles.guide} id="how-it-works" aria-label="복덕방 사용법">
          <div className={styles.guideHeading} data-reveal>
            <p>흩어진 정보를 찾아다니던 시간은 줄이고</p>
            <h2>내가 살 집에<br className={styles.mobileBreak} /> 조금 더 가까이.</h2>
          </div>
          <div className={styles.walkthrough}>
            <ol className={styles.steps} role="list">
              {steps.map((step, index) => (
                <li key={step.id} id={step.id} data-step={index}
                  className={styles.step} data-active={index === activeStep || undefined}>
                  <div className={styles.stepCopy} data-reveal>
                    <span className={styles.stepNumber} aria-label={`${index + 1}단계`}>{step.number}</span>
                    <h3>{step.title}</h3>
                    <p>{step.description}</p>
                    {step.id === 'alerts' && <p className={styles.stepNotice}>로그인 후 설정할 수 있어요.</p>}
                  </div>
                  <ServiceScreen step={step} mobile />
                </li>
              ))}
            </ol>
            <div className={styles.stickyColumn}>
              <div className={styles.stickyPreview}>
                <div className={styles.screenStack}>
                  {steps.map((step, index) => (
                    <ServiceScreen key={step.id} step={step} active={index === activeStep} />
                  ))}
                </div>
                <nav className={styles.stepNavigation} aria-label="사용 순서">
                  {steps.map((step, index) => (
                    <a key={step.id} href={`#${step.id}`} aria-current={index === activeStep ? 'step' : undefined}
                      onClick={() => setActiveStep(index)}>
                      <span>{step.number}</span>{step.navigation}
                    </a>
                  ))}
                </nav>
              </div>
            </div>
          </div>
          <p className={styles.screenNote}>실제 서비스 화면의 일부를 확대했어요. 모집 정보는 조회 시점에 따라 달라질 수 있어요.</p>
        </section>

        <section className={styles.closing} aria-labelledby="start-title">
          <div data-reveal>
            <img src="/logo-bok-search.svg" alt="" width="76" height="76" />
            <h2 id="start-title">살고 싶은 동네에,<br />기다리고 있던 내 집.</h2>
            <p>지금 복덕방에서 찾아보세요.</p>
            <StartLink />
          </div>
        </section>
      </main>
      <footer className={styles.footer}>
        <BrandLink showName className={styles.brand} />
        <p>공공주택을 찾는 당신 곁에, 복덕방</p>
        <a href="#about">맨 위로 ↑</a>
      </footer>
    </div>
  )
}
