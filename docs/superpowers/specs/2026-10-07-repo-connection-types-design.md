# Repo bağlantı türleri: Bitbucket, GitHub, Git — Tasarım Eki

- **Tarih:** 2026-10-07
- **Dayanak:**
  - `2026-10-05-impact-analyzer-design.md`: §3.2 adım 1, §6.2 (`SCM_CONNECTION`), §8.
  - `2026-10-06-web-ui-design.md`: §4.2 satır 10, §4.3.
- **Neden:** Kullanıcı, Bitbucket Data Center dışında GitHub (github.com ve Enterprise) ve düz Git sunucularındaki repoları da taratmak istiyor.

## 1. Kapsam

- Menü ve başlıklarda "SCM bağlantıları" ifadesi **"Repo bağlantıları"** olur. API yolları değişmez (`/api/v1/admin/scm-connections`).
- Bağlantı türleri `BITBUCKET_DC` (mevcut), **`GITHUB`** ve **`GIT`** olur. Arayüzdeki etiketleri sırasıyla "Bitbucket", "GitHub" ve "Git"tir.
- Bağlantının türü yalnızca oluştururken seçilir; kayıtlı bir bağlantının türü değiştirilemez. Değiştirme denemesine backend 400 döner.

## 2. Türe göre alanlar

| Alan | BITBUCKET_DC | GITHUB | GIT |
|---|---|---|---|
| `baseUrl` | Bitbucket adresi | API URL: `https://api.github.com` ya da `https://<host>/api/v3`. Kodda varsayılan değer yok; örnekler yalnızca ipucu olarak gösterilir. | Git sunucusunun adresi (scheme + host + isteğe bağlı yol) |
| `username` | isteğe bağlı | isteğe bağlı (clone için kullanıcı adı; boşsa token yalnız başına kullanılır) | isteğe bağlı |
| `secret` | token | token (zorunlu) | şifre ya da token (isteğe bağlı) |
| `includeProjects` | dahil edilen projeler | **organizasyonlar** (en az 1) | kullanılmaz (boş olmalı) |
| `excludeRepos` | `PROJE/repo` joker deseni | `org/repo` joker deseni | kullanılmaz (boş olmalı) |
| `repositoryUrls` (yeni) | kullanılmaz | kullanılmaz | **clone URL listesi** (en az 1) |

Kurallar:
- **GIT adresleri:** Her `repositoryUrls` öğesi `baseUrl` ile başlamalıdır; origin ve yol öneki birlikte karşılaştırılır. Böylece kayıtlı kimlik bilgisi yalnızca o sunucuya gider. URL'de userinfo, query ya da fragment olmaz; Plan 7'deki URL kuralları geçerlidir.
- **Şifrenin yeniden girilmesi:** `SecretUpdate` kuralı (aynı `baseUrl` + `username` olmadıkça şifre yeniden girilir) ve `baseUrl` değişince repoların pasife alınması (Plan 7) üç türde de geçerlidir.
- **Repo adları:**
  - GitHub'da proje anahtarı `owner.login`, repo adı `name`, clone adresi `clone_url`'dir.
  - GIT'te repo adı yolun son parçasıdır (`.git` atılır). Proje anahtarı, `baseUrl` yolundan sonra kalan önceki parçalardır, `/` ile birleştirilir. Hiç parça kalmazsa proje anahtarı repo adıyla aynıdır.

## 3. Backend

- **Migration (V10):**
  - `scm_connection.type` kısıtı `('BITBUCKET_DC', 'GITHUB', 'GIT')` olur.
  - Yeni kolon `repository_urls CLOB`; satır sonlarıyla ayrılmış adres listesi.
- **`ScmType`:** `GITHUB` ve `GIT` eklenir.
  - `ScmConnection`, `ScmConnectionUpdate` ve `ScmConnectionView` `repositoryUrls` alanını taşır.
  - Doğrulama türe göre yapılır (§2).
- **`GitHubClient` (`ScmClient`):**
  - Organizasyon başına `GET {baseUrl}/orgs/{org}/repos?per_page={scm.page_size}&page=N` çağrılır. Sayfalar `Link: rel="next"` başlığına göre izlenir.
  - Kimlik doğrulama `Authorization: Bearer <token>` ile yapılır. 401/403 `ScmAuthenticationException` fırlatır.
  - 5xx ve G/Ç hatalarında `scm.retry_count` / `scm.retry_backoff` ile yeniden denenir. Zaman aşımları `scm.connect_timeout` / `scm.read_timeout` ayarlarından gelir.
  - Hariç tutma desenleri `org/repo` biçimine uygulanır.
  - `test()`: tek istek (`GET {baseUrl}/orgs/{ilk org}/repos?per_page=1`), yeniden deneme yok.
- **`GitConnectionClient` (`ScmClient`):**
  - `listRepositories`, `repositoryUrls` listesini repo listesine çevirir; ağa çıkmaz.
  - `test()`: ilk adreste `GitWorkspace.remoteHead` (ls-remote) çağrılır. Kimlik doğrulama hatası `ScmAuthenticationException`, diğer hatalar `ScmException` olur.
- **Değişmeyenler:** Klonlama ve tarama türden bağımsızdır: `GitWorkspace` bağlantının `username`/`secret` bilgisiyle HTTP kimlik doğrulaması yapar. GitHub'da kullanıcı adı boşsa, token kullanıcı adı olmadan Basic kimlik doğrulamasıyla gönderilir; JGit için kullanıcı adının nasıl doldurulacağı planda doğrulanır.

## 4. Arayüz

- Menüde, liste ve form başlıklarında, bildirimlerde ve README'de "Repo bağlantıları" geçer.
- Tür seçimi Bitbucket / GitHub / Git'tir. Düzenleme ekranında tür değiştirilemez, yalnızca gösterilir.
- Form türe göre alanları, etiketleri ve ipuçlarını değiştirir (§2):
  - GitHub'da "Dahil edilen projeler" alanının adı "Organizasyonlar" olur.
  - GIT'te bu alan ve "Hariç tutulan repolar" gizlenir; yerine "Repo adresleri" (her satıra bir URL) gelir.
- Listede tür sütunu yeni etiketleri gösterir. GIT bağlantılarında organizasyon ya da proje sütunu yerine adres sayısı gösterilir.

## 5. Test

- **Backend:**
  - `GitHubClient`: bir fake HTTP sunucusuyla sayfalama, `Link` başlığı, 401, 5xx + yeniden deneme ve hariç tutma.
  - `GitConnectionClient`: adres → repo dönüşümü ve test.
  - Doğrulama kuralları: zorunlu alanlar, adreslerin `baseUrl` ile başlaması, tür değiştirmenin reddedilmesi.
  - Migration.
- **Frontend:**
  - Türe göre alanlar ve gönderilen istek gövdesi.
  - Ad değişikliği.
  - Düzenlemede türün değiştirilememesi.

## 6. Ek (2026-10-07): GitHub'da token sahibinin kendi repoları

**Neden:** İlk gerçek denemede kullanıcının repoları bir organizasyonda değil, kişisel hesabında (`zeuseng25`) çıktı. `/orgs/{name}/repos` kişisel hesaplar için 404 döner.

- **Alan:** Yeni `includeOwnRepositories` (boolean) alanı eklenir.
  - Veritabanı: `scm_connection.include_own_repositories NUMBER(1) DEFAULT 0 NOT NULL` (V11).
  - Alan `ScmConnection`, `ScmConnectionUpdate` ve `ScmConnectionView` içinde taşınır.
- **Doğrulama:**
  - GITHUB: en az bir organizasyon **ya da** `includeOwnRepositories = true` gerekir. Token zorunluluğu değişmez.
  - BITBUCKET_DC ve GIT: alan `true` olamaz (400).
- **Listeleme:** `includeOwnRepositories = true` ise `GET {baseUrl}/user/repos?affiliation=owner&visibility=all&per_page={scm.page_size}` çağrılır.
  - Organizasyonlarla aynı kurallar geçerlidir: sayfalar `Link` başlığıyla izlenir, sonraki sayfa linki aynı origin'de olmalıdır, yeniden deneme yapılır, istek limiti ayrı raporlanır, hariç tutma uygulanır, arşivlenmiş ve devre dışı repolar atlanır, `clone_url` kuralları uygulanır.
  - Proje anahtarı `owner.login` olur.
  - Organizasyon listesi de doluysa sonuçlar birleştirilir. Aynı repo iki kez gelirse bir kez sayılır (owner/name, büyük-küçük harf duyarsız).
- **`test()`:**
  - Organizasyon varsa mevcut tek istek kullanılır.
  - Organizasyon yoksa `GET {baseUrl}/user` çağrılır. Bu tek istektir ve yeniden deneme yoktur. 401/403 kimlik hatasıdır; istek limiti kuralı burada da geçerlidir.
- **Arayüz:**
  - Yalnızca GitHub'da "Kendi repolarımı da tara" onay kutusu (Switch) gösterilir.
  - Kutu işaretliyse "Organizasyonlar" alanı zorunlu olmaktan çıkar.
  - Tür değişince kutu temizlenir.
  - İstek gövdesinde diğer türler için `includeOwnRepositories: false` gönderilir.
