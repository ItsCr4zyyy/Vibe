<script>
	import { env } from '$env/dynamic/public';

	const repoUrl = env.PUBLIC_REPO_URL || 'https://github.com/ItsCr4zyyy/Vibe';
	const downloadUrl = env.PUBLIC_DOWNLOAD_URL || `${repoUrl}/releases/latest/download/vibe.apk`;
	const releasesUrl = `${repoUrl}/releases`;
	const version = env.PUBLIC_VERSION || '1.0.0';

	const features = [
		{ icon: '🔎', title: 'Hledání jako na YouTube Music', text: 'Skladby, interpreti a alba s obaly. Stránky interpretů, novinky od těch, které sleduješ.' },
		{ icon: '📥', title: 'Import playlistů', text: 'Z YouTube, YouTube Music, Spotify i SoundCloudu – vložením odkazu nebo přes „Sdílet → Vibe“. Playlisty se samy synchronizují.' },
		{ icon: '📻', title: 'Rádio a mixy', text: 'Nekonečné rádio ze skladby nebo playlistu, „Tvůj mix“ z celé knihovny a automatické pokračování po dohrání fronty.' },
		{ icon: '💾', title: 'Stream i offline', text: 'Poslouchej online, nebo si hudbu stáhni. Volitelně se ukládá všechno, co přehraješ, nebo vše, co dostane srdíčko.' },
		{ icon: '🚗', title: 'Android Auto a Chromecast', text: 'Knihovna, mixy a hlasové „pusť … na Vibe“ v autě. Doma pošleš hudbu na reproduktor nebo TV.' },
		{ icon: '🎚️', title: 'Zvuk', text: 'Crossfade, vyrovnání hlasitosti, ekvalizér, časovač vypnutí a synchronizované texty písní.' },
		{ icon: '📊', title: 'Statistiky a Wrapped', text: 'Kolik minut posloucháš, top interpreti a skladby po týdnech až rocích. Na konci roku Vibe Wrapped ke sdílení.' },
		{ icon: '🔄', title: 'Bez účtu, bez reklam', text: 'Žádná registrace. Stahovací engine (yt-dlp) se aktualizuje sám, takže aplikace funguje dál i bez nových verzí.' }
	];

	const steps = [
		{ title: 'Stáhni APK', text: 'Tlačítkem nahoře – stáhne se soubor vibe.apk z GitHubu (sekce Releases).' },
		{ title: 'Povol instalaci', text: 'Otevři stažený soubor. Android se zeptá, jestli prohlížeči povolíš instalovat aplikace – povol to.' },
		{ title: 'Nainstaluj a spusť', text: 'Při prvním spuštění se chvíli připravuje stahovací engine. Pak stačí něco vyhledat nebo naimportovat playlist.' }
	];

	const faq = [
		{ q: 'Co je Vibe zač?', a: 'Soukromý projekt jednoho člověka, žádná firma ani služba. Aplikace není dokončená a nemusí se dál vyvíjet – klidně zůstane jen u téhle verze. Stahovací engine se ale aktualizuje sám, takže by měla fungovat i dál.' },
		{ q: 'Proč to není na Google Play?', a: 'Je to soukromý projekt. Na Google Play by aplikace přehrávající hudbu z YouTube navíc neprošla.' },
		{ q: 'Kde najdu zdrojový kód?', a: 'Na GitHubu – odkaz je dole na stránce. Tam jsou i všechny vydané verze APK.' },
		{ q: 'Funguje to v Android Auto?', a: 'Ano. Protože Vibe není z Google Play, musíš v Android Auto zapnout vývojářský režim (10× ťuknout na verzi v nastavení Android Auto) a v nastavení pro vývojáře povolit „Neznámé zdroje“.' },
		{ q: 'Přehraju i 18+ písničky?', a: 'YouTube je bez přihlášení nevydá, ale Vibe za ně automaticky najde jinou nahrávku stejné písničky. Výsledky ze SoundCloudu si můžeš zapnout v Nastavení → Zdroje.' },
		{ q: 'Co když něco přestane fungovat?', a: 'Většinu změn na YouTube vyřeší automatická aktualizace yt-dlp. V Nastavení → Diagnostika je tlačítko „Otestovat vše“, které ukáže, co nefunguje.' },
		{ q: 'Jaké zařízení potřebuju?', a: 'Android 8 nebo novější. Instalace má kolem 80 MB, protože obsahuje vlastní engine pro stahování.' }
	];
</script>

<svelte:head>
	<title>Vibe – hudba bez reklam a bez účtu</title>
	<meta name="description" content="Vibe je osobní hudební přehrávač pro Android. Streamuj i stahuj hudbu, importuj playlisty ze Spotify a YouTube." />
	<meta property="og:title" content="Vibe – hudba bez reklam a bez účtu" />
	<meta property="og:description" content="Osobní hudební přehrávač pro Android. Stáhni si APK." />
</svelte:head>

<div class="glow" aria-hidden="true"></div>

<header class="nav">
	<a class="brand" href="/">
		<img src="/favicon.svg" alt="" width="32" height="32" />
		<span>Vibe</span>
	</a>
	<a class="nav-download" href={downloadUrl}>Stáhnout</a>
</header>

<main>
	<section class="hero">
		<p class="badge">Android • verze {version} • soukromý projekt</p>
		<h1>Tvoje hudba.<br /><span class="accent">Bez reklam a bez účtu.</span></h1>
		<p class="lead">
			Vibe je hudební přehrávač ve stylu Spotify. Hledej, importuj playlisty, poslouchej online i offline –
			zdarma a bez přihlašování.
		</p>
		<div class="cta">
			<a class="btn primary" href={downloadUrl} download>
				<svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><path fill="currentColor" d="M12 3a1 1 0 0 1 1 1v9.59l3.3-3.3a1 1 0 1 1 1.4 1.42l-5 5a1 1 0 0 1-1.4 0l-5-5a1 1 0 1 1 1.4-1.42l3.3 3.3V4a1 1 0 0 1 1-1Zm-8 15a1 1 0 0 1 1 1v1h14v-1a1 1 0 1 1 2 0v2a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-2a1 1 0 0 1 1-1Z" /></svg>
				Stáhnout APK
			</a>
			<a class="btn ghost" href="#instalace">Jak nainstalovat</a>
		</div>
		<p class="note">Android 8+ • cca 75 MB • <a href={releasesUrl}>všechny verze na GitHubu</a></p>
	</section>

	<section class="features">
		{#each features as f}
			<article class="card">
				<div class="icon">{f.icon}</div>
				<h3>{f.title}</h3>
				<p>{f.text}</p>
			</article>
		{/each}
	</section>

	<section id="instalace" class="section">
		<h2>Instalace</h2>
		<ol class="steps">
			{#each steps as s, i}
				<li>
					<span class="num">{i + 1}</span>
					<div>
						<h3>{s.title}</h3>
						<p>{s.text}</p>
					</div>
				</li>
			{/each}
		</ol>
	</section>

	<section class="section">
		<h2>Časté otázky</h2>
		<div class="faq">
			{#each faq as item}
				<details>
					<summary>{item.q}</summary>
					<p>{item.a}</p>
				</details>
			{/each}
		</div>
	</section>

	<section class="section">
		<div class="status">
			<h2>Stav projektu</h2>
			<p>
				Vibe je <strong>soukromý projekt</strong> a <strong>není dokončený</strong>. Některé věci se můžou chovat
				nečekaně a je dost možné, že tahle verze zůstane jediná. Používání je na vlastní riziko – ale hudbu hraje. 🎧
			</p>
		</div>
	</section>

	<section class="final">
		<h2>Pusť si to.</h2>
		<a class="btn primary" href={downloadUrl} download>Stáhnout Vibe {version}</a>
	</section>
</main>

<footer>
	<p>
		Vibe je soukromý, nedokončený projekt. Není spojen s YouTube, Spotify ani SoundCloudem.
		<br /><a href={repoUrl}>Zdrojový kód na GitHubu</a>
	</p>
</footer>

<style>
	:global(*) {
		box-sizing: border-box;
	}
	:global(html) {
		scroll-behavior: smooth;
	}
	:global(body) {
		margin: 0;
		background: #0c0b10;
		color: #f4f4f6;
		font-family: system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif;
		line-height: 1.55;
		-webkit-font-smoothing: antialiased;
	}

	.glow {
		position: fixed;
		inset: -20% -10% auto;
		height: 70vh;
		background:
			radial-gradient(40% 50% at 30% 40%, rgba(183, 156, 255, 0.24), transparent 70%),
			radial-gradient(40% 50% at 75% 30%, rgba(240, 166, 216, 0.18), transparent 70%);
		filter: blur(20px);
		pointer-events: none;
		z-index: 0;
	}

	.nav,
	main,
	footer {
		position: relative;
		z-index: 1;
		max-width: 1080px;
		margin: 0 auto;
		padding-inline: 16px;
	}

	.nav {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding-block: 20px;
	}
	.brand {
		display: flex;
		align-items: center;
		gap: 10px;
		color: inherit;
		text-decoration: none;
		font-weight: 800;
		font-size: 1.25rem;
		letter-spacing: -0.02em;
	}
	.brand img {
		border-radius: 8px;
	}
	.nav-download {
		color: #0b0b0f;
		background: #f4f4f6;
		padding: 8px 18px;
		border-radius: 999px;
		font-weight: 700;
		text-decoration: none;
		font-size: 0.95rem;
	}

	.hero {
		text-align: center;
		padding-block: 72px 56px;
	}
	.badge {
		display: inline-block;
		margin: 0 0 20px;
		padding: 6px 14px;
		border-radius: 999px;
		background: rgba(183, 156, 255, 0.14);
		color: #b79cff;
		font-weight: 600;
		font-size: 0.85rem;
	}
	h1 {
		font-size: clamp(2.4rem, 7vw, 4.4rem);
		line-height: 1.05;
		letter-spacing: -0.04em;
		margin: 0;
		font-weight: 900;
	}
	.accent {
		background: linear-gradient(90deg, #b79cff, #f0a6d8);
		-webkit-background-clip: text;
		background-clip: text;
		color: transparent;
	}
	.lead {
		max-width: 620px;
		margin: 24px auto 0;
		color: #a1a1aa;
		font-size: 1.15rem;
	}
	.cta {
		display: flex;
		flex-wrap: wrap;
		gap: 12px;
		justify-content: center;
		margin-top: 36px;
	}
	.btn {
		display: inline-flex;
		align-items: center;
		gap: 10px;
		padding: 14px 28px;
		border-radius: 999px;
		font-weight: 700;
		font-size: 1.05rem;
		text-decoration: none;
		transition:
			transform 0.15s ease,
			box-shadow 0.15s ease,
			background 0.15s ease;
	}
	.btn.primary {
		background: #b79cff;
		color: #1c0b45;
		box-shadow: 0 10px 30px rgba(183, 156, 255, 0.3);
	}
	.btn.primary:hover {
		transform: translateY(-2px);
		box-shadow: 0 14px 36px rgba(183, 156, 255, 0.4);
	}
	.btn.ghost {
		color: #f4f4f6;
		border: 1px solid #2c2c36;
	}
	.btn.ghost:hover {
		background: #1c1c25;
	}
	.note {
		margin-top: 14px;
		color: #71717a;
		font-size: 0.9rem;
	}

	.features {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
		gap: 14px;
		padding-block: 24px;
	}
	.card {
		background: #121218;
		border: 1px solid #1f1f28;
		border-radius: 18px;
		padding: 22px;
	}
	.icon {
		font-size: 1.6rem;
	}
	.card h3 {
		margin: 12px 0 6px;
		font-size: 1.05rem;
	}
	.card p {
		margin: 0;
		color: #a1a1aa;
		font-size: 0.95rem;
	}

	.section {
		padding-block: 56px 8px;
	}
	h2 {
		font-size: clamp(1.7rem, 4vw, 2.3rem);
		letter-spacing: -0.03em;
		margin: 0 0 24px;
	}
	.steps {
		list-style: none;
		padding: 0;
		margin: 0;
		display: grid;
		gap: 14px;
	}
	.steps li {
		display: flex;
		gap: 16px;
		align-items: flex-start;
		background: #121218;
		border: 1px solid #1f1f28;
		border-radius: 18px;
		padding: 20px;
	}
	.num {
		flex: none;
		width: 36px;
		height: 36px;
		border-radius: 50%;
		display: grid;
		place-items: center;
		background: #b79cff;
		color: #1c0b45;
		font-weight: 800;
	}
	.steps h3 {
		margin: 4px 0 4px;
		font-size: 1.05rem;
	}
	.steps p {
		margin: 0;
		color: #a1a1aa;
	}

	.faq {
		display: grid;
		gap: 10px;
	}
	details {
		background: #121218;
		border: 1px solid #1f1f28;
		border-radius: 14px;
		padding: 16px 20px;
	}
	summary {
		cursor: pointer;
		font-weight: 600;
		list-style: none;
		display: flex;
		justify-content: space-between;
		gap: 12px;
	}
	summary::-webkit-details-marker {
		display: none;
	}
	summary::after {
		content: '+';
		color: #b79cff;
		font-size: 1.3rem;
		line-height: 1;
	}
	details[open] summary::after {
		content: '–';
	}
	details p {
		margin: 12px 0 0;
		color: #a1a1aa;
	}

	.final {
		text-align: center;
		padding-block: 72px;
	}
	.final h2 {
		margin-bottom: 24px;
	}

	footer {
		border-top: 1px solid #1f1f28;
		padding-block: 28px 40px;
		color: #71717a;
		font-size: 0.85rem;
		text-align: center;
	}

	.note a,
	footer a {
		color: #b79cff;
	}
	.status {
		background: linear-gradient(135deg, rgba(183, 156, 255, 0.12), rgba(240, 166, 216, 0.08));
		border: 1px solid rgba(183, 156, 255, 0.25);
		border-radius: 18px;
		padding: 24px;
	}
	.status h2 {
		margin-bottom: 10px;
	}
	.status p {
		margin: 0;
		color: #c9c9d1;
	}

	@media (max-width: 520px) {
		.hero {
			padding-block: 40px 32px;
		}
		.btn {
			width: 100%;
			justify-content: center;
		}
	}
</style>
