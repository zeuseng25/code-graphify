# Impact Analyzer — Web Arayüzü Tasarım Dokümanı

- **Tarih:** 2026-10-06
- **Kapsam:**
  - React web arayüzü: arama, sembol detayı, etki analizi, repolar, tarama çalışmaları, repo grafı ve yönetim ekranları.
  - Arayüzün aynı uygulama içinden sunulması.
  - Uygulamanın WildFly 41'e WAR olarak deploy edilmesi.
- **Dayanak:** Backend `docs/superpowers/specs/2026-10-05-impact-analyzer-design.md` spesifikasyonuna göre tamamlandı (Plan 1–8). Bu doküman o spesifikasyonun §13 maddesi 1'idir.
- **Kapsam dışı:** LLM amaç tahmini ve yanlış kullanım katmanı (ayrı spesifikasyon); mobil uyumluluk ötesi özel tasarım.

---

## 1. Amaç ve başarı kriterleri

### 1.1 Amaç
Backend'in cevapladığı soruları bir geliştiricinin tarayıcıdan, API bilmeden sorabilmesi:
- "X metodu hangi projelerin hangi sınıflarında kullanılıyor?"
- "X'i değiştirirsem kimler etkilenir?"
- "Bu repo nasıl bir yapıda?"

Ayrıca yöneticinin bütün işletim ayarlarını web arayüzünden yönetebilmesi. Ana kural (backend spec §6) geçerli: **kodda sabit değer yok, seçenekler her zaman web arayüzünden gelir.**

### 1.2 Başarı kriterleri
1. **Arama → etki akışı:** Bir kullanıcı metot adını yazar, doğru overload'u seçer ve şunları görür:
   - kaç repoda ve hangi sınıflarda kullanıldığını;
   - etki analizini, giriş noktalarını ve uyarıları;
   - CSV indirebilir.

   Bunların hepsi en fazla 4 ekran geçişiyle olur.
2. **Graf:** Repo grafı modül, paket, sınıf ve metot seviyelerinde gezilebilir. Düğüme tıklayınca bir alt seviyeye inilir. Graf limit yüzünden üst seviyeye toplandıysa kullanıcı bunu görür ve öneriyi okur.
3. **Yönetim:** Backend spec §10.6'daki her yönetim işlemi bir ekrandan yapılabilir. Bunun için veritabanına ya da API'ye elle erişmek gerekmez.
4. **Gizli bilgi:** Hiçbir ekranda gizli bilgi (token, şifre) görüntülenmez, saklanmaz ve URL'ye yazılmaz.
5. **Deploy:** Tek WAR dosyası WildFly 41.0.0.Final'a deploy edilir. Uygulama hangi context path ile deploy edilirse edilsin çalışır.
6. **Sabit metin ve değer yok:** Arayüzde sabit operasyonel değer (sayfa boyutu, limit, yenileme aralığı vb.) bulunmaz. Kullanıcıya görünen bütün metinler tek bir Türkçe metin dosyasından gelir.

---

## 2. Teknoloji

| Alan | Seçim |
|---|---|
| Çalışma ortamı (build) | Node.js 24 LTS, npm 11 |
| Arayüz | React (güncel kararlı sürüm), TypeScript, Vite |
| Bileşenler | Mantine (çekirdek, form, bildirim, modal, tarih) |
| Yönlendirme | React Router |
| Sunucu verisi | TanStack Query |
| API istemcisi | `openapi-typescript` (tip üretimi) + `openapi-fetch` |
| Graf çizimi | Cytoscape.js + `cytoscape-fcose` yerleşim eklentisi |
| Test | Vitest, Testing Library, MSW, `tsc --noEmit`, ESLint |
| Paketleme | `frontend-maven-plugin`, WAR |
| Uygulama sunucusu | WildFly 41.0.0.Final |

**Dil:** Arayüz yalnızca Türkçedir.
- Metinler `frontend/src/i18n/tr.ts` dosyasında toplanır.
- Tarih ve sayı biçimleri `Intl` ile `tr-TR`'dir.
- Backend'in hata mesajları (400/409/502 `detail`) olduğu gibi gösterilir.

**Değerlendirilip seçilmeyenler:**
- **MUI:** Daha ağır, Material görünümüne bağlı ve gelişmiş tablo için ücretli lisans istiyor.
- **shadcn/ui + Tailwind + React Flow:** Tablo ve form altyapısını kendimiz kurmamız gerekir. React Flow, yüzlerce düğümlü otomatik yerleşimde Cytoscape'in gerisinde kalıyor.

---

## 3. Mimari ve paketleme

### 3.1 Repo yapısı
```
graphify/
├─ pom.xml                     (packaging: war)
├─ src/main/java/...           backend (değişmeyen yapı)
├─ src/main/webapp/WEB-INF/jboss-deployment-structure.xml
└─ frontend/                   ayrı Vite projesi
   ├─ package.json, package-lock.json, vite.config.ts, tsconfig.json
   └─ src/ (api, auth, config, components, features, i18n)
```
Arayüz ile backend arasındaki tek sözleşme `/api/v1` ve OpenAPI tanımıdır (`/api/v1/openapi.json`).

### 3.2 Build
- `./mvnw test`, arayüzü derlemez. Backend testleri bugünkü gibi hızlı kalır.
- `./mvnw package`, `prepare-package` fazında `frontend-maven-plugin` ile şunları yapar:
  1. Node 24 ve npm 11'i build dizinine kurar.
  2. `npm ci`
  3. `npm test`
  4. `npm run build`

  Build çıktısı `target/classes/static/` altına kopyalanır.
- **Adresler kodda yok:** Node indirme adresi (`frontend.node.downloadRoot`) ve npm registry adresi (`frontend.npm.registry`) Maven özellikleridir. Kurum içi npm deposu ve Node aynası `~/.m2/settings.xml` profilinden ya da `-D` ile verilir.
- **Eksik adres:** Özellik verilmeden build edilirse, maven-enforcer anlaşılır bir mesajla durdurur.
- **API tipleri:** `frontend/src/api/schema.d.ts`, çalışan backend'in OpenAPI tanımından `npm run generate:api` ile üretilir ve commit edilir. Backend API'si değişince bu komut yeniden çalıştırılır. Tip uyuşmazlığı `tsc` aşamasında build'i kırar.

### 3.3 WildFly 41 için WAR
- **Paketleme:** `pom.xml` içinde `packaging` war olur. `spring-boot-starter-tomcat` `provided` kapsamına alınır.
- **Başlatıcı:** `GraphifyApplication` için bir `SpringBootServletInitializer` eklenir. WAR yerelde yine `java -jar` ile çalışabilir; bu geliştirme kolaylığıdır.
- **Modül dışlama:** `WEB-INF/jboss-deployment-structure.xml`, WildFly'ın JAX-RS, JPA ve Jackson alt sistemlerini ve loglama yapılandırmasını uygulamadan dışlar. Uygulama kendi Spring, Jackson, logback ve ojdbc kütüphanelerini kullanır.
- **Ortam değişkenleri:** Veritabanı ve anahtar ayarları (`DB_URL`, `DB_USER`, `DB_PASSWORD`, `APP_MASTER_KEY`, `APP_BOOTSTRAP_ADMIN_PASSWORD`, `SPRING_PROFILES_ACTIVE`) ortam değişkeni ya da sistem özelliği olarak verilir. WildFly'da bunun yeri `standalone.conf` ya da `standalone.xml`'deki `<system-properties>` bölümüdür.
- **`server.*` ayarları:** Uygulama `server.*` ayarlarına bağlı değildir. Oturum süresi zaten `auth.session_timeout` ayarından kodda verilir.
- **Ön koşul:** WildFly 41'in Jakarta EE 11 (Servlet 6.1) ve JDK 25 desteği ilk planın ilk görevinde duman testiyle doğrulanır. Uyumsuzlukta iş durur ve karar verilir.

### 3.4 Context path ve SPA sunumu
- **Context path kodda yok.** `index.html` bir Spring controller tarafından sunulur. Controller, derlenmiş `index.html` içindeki yer tutucuya çalışma anındaki `request.getContextPath()` değerini `<base href="{contextPath}/">` olarak yazar.
- **Kök yol:** React Router'ın kök yolu (`basename`) ve API çağrılarının kök adresi `document.baseURI`'den türetilir. Vite build'i göreli varlık yolları (`base: './'`) kullanır.
- **Yenileme (fallback):** `/api/` ile başlamayan ve dosya uzantısı içermeyen GET istekleri (`/repositories/12/graph` gibi) `index.html`'i döndürür. `/api/**` bu fallback'e hiç takılmaz; bilinmeyen bir API adresi 404 problem+json döner.

### 3.5 Güvenlik değişiklikleri
- **Anonim erişim:** `SecurityConfiguration` içinde şunlar anonime açılır:
  - `index.html`;
  - `/assets/**` ve kök dizindeki statik dosyalar (favicon vb.);
  - API dışı SPA rotaları (yalnızca GET).

  Bu adresler veri içermez. Bütün veri `/api/**` üzerinden bugünkü rol kurallarıyla gelir.
- **CSP:** Yanıtlara `Content-Security-Policy` eklenir:
  - `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'`
  - `style-src 'unsafe-inline'`, Mantine'in çalışma anındaki stilleri için gereklidir.
- **Değişmeyen kurallar:** Oturum çerezi, `X-XSRF-TOKEN` CSRF modeli ve rol matrisi aynen kalır. Arayüz ve API aynı origin'dedir, CORS gerekmez.

### 3.6 Yeni backend ucu: arayüz ayarları
- **Uç:** `GET /api/v1/ui-config`. Erişim USER; anonim istek 401 alır.
- **Döndürdükleri:** Yalnızca arayüzün ihtiyaç duyduğu, gizli olmayan ayarlar.
  - `api.page_default_size`, `api.page_max_size`
  - `graph.max_nodes`
  - `impact.default_depth`, `impact.max_depth`
  - yeni `ui.poll_interval`
- **Neden ayrı bir uç:** Yönetici ayar listesi (`/admin/settings`) USER'a açık olmadığı için gerekir.
- **Yeni ayar:** `ui.poll_interval` (DURATION, seed `PT5S`, min/max ile). Seed bir Flyway migration'da verilir.

---

## 4. Ekranlar

Yerleşim: sol menü ve üst çubuk.
- Üst çubukta kullanıcı adı, rol, "Şifre değiştir" (yalnızca yerel hesap) ve "Çıkış" vardır.
- ADMIN menüsü USER'a gösterilmez. Bu yalnızca görünüm amaçlıdır; yetki backend'dedir.

### 4.1 Herkes (USER)
| # | Ekran | İçerik | Kullanılan uçlar |
|---|---|---|---|
| 1 | Giriş | Kullanıcı adı ve şifre. Hata türleri: hatalı bilgiler, kilitli hesap, dizin erişilemiyor (backend mesajı). | `/auth/csrf`, `/auth/login`, `/auth/me` |
| 2 | Şifre değiştir | Mevcut ve yeni şifre (yerel hesap) | `/auth/change-password` |
| 3 | Sembol arama (ana sayfa) | Arama kutusu, tür ve repo filtresi. Sonuç satırlarında FQN, imza, origin ve kullanıldığı repo sayısı var; overload'lar ayrı satırlarda. | `/symbols/search` |
| 4 | Sembol detayı | Tanımlar, üst sınıf, üyeler, override'lar. Kullanım özeti ağacı: repo → modül → sınıf, sayılarla. Sayfalı kullanım listesi: dosya, satır, kod parçası, güven, tür; güven, tür ve repo filtreleriyle. "Etki analizi" butonu. | `/symbols/{id}`, `/usages`, `/usages/summary` |
| 5 | Etki analizi | Girdiler: sembol(ler), `changeType`, `depth` (varsayılan ve azami `ui-config`'ten), güven seviyeleri, `includeDispatch`. Sonuç bölümleri aşağıda. | `/impact`, `/impact/export?format=csv` |
| 6 | Repolar | Durum ve metin filtreli sayfalı liste. Detayda modüller, son tarama, tarama geçmişi ve "Grafı göster". | `/repositories`, `/repositories/{id}`, `/repositories/{id}/runs` |
| 7 | Repo grafı | Ayrıntılar §5'te | `/repositories/{id}/graph`, `/graph/report`, `/graph/export` |
| 8 | Tarama çalışmaları | Liste ve detay: repo bazında durumlar, notlar, `inProgress`. Çalışan tarama `ui.poll_interval` aralığıyla yenilenir. | `/index/runs`, `/index/runs/{runId}` |

Etki analizi sonucunda şunlar gösterilir:
- özet ve seviyelere göre düğümler;
- giriş noktaları (HTTP yolu, zamanlanmış iş, dinleyici);
- sürüm uyarıları ve eski indeks (staleness) uyarıları;
- `truncated` bildirimi;
- küçük bir etki grafı (Cytoscape);
- CSV indirme.

### 4.2 Yalnızca ADMIN (Yönetim menüsü)
| # | Ekran | İçerik | Kullanılan uçlar |
|---|---|---|---|
| 9 | Tarama başlat ve iptal | Kapsam: hepsi, bağlantı ya da repo; `force` seçeneği. Çalışan taramayı iptal etme. 409 mesajı gösterilir. | `POST /index/runs`, `/cancel` |
| 10 | SCM bağlantıları | Liste ve form: ad, tür, `baseUrl`, kullanıcı, secret, dahil edilen projeler, hariç tutulan repolar, aktif. Ayrıca test, son test ve son senkronizasyon durumu, silme (409 mesajı). | `/admin/scm-connections/**` |
| 11 | Maven depoları | Liste ve form: ad, URL, kullanıcı, secret, `mirrorOf`, sıra, aktif. Test ve silme. | `/admin/artifact-repositories/**` |
| 12 | Kullanıcılar | Liste, LDAP'ta arayıp ekleme, rol değiştirme, aktif/pasif. Son-admin kuralı mesajı gösterilir. | `/admin/users/**`, `/admin/ldap/users` |
| 13 | LDAP ayarları | Form ve bağlantı testi | `/admin/ldap`, `/admin/ldap/test` |
| 14 | Ayarlar | Tablo: anahtar, değer, tür, açıklama, min/max, son değiştiren. Satır içi düzenleme; 400 sebebi alanın altında gösterilir. | `/admin/settings/**` |
| 15 | Giriş noktası annotation'ları | Tablo; ekleme, düzenleme, açma/kapama, silme | `/admin/entry-point-annotations/**` |
| 16 | Etki kuralları | Usage kind başına `propagates` ve `shownAtLevel1` anahtarları | `/admin/impact-rules/**` |
| 17 | Denetim kaydı | Kullanıcı, işlem ve tarih aralığı filtreli, sayfalı liste | `/admin/audit` |

### 4.3 Gizli bilgi alanları
SCM secret, Maven deposu şifresi ve LDAP bind şifresi için:
- **Gösterim:** Alan, değer yerine "Kayıtlı" ya da "Boş" rozetini gösterir. Değer hiçbir zaman forma doldurulmaz.
- **Gönderim:** Alan boş bırakılırsa `null` gönderilir ve kayıtlı değer korunur. "Temizle" seçilirse `""` gönderilir.
- **Hedef değişince:** Bağlantının hedefi değiştiyse (URL, kullanıcı ya da bindDn) ve kayıtlı bir değer varsa, form şifreyi yeniden ister. Bu, kullanıcıya önceden haber vermek içindir; asıl kural backend'dedir (400 "Re-enter ...").
- **Saklama:** Şifre ve token değerleri localStorage, sessionStorage, URL, log ya da hata raporlarına yazılmaz. Formdan doğrudan isteğe gider.

---

## 5. Repo grafı ekranı

- **Görünüm:** Seviye seçici (Modül / Paket / Sınıf / Metot), "Dış bağımlılıkları göster" anahtarı ve yerleşim seçici.
- **Gezinme ve URL:**
  - Seviye, odak ve dış bağımlılık seçimi URL'de durur: `?level=&focus=&includeExternal=`. Bu yüzden bağlantı olarak paylaşılabilir.
  - Hangi seviye ve odakta olunduğu bir yol çubuğunda (breadcrumb) gösterilir.
  - Düğüme tıklayınca bir alt seviyeye inilir:
    - modül → o modülün paketleri;
    - paket → sınıflar (odak o paket);
    - sınıf → metot seviyesi (odak o sınıf).
- **Görsel kodlama:**
  - **Şekil:** düğüm tipine göre (modül, paket, sınıf, üye, dış repo, dış kütüphane).
  - **Renk:** sınıf düğümlerinde `metrics.communityId` değerine göre.
  - **Boyut:** sınıf düğümlerinde `metrics.dependents` değerine göre.
  - **Giriş noktası:** ayrıca işaretlenir.
  - **Kenar kalınlığı:** ağırlığa göre. Üzerine gelince kullanım türlerinin dağılımı (`kinds`) görünür.
- **Toplanmış graf:** `truncated: true` gelirse grafın üstünde bir uyarı çıkar. Uyarıda backend'in `suggestion` metni ve gösterilen seviye yer alır.
- **Rapor paneli:**
  - kritik sınıflar (tıklanabilir);
  - topluluklar;
  - paket döngüleri;
  - giriş noktası sınıfları;
  - sayılar.

  Analiz eskimişse (`stale: true`) panelde uyarı gösterilir.
- **Dışa aktarma:** GraphML ve JSON indirme, o anki seviye ve odakla.
- **Boyut:** Düğüm sayısı backend'de `graph.max_nodes` ile sınırlıdır. Arayüz ayrı bir sınır koymaz.

---

## 6. Arayüzün iç yapısı

### 6.1 Klasörler (`frontend/src`)
- `api/`:
  - üretilmiş `schema.d.ts`;
  - tek `apiClient`;
  - uç başına TanStack Query hook'ları.

  Ekranlar `fetch` çağırmaz.
- `auth/`:
  - oturum bağlamı (`/auth/me`);
  - giriş ve çıkış;
  - `<RequireAuth>`, `<RequireRole role="ADMIN">`.
- `config/`:
  - `<base href>`'ten türetilen kök yol;
  - `/ui-config`'ten gelen ayarlar (bağlam olarak).
- `features/<ekran>/`: her ekran kendi sayfası, alt bileşenleri ve testleriyle.
- `components/`:
  - sayfalı tablo;
  - güven seviyesi rozeti;
  - hata görünümü;
  - onay modalı;
  - gizli bilgi alanı;
  - durum rozetleri;
  - graf sarmalayıcıları (`RepoGraphView`, `ImpactGraphView`).
- `i18n/tr.ts`: bütün metinler.

### 6.2 API istemcisi
- **Kimlik bilgisi:** İstekler `credentials: 'same-origin'` ile gönderilir.
- **CSRF token'ı:**
  - Değişiklik yapan istekler (POST/PUT/DELETE) `/auth/csrf`'ten alınmış token'ı `X-XSRF-TOKEN` başlığında taşır.
  - Token girişten ve şifre değişiminden sonra yeniden alınır, çünkü backend bu anlarda token'ı yeniler.
  - 403 problem+json yanıtı CSRF kaynaklıysa token bir kez yenilenir ve istek bir kez daha denenir.
- **Hata yanıtları:**
  - 401: oturum bağlamı temizlenir ve giriş ekranına gidilir. Giriş sonrası kalınan adrese dönülür; bu adres yalnızca uygulama içi bir yol olabilir (açık yönlendirme yok).
  - 403: "Bu işlem için yetkiniz yok" gösterilir.
  - 400, 404, 409 ve 502: problem+json'daki `detail` bildirim olarak gösterilir. Form hatalarında ilgili alanın altında gösterilir.
- **Dosya indirme (CSV, GraphML, JSON):** `fetch` ile alınır ve Blob olarak indirilir. Dosya adı `Content-Disposition`'dan okunur.

### 6.3 Durum yönetimi
- Sunucu verisi TanStack Query'dedir.
- Ekran durumu (filtreler, sayfa, seviye, odak) URL parametrelerindedir.
- Global bir store kullanılmaz.

### 6.4 Formlar ve doğrulama
- Mantine Form kullanılır.
- İstemci doğrulaması yalnızca hızlı geri bildirim içindir. Kurallar backend'de tekrar edilmez; zorunlu alan ve sayı biçimi dışındaki kurallar backend'den gelen 400 ile gösterilir.

### 6.5 Tema ve erişilebilirlik
- Mantine varsayılan teması kullanılır; açık ve koyu mod vardır.
- Kurumsal renkler tek bir tema dosyasından değiştirilebilir.
- Klavye ile gezinme ve form etiketleri Mantine bileşenleriyle sağlanır.

---

## 7. Hata ve kenar durumları

| Durum | Davranış |
|---|---|
| Oturum süresi doldu (401) | Giriş ekranı. Girişten sonra kalınan adrese dönülür. |
| Dizin (LDAP) erişilemiyor | Giriş ekranında backend mesajı gösterilir. Yerel hesapla giriş mümkündür. |
| Tarama zaten çalışıyor (409) | Tarama başlatma formunda backend mesajı ve çalışan taramaya bağlantı gösterilir. |
| Bağlantı testi başarısız (502) | Maskelenmiş backend mesajı ve son test durumu gösterilir. |
| Repo henüz taranmadı | Repo detayında ve grafında boş durum ve açıklama gösterilir. |
| Graf toplandı (`truncated`) | Uyarı ve öneri gösterilir; seviye seçici gösterilen seviyeye geçer. |
| Analiz eskimiş (`stale`) | Rapor panelinde uyarı gösterilir. |
| Backend'e ulaşılamıyor | Genel hata görünümü ve "Tekrar dene" düğmesi gösterilir. |

---

## 8. Test stratejisi

| Katman | Araç | Kanıtlanan |
|---|---|---|
| Bileşen/ekran | Vitest + Testing Library + MSW (OpenAPI tiplerine uyan sahte yanıtlar) | Her ekran için: doğru istek, yükleniyor/boş/hata durumları, USER'ın ADMIN öğelerini görmemesi |
| Kritik akışlar | Aynı | 401 yönlendirmesi ve geri dönüş; CSRF yenileyip tekrar deneme; arama → detay → etki → CSV; graf detaya inme ve `truncated` uyarısı; gizli bilgi alanı ve hedef değişince yeniden isteme; ayar 400 mesajı |
| Statik | `tsc --noEmit`, ESLint | API tip uyumu, kod kuralları (`npm test` içinde) |
| Backend: SPA sunumu | MockMvc | `<base href>` context path ile doğru; fallback yalnızca API dışı GET; bilinmeyen API adresi 404; statik dosyalar anonim; CSP başlığı |
| Backend: `ui-config` | MockMvc | USER erişimi, anonim 401, yalnızca izinli ayarlar, `ui.poll_interval` seed'i |
| Paketleme | Maven | WAR'da `static/index.html` ve `WEB-INF/jboss-deployment-structure.xml` var |
| WildFly | `scripts/wildfly-smoke.sh` + README deploy kılavuzu | WildFly 41'e deploy, `/{ctx}/` ve `/{ctx}/api/v1/auth/csrf` yanıtları, giriş |

---

## 9. Teslim planı

1. **Plan 9: Temel.**
   - WAR paketlemesi, `jboss-deployment-structure.xml`, WildFly duman testi. WildFly 41 uyumu ilk görevde doğrulanır.
   - SPA sunumu, güvenlik değişiklikleri, CSP, `ui-config` ve `ui.poll_interval`.
   - `frontend/` iskeleti, Maven entegrasyonu, üretilmiş API istemcisi.
   - Giriş, çıkış ve şifre değiştirme; ana yerleşim ve rol bazlı menü.
2. **Plan 10: Kullanıcı ekranları.** Arama, sembol detayı, etki analizi (graf ve CSV dahil), repolar, tarama çalışmaları.
3. **Plan 11: Repo grafı.** Cytoscape görünümü, gezinme, rapor paneli, dışa aktarma.
4. **Plan 12: Yönetim ekranları.** Tarama başlat ve iptal, SCM bağlantıları, Maven depoları, kullanıcılar, LDAP, ayarlar, annotation'lar ve kurallar, denetim kaydı.

## 10. Ön koşullar
- Build makinesinden kurum içi npm deposuna ve bir Node 24 indirme aynasına erişim olmalı. Adresler Maven özellikleriyle verilir.
- Test ve üretim ortamında WildFly 41.0.0.Final ve JDK 25 kurulu olmalı. Oracle erişimi WildFly sunucusundan sağlanmalı.
