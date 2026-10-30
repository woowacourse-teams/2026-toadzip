import type { ReactNode } from 'react'
import styles from './TechnologySection.module.css'

const highlights = [
  { value: '3.9배', label: 'LH 공고 수집 처리 속도', detail: '160.7초 → 41.1초' },
  { value: '49%', label: '원천 교체 SQL 감소', detail: '100행 기준 202회 → 102회' },
  { value: '257개', label: '전국 단지를 묶는 지역 그룹', detail: '권역 11 · 시·도 16 · 시·군·구 230' },
  { value: '35%', label: '개별 단지 집계 쿼리 단축', detail: '평균 7.76ms → 5.07ms' },
] as const

const stages = [
  { stage: '1단계', name: '권역', count: '11개' },
  { stage: '2단계', name: '시·도', count: '16개' },
  { stage: '3단계', name: '시·군·구', count: '230개' },
  { stage: '4단계', name: '개별 단지', count: '화면 안' },
] as const

const pipeline = [
  { phase: '수집', steps: ['마이홈 공고', 'LH 목록', 'LH 공급', 'LH 상세'] },
  { phase: '정제', steps: ['공고·공급행 저장', 'LH 공고 보강'] },
] as const

const stack = [
  'Java 25', 'Spring Boot 4', 'Spring Data JPA', 'PostgreSQL 17', 'Flyway', 'Testcontainers',
  'React 19', 'NAVER Maps', 'Prometheus', 'Grafana',
] as const

function Comparison({ label, unit, before, after }: {
  readonly label: string
  readonly unit: string
  readonly before: { readonly name: string, readonly value: number }
  readonly after: { readonly name: string, readonly value: number }
}) {
  return (
    <figure className={styles.comparison} aria-label={`${label}: ${before.name} ${before.value}${unit}, ${after.name} ${after.value}${unit}`}>
      <figcaption>{label}</figcaption>
      {[before, after].map((bar, index) => (
        <div key={bar.name} className={styles.bar} data-after={index === 1 || undefined} aria-hidden="true">
          <span className={styles.barName}>{bar.name}</span>
          <span className={styles.barTrack}>
            <span className={styles.barFill} style={{ width: `${bar.value / before.value * 100}%` }} />
          </span>
          <span className={styles.barValue}>{bar.value}{unit}</span>
        </div>
      ))}
    </figure>
  )
}

function CaseStudy({ number, tag, title, visual }: {
  readonly number: string
  readonly tag: string
  readonly title: string
  readonly visual: ReactNode
}) {
  return (
    <article className={styles.case} data-reveal>
      <p className={styles.caseTag}><span>{number}</span>{tag}</p>
      <h3>{title}</h3>
      <div className={styles.caseVisual}>{visual}</div>
    </article>
  )
}

export function TechnologySection() {
  return (
    <section className={styles.technology} id="technology" aria-labelledby="technology-title">
      <div className={styles.heading} data-reveal>
        <p className={styles.eyebrow}>기술 소개</p>
        <h2 id="technology-title">흩어진 공공주택 정보를<br />정확하고 빠르게 잇는 기술</h2>
        <p className={styles.lede}>
          마이홈·LH·SH 공공 API의 데이터를 모아 정제하고, 전국 단지를 지도 위에 묶어 그려요.
          스프린트마다 직접 측정하고 개선한 과정을 소개해요.
        </p>
      </div>

      <ul className={styles.highlights} role="list" aria-label="핵심 성과">
        {highlights.map((highlight) => (
          <li key={highlight.label} data-reveal>
            <strong>{highlight.value}</strong>
            <span>{highlight.label}</span>
            <small>{highlight.detail}</small>
          </li>
        ))}
      </ul>

      <div className={styles.cases}>
        <CaseStudy
          number="01" tag="성능 · 데이터 수집" title="LH 공고 수집, 160초에서 41초로"
          visual={<Comparison label="LH 공급·상세 수집 시간" unit="초"
            before={{ name: '동시 2개', value: 160.7 }} after={{ name: '동시 8개', value: 41.1 }} />}
        />

        <CaseStudy
          number="02" tag="장애 대비 · 회복력" title="외부 API가 멈춰도 수집이 무너지지 않게"
          visual={
            <figure className={styles.circuit} aria-label="서킷 브레이커 상태 전이: 정상에서 연속 5회 실패하면 30초 차단, 이후 1건으로 복구를 확인해 성공하면 정상, 실패하면 다시 차단">
              <figcaption>LH 서킷 브레이커</figcaption>
              <ol role="list" aria-hidden="true">
                <li data-state="closed"><strong>정상</strong><span>요청 통과</span></li>
                <li className={styles.transition}>연속 5회 실패</li>
                <li data-state="open"><strong>차단</strong><span>30초간 호출 거절</span></li>
                <li className={styles.transition}>30초 경과</li>
                <li data-state="half"><strong>복구 확인</strong><span>1건만 시도</span></li>
              </ol>
              <p aria-hidden="true">성공하면 정상으로, 실패하면 다시 차단으로</p>
            </figure>
          }
        />

        <CaseStudy
          number="03" tag="정제 · 데이터 무결성" title="원천은 보존하고, 정제는 언제든 다시"
          visual={
            <figure className={styles.pipeline} aria-label={`수집·정제 파이프라인: ${pipeline.map((group) => `${group.phase} ${group.steps.join(', ')}`).join(' 다음 ')}`}>
              <figcaption>수집 · 정제 파이프라인</figcaption>
              {pipeline.map((group) => (
                <div key={group.phase} className={styles.pipelinePhase} data-phase={group.phase} aria-hidden="true">
                  <strong>{group.phase}</strong>
                  <ol role="list">{group.steps.map((step) => <li key={step}>{step}</li>)}</ol>
                </div>
              ))}
              <p aria-hidden="true">원천 테이블에 보관 → 정제는 외부 호출 없이 재실행</p>
            </figure>
          }
        />

        <CaseStudy
          number="04" tag="지도 · 서버 클러스터링" title="지도 클러스터링"
          visual={
            <div className={styles.mapVisual}>
              <ol className={styles.stages} role="list" aria-label="지도 확대 수준별 표시 단계">
                {stages.map((stage) => (
                  <li key={stage.stage}><span>{stage.stage}</span><strong>{stage.name}</strong><small>{stage.count}</small></li>
                ))}
              </ol>
              <Comparison label="개별 단지 집계 쿼리 실행 시간" unit="ms"
                before={{ name: '기존', value: 7.76 }} after={{ name: '개선', value: 5.07 }} />
            </div>
          }
        />
      </div>

      <div className={styles.stack} data-reveal>
        <h3>사용 기술</h3>
        <ul role="list">{stack.map((name) => <li key={name}>{name}</li>)}</ul>
      </div>

      <p className={styles.note}>
        수집·정제 수치는 실제 API와 격리 PostgreSQL에서 같은 작업을 한 번씩 비교한 값이고, 지도 수치는 dev DB 실행계획 3회 평균이에요.
        운영 환경의 성능을 보장하는 값은 아니에요.
      </p>
    </section>
  )
}
