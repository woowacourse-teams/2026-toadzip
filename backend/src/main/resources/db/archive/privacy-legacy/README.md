# 적용 이력 보존용 개인정보 SQL

이 디렉터리는 이미 실행된 `V20261009_01`~`04`의 원본을 byte 단위로 보존한다.
`SHA256SUMS`가 원본 해시다. 정상 Flyway의 `classpath:db/migration`과 별도 개인정보
Flyway의 `classpath:db/privacy` 모두 이 경로를 스캔하지 않는다.

이 SQL은 새 설치에 실행하지 않으며 runtime location에 이 경로를 추가하지 않는다.
이 디렉터리의 SQL 파일명이 적용 이력에 기록된 DB는 시작 전 검증에서 중단하고, 별도 승인된 복구를 진행한다.
원본을 덮어쓰거나 이력을 삭제·repair·ignore하여 새 구조와 같다고 취급하지 않는다.
구체적인 계약은 [개인정보 DB 배포](../../../../../../docs/operations/PRIVACY.md)를 따른다.
