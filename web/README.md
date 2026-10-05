# Vibe – web ke stažení

Jednostránkový web (SvelteKit + adapter-node) s popisem aplikace a odkazem na APK.

## Lokálně
```bash
npm install
npm run dev        # http://localhost:5317
```

## Nasazení na Ubuntu (pm2)
```bash
# na serveru, ve složce web/
npm ci
npm run build
pm2 start ecosystem.config.cjs
pm2 save           # po restartu serveru se spustí znovu (jednou: pm2 startup)
```

Web poslouchá na `127.0.0.1:5317`. Repozitář a verze se nastavují v `ecosystem.config.cjs`
(`PUBLIC_REPO_URL`, `PUBLIC_VERSION`, případně `PUBLIC_DOWNLOAD_URL`). Po změně stačí `pm2 restart vibe-web --update-env`,
není potřeba znovu buildovat.

## APK (GitHub Releases)
Tlačítko „Stáhnout“ vede na `https://github.com/ItsCr4zyyy/Vibe/releases/latest/download/vibe.apk`.
Při vydání verze nahraj do GitHub Release soubor `app/build/outputs/apk/release/app-arm64-v8a-release.apk`
**přejmenovaný na `vibe.apk`** (arm64 = prakticky všechny telefony za posledních ~8 let).

## nginx (vibe.wcrazy.eu → port 5317)
```nginx
server {
    server_name vibe.wcrazy.eu;
    location / {
        proxy_pass http://127.0.0.1:5317;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```
HTTPS pak `sudo certbot --nginx -d vibe.wcrazy.eu`.
