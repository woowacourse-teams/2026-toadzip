# DB 서버 설정

## 환경변수

`.env.example`을 참고해서 `.env`를 생성한다.

## 실행

```shell
docker compose up -d
```

## 종료

```shell
docker compose down
```

## 백업

개발·운영·공유 DB의 일일 백업, S3 30일 보관 설정과 복원 연습은 [BACKUP.md](BACKUP.md)를 따른다.
