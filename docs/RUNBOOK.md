# RUNBOOK

Operational guide for local development and the shared demo/prod-like stack.

All paths below are from:

```text
/Users/amir/Desktop/work/pharma/PharmaPayV2
```

## 1. Local Infrastructure

Prerequisites:

- Docker Desktop;
- JDK 22 / Temurin for backend;
- Node 22+ for admin frontend;
- Flutter 3.27 for mobile;
- ports `5433`, `6379`, `9000`, `9001`, `8080`, `5173` free.

Start infrastructure:

```bash
docker compose up -d
docker compose ps
docker exec epharm-postgres pg_isready -U epharm -d epharm
```

Services:

| Service       | URL/port         |
| ------------- | ---------------- |
| Postgres      | `localhost:5433` |
| Redis         | `localhost:6379` |
| MinIO API     | `localhost:9000` |
| MinIO console | `localhost:9001` |

Stop:

```bash
docker compose stop
docker compose down
docker compose down -v   # destructive: removes local DB volume
```

## 2. Backend

```bash
cd admin-panel/backend
export JAVA_HOME=/Users/amir/Library/Java/JavaVirtualMachines/temurin-22.0.2/Contents/Home
./gradlew bootRun
```

Health:

```bash
curl http://localhost:8080/api/health
```

Useful commands:

```bash
./gradlew test
./gradlew build
```

Dev reset:

```bash
curl -X POST http://localhost:8080/api/admin/dev/reset
```

The dev reset exists only in the dev profile.

Dev admin users:

| Email                | Password        | Role          |
| -------------------- | --------------- | ------------- |
| `damir@jadran.com`   | `damir2026`     | Brand Manager |
| `aigerim@inkar.kz`   | `aigerim2026`   | Category Lead |
| `bauyrzhan@inkar.kz` | `bauyrzhan2026` | HQ Head       |

Dev POSM key:

```text
dev-posm-key
```

## 3. Admin Frontend

```bash
cd admin-panel/frontend
npm install
npm run dev
```

Open:

```text
http://localhost:5173
```

Other checks:

```bash
npm run lint
npm test
npm run build
npm run test:e2e
```

Playwright expects backend and Docker infra to be running. It can launch frontend itself from the
Playwright config.

## 4. Mobile App

Shared demo backend:

```bash
flutter run \
  --dart-define=USE_API=true \
  --dart-define=API_BASE=https://epharm.78-140-246-238.sslip.io
```

Local backend:

```bash
# iOS simulator
flutter run --dart-define=USE_API=true --dart-define=API_BASE=http://localhost:8080

# Android emulator
flutter run --dart-define=USE_API=true --dart-define=API_BASE=http://10.0.2.2:8080
```

Offline demo:

```bash
flutter run --dart-define=USE_API=false
```

Checks:

```bash
flutter analyze lib test
flutter test
```

OTP is `544544` while dev OTP mode is active.

## 5. Full Local Cycle

Terminal 1:

```bash
cd /Users/amir/Desktop/work/pharma/PharmaPayV2
docker compose up -d
```

Terminal 2:

```bash
cd /Users/amir/Desktop/work/pharma/PharmaPayV2/admin-panel/backend
export JAVA_HOME=/Users/amir/Library/Java/JavaVirtualMachines/temurin-22.0.2/Contents/Home
./gradlew bootRun
```

Terminal 3:

```bash
cd /Users/amir/Desktop/work/pharma/PharmaPayV2/admin-panel/frontend
npm run dev
```

Terminal 4, optional mobile:

```bash
cd /Users/amir/Desktop/work/pharma/PharmaPayV2
flutter run --dart-define=USE_API=true --dart-define=API_BASE=http://localhost:8080
```

## 6. Receipt Moderation Flow

1. Mobile user logs in and uploads a receipt photo.
2. Backend stores photo in MinIO and creates a receipt.
3. Admin opens Reconcile.
4. Moderator approves or rejects.
5. Approval credits pharmacist balance.

API smoke:

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/admin/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"damir@jadran.com","password":"damir2026"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["tokens"]["accessToken"])')

curl -s localhost:8080/api/admin/reconcile/summary \
  -H "Authorization: Bearer $TOKEN"
```

OCR and OFD QR verification are not part of the current product.

## 7. POSM Local Smoke

Backend must be running.

Example request:

```bash
curl -s -X POST http://localhost:8080/api/posm/recommend \
  -H 'X-Posm-Key: dev-posm-key' \
  -H 'Content-Type: application/json' \
  -d '{
    "pharmacistId":"u_smoke",
    "pharmacyId":"ph_smoke",
    "sessionId":"s_smoke",
    "scannedBarcode":"4603423004936",
    "cart":[{"barcode":"4603423004936","name":"Аквалор","qty":1}]
  }'
```

For Windows client operation, use:

- `App/scripts/README-distrib.md`;
- `../App/WINDOWS_RUNBOOK.md`;
- `App/POSM_DEPLOY.md`.

## 8. Production Stack

Current public host:

```text
https://epharm.78-140-246-238.sslip.io
```

On server:

```bash
cd /root/epharm
docker compose --env-file .env.prod -f docker-compose.prod.yml ps
docker compose --env-file .env.prod -f docker-compose.prod.yml logs -f backend
docker logs epharm-caddy --tail 100
```

Deploy from local git snapshot:

```bash
git archive --format=tar.gz -o /tmp/epharm-deploy.tar.gz HEAD \
  admin-panel/backend admin-panel/frontend docker-compose.prod.yml Caddyfile tools .env.prod.example

scp -i ~/.ssh/epharm_deploy /tmp/epharm-deploy.tar.gz root@78.140.246.238:/tmp/
```

Then on server:

```bash
cd /root/epharm
tar xzf /tmp/epharm-deploy.tar.gz admin-panel/backend admin-panel/frontend Caddyfile
bash tools/pg-backup.sh
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --build backend frontend caddy
```

Frontend-only deploy should use `--no-deps frontend` to avoid unnecessary backend recreate.

## 9. Current Caddy Shape

`Caddyfile` has one public site block because current `API_DOMAIN`, `ADMIN_DOMAIN`, and `S3_DOMAIN`
may be the same sslip host. It routes by path:

- `/api/*` -> backend;
- `/s3/*` -> MinIO;
- `/` -> frontend.

Do not restore three identical site blocks: Caddy will crash with `ambiguous site definition`.

## 10. Common Problems

| Symptom                                             | Fix                                                                                |
| --------------------------------------------------- | ---------------------------------------------------------------------------------- |
| Backend cannot connect to `localhost:5433`          | Start `docker compose up -d`; wait for Postgres healthy.                           |
| Port 8080 busy                                      | `lsof -ti tcp:8080 \| xargs kill -9`                                               |
| Port 5173 busy                                      | use another Vite port or kill the old process.                                     |
| Flyway checksum mismatch                            | Do not edit applied migrations. For local-only reset use `docker compose down -v`. |
| Mobile cannot reach local backend on physical phone | Use Mac LAN IP, not `localhost`.                                                   |
| iOS codesign xattr error                            | Recreate `/tmp/codesign_shim` or keep build output outside iCloud-synced paths.    |
| Admin session odd after backend restart             | Refresh page; axios should refresh token. If refresh fails, log in again.          |
| Medusa images blocked in browser                    | Use `proxyMedia`/`/api/media/img`, not raw HTTP image URLs.                        |
