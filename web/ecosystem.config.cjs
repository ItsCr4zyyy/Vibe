// pm2 start ecosystem.config.cjs
module.exports = {
	apps: [
		{
			name: 'vibe-web',
			script: 'build/index.js',
			cwd: __dirname,
			env: {
				PORT: 5317,
				HOST: '127.0.0.1', // za reverzní proxy (nginx); pro přímý přístup dej 0.0.0.0
				ORIGIN: 'https://vibe.wcrazy.eu',
				// APK se stahuje z GitHub Releases (soubor vibe.apk u posledního vydání)
				PUBLIC_REPO_URL: 'https://github.com/ItsCr4zyyy/Vibe',
				PUBLIC_VERSION: '1.0.0'
			}
		}
	]
};
