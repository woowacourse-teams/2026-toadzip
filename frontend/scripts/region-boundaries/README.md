# 공식 경계 오프라인 변환

Python 3.12용 일회성 빌드 도구다. 앱 런타임 의존성이나 외부 API 호출은 없다.
저장소 루트에서 실행한다. 원본 ZIP은 저장소 밖에 보관한다.

```sh
python3.12 -m venv /tmp/toadzip-boundary-venv
/tmp/toadzip-boundary-venv/bin/pip install -r frontend/scripts/region-boundaries/requirements.txt
PYTHONDONTWRITEBYTECODE=1 /tmp/toadzip-boundary-venv/bin/python -m unittest discover -s frontend/scripts/region-boundaries -p 'test_*.py'
PYTHONDONTWRITEBYTECODE=1 /tmp/toadzip-boundary-venv/bin/python frontend/scripts/region-boundaries/convert.py \
  ~/Downloads/AL_D001_00_20260909.zip --acquired-at '2026-09-22T16:01:25+09:00'
PYTHONDONTWRITEBYTECODE=1 /tmp/toadzip-boundary-venv/bin/python frontend/scripts/region-boundaries/verify_assets.py
```

`--acquired-at`은 원본을 확보한 기록이며 데이터 기준일과 다르다. 이 스냅샷은 다운로드 파일의 생성 시각을 증거로 사용한다. `--output-root /tmp/other-root`로 별도 디렉터리에 재생성할 수 있다. 카탈로그 입력은 스크립트가 있는 저장소의 `backend/src/main/resources/region/regions.csv`다.

변환기는 감사한 원본 SHA-256을 강제한다. CP949 DBF, 중첩 SIG ZIP, EPSG:5186에 해당하는 PRJ 수치와 datum 선언을 검증한다. 중복 코드·이름 불일치·사용할 수 없는 도형은 실패 사유로 남긴다. 17개 이름의 시도 접두어 오기만 명시적으로 교정하며 코드/위치를 이동하지 않는다. 부모 도시는 카탈로그의 같은 시도·도시 이름을 가진 모든 자식 구가 검증된 경우에만 union한다.

GEOS `make_valid` 결과가 Polygon/MultiPolygon이고 bbox가 같고 경계의 이산 Hausdorff 거리가 0에 가까우며 면적 상대 차이가 1e-8 미만인 경우만 도형 수정을 허용한다. 비면적 잔여물은 버리지 않는다. 모든 수정의 전후 polygon/hole/position 개수·면적 차이·원본 오류를 `provenance.json`에 기록한다. `preserve_topology=True`, 허용 오차 5m로 단순화하고 섬/구멍 개수 보존을 강제한다. WGS84 변환 후 반시계 외곽선/시계 구멍 순서와 닫힌 링·유효성·좌표 범위를 검증한다. 작은 섬이 소실되지 않도록 소수점 반올림은 하지 않는다.

같은 버전의 산출물이 달라지면 덮어쓰지 않고 실패한다. 원본/가공 규칙 변경 시 `VERSION`의 스냅샷 또는 가공 버전을 새로 정하고 기존 버전 파일은 유지한다. 재실행은 파일 내용이 같은지 확인하므로 결정성을 검증한다. 버전 인덱스만 새 산출물을 가리키도록 바뀐다.

전체 증거와 제한은 [지역 경계 데이터](../../docs/region-boundaries.md)에 있다. 원저작자: 국토교통부/K-Geo플랫폼, VWorld 법정구역정보. 라이선스: [CC BY 2.0 KR](https://creativecommons.org/licenses/by/2.0/kr/).

GEOS의 topology 보존 옵션에도 극소 해안 도형에서 잘못된 도형이 나올 수 있다. 단순화 결과가 유효하지 않거나 polygon/hole 개수가 달라지면 해당 지역 전체를 원본 정밀도로 유지하고 `simplificationFallback`에 기록한다.

화성처럼 부모 union이 WGS84에서 정밀도 붕괴를 일으키면 자식들을 1e-7m 고정 grid로 정규화한 뒤 합친다. 모든 자식의 실제 섬/구멍 개수를 보존해야 하고 최대 경계 이동이 grid 반대각선 + 1e-10m 이하인지 확인한다. 면적 상대 차이 1e-8 초과 시 실패한다. 이 경우 추가 단순화 없이 전체 도형을 배포한다. 정규화는 면적에 따라 구멍을 삭제하는 필터가 아니다.

이산 Hausdorff 계산은 공간 인덱스로 각 경계 정점의 상대 경계 선분까지 최소 거리를 구하고 양방향 최대값을 취한다. GEOS 전체 스캔과 같은 정의이며 hand-derived 테스트 및 265개 실제 기존 결과와 비교했다.
