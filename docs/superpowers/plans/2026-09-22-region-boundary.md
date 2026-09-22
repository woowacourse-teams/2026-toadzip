# Region Boundary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Track completed tasks below.

**Goal:** 지역 검색 시 정확한 시군구 경계와 지역 단위 자동 줌을 제공한다.
**Architecture:** 동기 bbox 인덱스로 카메라를 제어하고 비동기 GeoJSON은 오버레이만 갱신한다. 지역별 정적 파일은 Nginx/브라우저 HTTP 캐시와 제한된 메모리 캐시로 제공한다.
**Tech Stack:** React, TypeScript, NAVER Maps SDK, Vitest, Nginx, 오프라인 SHP 변환.
**Spec:** docs/superpowers/specs/2026-09-22-region-boundary.md

## Global Constraints

- feat/181-region-boundary에서 변경하고 사용자 미추적 파일을 보존한다.
- 테두리 #D34F3E, 실선 2px, opacity 1; 채움 #D34F3E, opacity 0.08.
- API 키와 외부 API 런타임 호출 없이 정적 GeoJSON을 제공한다.
- geometry 응답은 카메라를 변경하지 않는다. Polygon/MultiPolygon의 섬과 hole을 보존한다.
- 경계 선택은 주택 지역 필터와 별개다. 지도 이동 후 유지하고 명시적으로 해제한다.

## Shared contracts

`regions/regionBoundary.ts` defines readonly Position = [longitude, latitude], rings and polygons with polygon→ring→position nesting. RegionBoundary contains regionCode, version, polygons. RegionBoundaryMetadata contains regionCode, name, bounds (MapBounds), path. Generated index contains version and region entries. Camera target gains optional bounds: MapBounds and boundsPadding with top/right/bottom/left pixels.

### Task 1: Source data and reproducible assets (controller)

Files: frontend/scripts/region-boundaries/, frontend/public/region-boundaries/<version>/, frontend/src/public-housing/regions/regionBoundaryIndex.json, frontend/docs/region-boundaries.md.
- [x] Download VWorld 법정구역정보 national full data (reference date 2026-09-09, dataset 21); inspect fields, actual .prj, geometry and metadata. The initial N3A source was rejected because official Q&A says 2026 reforms arrive in 2027.
- [x] Compare all codes with backend region catalog. Match only verified geometry; union child districts for parent cities where valid. Record unavailable codes explicitly.
- [x] Verify the downloaded .prj against published EPSG:5186, then transform to WGS84, keep topology, generate individual GeoJSON and bbox index. Record provenance, hash, size and simplification validation if used.
- [x] Verify all generated rings, bboxes, representative cities/islands and mismatch report. No made-up shapes.

### Task 2: NAVER boundary rendering and bounds camera (worker)

Files: frontend/src/maps/naver/NaverMap.tsx, NaverMap.test.tsx, optional regionBoundaryOverlay.ts.
- [x] Write failing tests: fit bounds once, repeated request, no refit on overlay arrival, all polygon parts/holes, replacement and unmount disposal.
- [x] Add optional regionBoundary prop and optional cameraTarget.bounds/boundsPadding. Bounds wins over center/zoom; initial bounds request must execute after map initialization.
- [x] Create one SDK OverlayView with a single SVG path; preserve every ring as a separate subpath and use even-odd filling for islands and holes. Keep pointer events disabled and the layer below markers. Cleanup on replacement/failure/unmount.
- [x] Run focused NaverMap tests and TypeScript check.

### Task 3: GeoJSON repository and HTTP cache (worker)

Files: frontend/src/public-housing/regions/regionBoundary.ts, regionBoundaryRepository.ts, regionBoundaryRepository.test.ts; frontend/nginx.conf.
- [x] Write failing tests using hand-authored GeoJSON with two islands and one hole; malformed coordinates/code/bbox, failure retry, aborted request, bounded cache eviction.
- [x] Export createRegionBoundaryRepository(metadata lookup), find(regionCode, signal): Promise<RegionBoundary>. Validate unknown Feature geometry; support Polygon/MultiPolygon; verify code and version, closed rings and finite WGS84 bounds. Cache successful validated results only, at most 8 regions.
- [x] Add static /region-boundaries/ location with try_files $uri =404, GeoJSON MIME, successful immutable caching, gzip.
- [x] Run focused repository tests. Check Nginx with fixture success/404 responses without altering shared running services.

### Task 4: Search UX and URL integration (controller)

Files: PublicHousingExplorer.tsx/test, search/IntegratedSearch.tsx/test, navigation/regionBoundaryLocation.ts/test, new region boundary control CSS/component.
- [x] Write failing tests for bounds selection independent of region filter, coordinate-less supported region, persistence after close/filter, replace/clear/retry, URL restore/back navigation, no late camera change.
- [x] Derive selected region from boundaryRegionCode; use bundled metadata to request bounds immediately. Load geometry with cancellation and stale-response guard separately.
- [x] Allow supported region selection without representative coordinates. Unsupported regions preserve real coordinate fallback and show unavailable boundary feedback.
- [x] Show selected region, region again, clear and error retry with accessible controls. Fit with current visible panel padding and no zoom-13 floor.
- [x] Verify focused integration tests, then npm run check and real browser selection/drag/reselect/clear, URL, cache and small viewport.

### Task 5: Review and handoff

- [x] Review specification coverage and code quality, fix material findings.
- [x] Report source coverage, limitations, branch, tests and browser evidence. Raw ZIP and unrelated untracked files stay outside commit.

## Progress (2026-09-22)

- Official user-downloaded ZIP validated against SHA-256 and the catalog. Snapshot `vworld-20260909-3f8952dfda77-p2` supports 266 of 269 시군구. 부산 남구, 대구 남구 and 해남군 remain unavailable because of ambiguous, missing or invalid source geometry. See the data document and provenance for repairs, 5m simplification and the bounded precision correction for 화성 union.
- App source is our static per-region GeoJSON. No API key, runtime VWorld request, new backend dependency or raw ZIP is committed. All 266 assets pass the application repository decoder (20,793 rings).
- Search selection uses synchronous bbox and independent asynchronous geometry. URL restore, cancellation, retry without camera movement, reselect, filter/marker persistence and explicit clear are covered.
- Real GL testing found two limitations in the original renderer: 856 Polygon objects took 6,483ms to create for 신안; combining separate exteriors in one SDK Polygon clipped a distant island. The final single SVG OverlayView retains all rings and visually verifies exterior/hole/island-in-hole filling. Local same-environment creation was 29ms for 신안 and 31ms for 태안 (3,198 polygon parts). These measurements exclude a network/paint SLA.
- Real browser checks: 수원시 child-union and 장안구 without search coordinates, search close persistence, drag/zoom, clear/back restore, fresh URL, markers above boundary; desktop 1280×720 and mobile 390×844 fit below the toolbar with padding. No late geometry camera movement is asserted by integration tests.
- Final p2 verification: lint, 746 tests across 45 files with `--maxWorkers=2`, and production build passed. The default-parallel full check had intermittent 5s timeouts in existing filter/admin/Explorer tests; limiting worker count eliminated them without changing test timeouts or assertions. A missing mock export for name restoration was fixed before the final run. The production build retains a >500kB chunk advisory. Offline conversion has 10 passing tests, all 266 assets validated, and all 268 generated files reproduced byte-for-byte.
- Original separate work and user assets remain outside the commit. PR targets develop; no merge or deployment.
