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
- [ ] Download VWorld 법정구역정보 national full data (reference date 2026-09-09, dataset 21); inspect fields, actual .prj, geometry and metadata. The initial N3A source was rejected because official Q&A says 2026 reforms arrive in 2027.
- [ ] Compare all codes with backend region catalog. Match only verified geometry; union child districts for parent cities where valid. Record unavailable codes explicitly.
- [ ] Verify the downloaded .prj against published EPSG:5186, then transform to WGS84, keep topology, generate individual GeoJSON and bbox index. Record provenance, hash, size and simplification validation if used.
- [ ] Verify all generated rings, bboxes, representative cities/islands and mismatch report. No made-up shapes.

### Task 2: NAVER boundary rendering and bounds camera (worker)

Files: frontend/src/maps/naver/NaverMap.tsx, NaverMap.test.tsx, optional regionBoundaryOverlay.ts.
- [x] Write failing tests: fit bounds once, repeated request, no refit on overlay arrival, all polygon parts/holes, replacement and unmount disposal.
- [x] Add optional regionBoundary prop and optional cameraTarget.bounds/boundsPadding. Bounds wins over center/zoom; initial bounds request must execute after map initialization.
- [x] Create one SDK Polygon per polygon part, preserving rings; clickable false, approved style, below markers. Cleanup on replacement/failure/unmount.
- [x] Run focused NaverMap tests and TypeScript check.

### Task 3: GeoJSON repository and HTTP cache (worker)

Files: frontend/src/public-housing/regions/regionBoundary.ts, regionBoundaryRepository.ts, regionBoundaryRepository.test.ts; frontend/nginx.conf.
- [x] Write failing tests using hand-authored GeoJSON with two islands and one hole; malformed coordinates/code/bbox, failure retry, aborted request, bounded cache eviction.
- [x] Export createRegionBoundaryRepository(metadata lookup), find(regionCode, signal): Promise<RegionBoundary>. Validate unknown Feature geometry; support Polygon/MultiPolygon; verify code and version, closed rings and finite WGS84 bounds. Cache successful validated results only, at most 8 regions.
- [x] Add static /region-boundaries/ location with try_files $uri =404, GeoJSON MIME, successful immutable caching, gzip.
- [x] Run focused repository tests. Check Nginx with fixture success/404 responses without altering shared running services.

### Task 4: Search UX and URL integration (controller)

Files: PublicHousingExplorer.tsx/test, search/IntegratedSearch.tsx/test, navigation/regionBoundaryLocation.ts/test, new region boundary control CSS/component.
- [ ] Write failing tests for bounds selection independent of region filter, coordinate-less supported region, persistence after close/filter, replace/clear/retry, URL restore/back navigation, no late camera change.
- [ ] Derive selected region from boundaryRegionCode; use bundled metadata to request bounds immediately. Load geometry with cancellation and stale-response guard separately.
- [ ] Allow supported region selection without representative coordinates. Unsupported regions preserve real coordinate fallback and show unavailable boundary feedback.
- [ ] Show selected region, region again, clear and error retry with accessible controls. Fit with current visible panel padding and no zoom-13 floor.
- [ ] Verify focused integration tests, then npm run check and real browser selection/drag/reselect/clear, URL, cache and small viewport.

### Task 5: Review and handoff

- [ ] Review specification coverage and code quality, fix material findings.
- [ ] Report source coverage, limitations, branch, tests and browser evidence. Raw ZIP and unrelated untracked files stay outside commit.

## Progress (2026-09-22)

- Task 1 pending: browser login confirmed but download actions produced no local file. User asked for the path of the official 2026-09-09 national full-data ZIP (120MB). No official geometry is bundled yet.
- Task 2 implemented and unit-reviewed; real SDK fixture verified polygon holes, multiple parts, style and drag/clear. Browser caught an initial GL fit before SDK initialization (first zoom15 clipped bounds, subsequent fit14.292 covered them); fixed by waiting for the current map attempt’s SDK init event; fresh reload now matches the later correct fit.
- Task 3 complete: 39 repository tests, isolated real Nginx MIME/gzip/cache/404 checks and independent scope/code review passed.
- Task 4 pending official data validation/index; no Explorer/search integration or URL controls shipped yet.
- Final npm run check passed 722 tests (41 files), lint and build after the initial-fit fix. Nginx success/cache/gzip/404 verification also passed.
- Existing user files preserved; no PR yet.
