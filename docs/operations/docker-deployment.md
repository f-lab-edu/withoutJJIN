# Spring Boot Docker 실행과 Gotenberg 네트워크 격리

## 실행 구성

`docker-compose.deploy.yaml`로 Spring Boot·MySQL·Gotenberg를 함께 실행한다.
기존 `docker-compose.yaml`은 IntelliJ에서 Spring을 실행하는 개발 구성으로 유지한다.
배포용 구성은 별도 Compose 프로젝트 `withoutjjin-deploy`를 사용한다.

| 서비스 | 접속 네트워크 | 호스트 포트 |
| --- | --- | --- |
| `app` | `outbound`, `database`, `converter` | `127.0.0.1:8080` (변경 가능) |
| `mysql` | 내부 `database` | 공개하지 않음 |
| `gotenberg` | 내부 `converter` | 공개하지 않음 |

Spring의 DB 주소는 `jdbc:mysql://mysql:3306/ftfm`, 변환 서버 주소는 `http://gotenberg:3000`이다.
컨테이너의 `localhost`는 해당 컨테이너 자신을 가리키므로 호스트 개발용 주소를 사용하지 않는다.

Spring은 `outbound`에서 Google·S3·SES와 통신할 수 있다.
Gotenberg는 `internal: true`인 `converter`에만 연결한다. 외부 DNS 전달이 필요하지 않으므로
Gotenberg의 DNS 업스트림은 `127.0.0.1`로 지정한다. 외부 IP로 직접 접속하는 경로와
일반적인 외부 DNS 조회를 모두 제한하고, 같은 네트워크의 Spring 변환 요청은 받는다.
기존 `API_DISABLE_DOWNLOAD_FROM=true`, `WEBHOOK_DISABLE=true`도 유지한다.

`internal` 네트워크는 같은 네트워크의 서비스나 Docker 호스트까지 모두 차단하는 방화벽은 아니다.
`converter`에는 `app`과 `gotenberg`만 연결하고, Gotenberg에 다른 네트워크·프록시·AWS 인증정보를 추가하지 않는다.
컨테이너 엔진·이미지의 취약점 검사는 네트워크 격리와 별개로 관리해야 한다.

## 이미지 빌드

`Dockerfile`의 build 단계에서 Gradle Wrapper와 Java 17 JDK로 `bootJar`를 실행한다.
runtime 단계에는 Java 17 JRE와 실행 JAR만 복사하며 `spring` 사용자(UID 10001)로 실행한다.
이미지 빌드 과정에서는 DB·S3·Google·SES 인증정보가 필요하지 않다.
`bootJar` 빌드는 자동화 테스트를 실행하는 명령이 아니므로 테스트 통과 여부는 별도로 확인한다.

`.dockerignore`는 빌드에 필요한 Gradle 파일과 `src/main`만 허용한다.
호스트의 `.env`, `.env.docker`, `.git`, `.idea`, `output`과 개발 DB 파일을 빌드 컨텍스트에 넣지 않는다.
실행할 때 Compose가 환경변수를 `app`에 전달한다. MySQL root 비밀번호는 `app`에 전달하지 않는다.

MySQL·Gotenberg가 healthy가 된 후 Spring을 시작한다.
Spring 이미지의 헬스체크는 공개 루트 경로 `/`의 HTTP 응답을 확인한다.
`docker` 프로필은 반복적인 Hibernate SQL의 DEBUG 출력을 줄인다.

## 최초 실행

Docker Desktop 또는 Docker Engine과 Compose v2 이상이 필요하다.
저장소 루트에서 PowerShell로 실행한다.

```powershell
Copy-Item .env.docker.example .env.docker
```

최초 한 번 복사하고 `.env.docker`의 필수값을 입력한다. 기존 `.env.docker`가 있으면 재사용한다.

- `MYSQL_ROOT_PASSWORD`, `DB_PASSWORD`: 새 DB에 사용할 비밀번호
- `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`: Google OAuth 클라이언트
- `INVITATION_TOKEN_ENCRYPTION_KEY`: Base64로 인코딩한 32바이트 키. 재기동·재빌드 시 같은 키를 유지한다.
- `S3_BUCKET`, `SES_FROM_EMAIL`, `AWS_REGION`: 사용할 AWS 리소스 정보
- `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`: 키 방식이면 두 값을 함께 설정한다. 지원되는 IAM 역할 환경에서는 둘 다 비워 SDK 기본 인증정보 공급자를 사용한다.

호스트에서 이미 IntelliJ Spring이 8080을 사용 중이면 중지하거나 `APP_PORT`를 다른 값으로 지정한다.

```powershell
docker compose --env-file .env.docker -f docker-compose.deploy.yaml config --quiet
docker compose --env-file .env.docker -f docker-compose.deploy.yaml up -d --build --wait
docker compose --env-file .env.docker -f docker-compose.deploy.yaml ps
```

기본 화면 주소는 `http://localhost:8080`이다. `APP_PORT`를 변경했다면 해당 포트를 사용한다.
Google OAuth의 허용 리디렉션 URI도 브라우저 주소와 포트를 기준으로 등록해야 한다.
`localhost:8080` 기준 콜백은 `http://localhost:8080/login/oauth2/code/google`이다.

`.env.docker`는 Git에서 제외된다. 전체 환경변수를 출력하는 명령 대신 문법 확인에는 `config --quiet`를 사용한다.

## DB와 재시작

배포용 MySQL 데이터는 `withoutjjin-deploy_mysql-data` 볼륨에 보관한다.
기존 개발용 `withoutjjin_mysql-data`와 다른 볼륨이므로 개발 데이터가 자동으로 옮겨지지는 않는다.
실제 데이터를 옮길 때는 백업·복원 절차를 별도로 진행한다.

초기 컨테이너 실행에서는 기존 애플리케이션의 `ddl-auto=update`를 유지한다.
`DB_DDL_AUTO`로 변경할 수 있다. 공개 운영 배포 전에는 DB 백업·스키마 변경 절차와
`validate` 전환 여부를 정해야 한다.

코드 변경 후 Spring만 다시 빌드·기동하려면:

```powershell
docker compose --env-file .env.docker -f docker-compose.deploy.yaml up -d --build --no-deps --wait app
docker compose --env-file .env.docker -f docker-compose.deploy.yaml logs --tail 100 app
```

코드 변경은 실행 중인 이미지에 자동 반영되지 않는다. 새 이미지를 빌드하고 컨테이너를 재생성한다.

종료할 때는 볼륨을 유지한다.

```powershell
docker compose --env-file .env.docker -f docker-compose.deploy.yaml down
```

`down --volumes`는 DB 볼륨도 삭제하므로 실제 데이터를 유지할 환경에서는 사용하지 않는다.

## 외부 통신 차단 확인

Spring의 외부 DNS 조회는 성공해야 한다.

```powershell
docker compose --env-file .env.docker -f docker-compose.deploy.yaml exec -T app getent ahostsv4 example.com
```

Gotenberg의 외부 DNS 조회와 외부 IP TCP 연결은 실패해야 한다.

```powershell
docker compose --env-file .env.docker -f docker-compose.deploy.yaml exec -T gotenberg timeout 5 getent ahostsv4 example.com
docker compose --env-file .env.docker -f docker-compose.deploy.yaml exec -T gotenberg timeout 5 bash -c 'exec 3<>/dev/tcp/1.1.1.1/443'
```

실패 결과만으로 네트워크가 정상 격리되었다고 판단하지 않고, 같은 환경의 Spring에서
외부 통신이 성공하는지와 내부 변환 요청이 성공하는지도 함께 확인한다.

## 검증 결과

2026-11-24, Docker Desktop의 Linux 컨테이너 환경에서 검증했다.
기존 개발용 컨테이너와 분리된 임의 이름의 Compose 프로젝트·MySQL 볼륨을 사용했다.

- 다단계 Docker 이미지 빌드 성공
- Spring·MySQL·Gotenberg의 healthy 상태 확인
- Spring UID 10001 실행, 루트 경로 200·비로그인 보호 API 401 확인
- Spring의 외부 DNS 조회와 외부 TCP/443 연결 성공 확인
- Gotenberg의 외부 DNS 조회, 외부 TCP/80·443, 메타데이터 주소 `169.254.169.254:80` 연결 실패 확인
- 앱 네트워크 네임스페이스에서 실제 `DocumentConversionConfig`·`WordPdfConverter`로 생성한 DOCX를 Gotenberg에 전송하여 1페이지 PDF 반환·검증 확인
- Spring 컨테이너 재생성 후 MySQL 볼륨 유지와 Spring 재기동 확인

변환 검증에는 테스트용으로 생성한 DOCX를 사용했다. 실제 AWS·Google 로그인·SES 발송이나
브라우저의 전체 업로드 흐름을 검증한 결과는 아니다. 테스트용 컨테이너·네트워크·DB 볼륨은 검증 후 정리했다.

## 실제 서버 배포 전 남은 작업

서버 준비 후 도메인·HTTPS·리버스 프록시, 전달 헤더·세션 쿠키 설정, Google 콜백과 S3 CORS,
IAM 권한, DB 백업·스키마 변경 절차를 실제 배포 환경에 맞춘다.
현재 앱 포트는 호스트 루프백에만 공개하므로 이 구성만으로 인터넷에 서비스가 노출되지는 않는다.
Gotenberg의 CPU·메모리 제한은 이번 구성에 포함하지 않았다.

## 참고

- [Docker 내부 네트워크](https://docs.docker.com/reference/compose-file/networks/#internal)
- [Compose 시작 순서와 헬스체크](https://docs.docker.com/compose/how-tos/startup-order/)
- [Dockerfile 다단계 빌드](https://docs.docker.com/build/building/multi-stage/)
