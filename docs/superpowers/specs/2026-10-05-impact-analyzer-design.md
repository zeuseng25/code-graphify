# Impact Analyzer — Tasarım Dokümanı

- **Tarih:** 2026-10-05
- **Durum:** İnceleme bekliyor
- **Kapsam:** Çekirdek backend (repo keşfi, Java indeksleme, depolama, etki analizi, repo graf görünümü, REST API, kimlik doğrulama, yönetim). React arayüzü ve LLM katmanı ayrı spec'lerdir.

---

## 1. Amaç ve başarı kriterleri

### 1.1 Problem
Kurum içi Bitbucket Data Center'da en fazla 300 Java/Maven reposu var. Bir sınıf veya metot değiştirildiğinde hangi projelerin, sınıfların ve metotların etkileneceği bugün bilinmiyor.

### 1.2 Kullanıcı soruları (sistemin cevaplaması gerekenler)
1. "`X` sınıfı/metodu **hangi projenin hangi sınıfında, hangi satırında** kullanılıyor?"
2. "`X` **kaç farklı projede** kullanılıyor?"
3. "`X`'i değiştirirsem **kim etkilenir**?" (doğrudan + dolaylı, projeler arası, etkilenen endpoint/scheduler/listener'lar)
4. Üç kaynak türü için de çalışmalı:
   - Kurum içi ortak kütüphane (ör. `common-utils` jar'ı, Nexus'tan bağımlılık olarak)
   - Projenin kendi içindeki kod ("projemde nerede kullandım?")
   - Üçüncü parti kütüphane (ör. Spring Boot upgrade'inde "`RestTemplate.exchange` nerelerde kullanılmış?")
5. Tek bir repo seçildiğinde o repoyu **graf olarak** göstermek (ekstra özellik, bkz. §9).

### 1.3 Başarı kriterleri
- Overload'lar ayırt edilir (`format(int)` ≠ `format(String)`).
- `var`, zincirleme çağrı (`a().b()`), lambda, method reference, generic, inner/anonim sınıf, static import ve interface dispatch doğru çözülür.
- Üçüncü parti kütüphane çağrıları (classpath'te jar varsa) kesin olarak kaydedilir.
- Her sonuç bir **güven seviyesi** taşır (`EXACT` / `RECOVERED` / `NAME_ONLY`); kullanıcı kesin ve tahmini sonuçları ayırt edebilir.
- Bir repodaki hata diğer repoları ve o reponun önceki geçerli verisini etkilemez.
- İş mantığını etkileyen hiçbir değer kodda sabit değildir; tamamı web arayüzünden yönetilir (§6).

### 1.4 Kapsam dışı (bu spec)
- React web arayüzü (ayrı spec; bu spec API sözleşmesini tanımlar).
- LLM ile "ne için kullanılıyor" tahmini ve yanlış kullanım tespiti (ayrı spec; `USAGE.snippet` bunun için saklanır).
- Varsayılan branch dışındaki branch'ler.
- Java dışı diller; doküman/PDF/video.
- Reflection, Spring XML ve SpEL üzerinden yapılan dinamik çağrılar.
- Bitbucket Cloud ve GitHub istemcileri (arayüz hazır, uygulama sonra).

---

## 2. Arka plan: graphify değerlendirmesi

`graphify-8/` (Python, tree-sitter tabanlı, ~78 bin satır kütüphane) değerlendirildi. Amacı AI asistanlarına kod tabanının yaklaşık haritasını ucuza vermek; bu amaç için iyi, kesin etki analizi için yetersiz.

İki repolu bir Java örneğiyle (aynı corpus'ta, graphify için en iyi durum) yapılan deneyde:

| Çağrı | Sonuç |
|---|---|
| `money.format("10")` | ✅ |
| `money.format(5)` | ⚠️ `format(String)`'e bağlandı; `format(int)` overload'u grafta hiç yok |
| `var m = new MoneyUtil(); m.format(1)` | ❌ kaçırıldı |
| `MoneyUtil.instance().format("x")` | ❌ kaçırıldı |
| `xs.forEach(s -> money.format(s))` | ✅ |
| `date.format("2020")` (aynı isim, farklı sınıf) | ✅ |
| `rest.exchange(...)` (üçüncü parti) | ❌ kenar yok, yalnızca "çözülemedi" notu |

**Karar:** Çıkarma motoru olarak graphify'ı Java'ya çevirmek yerine **Eclipse JDT** kullanılır (gerçek Java derleyici altyapısı; tip, overload ve generic çözümlemesi kesin). graphify'dan alınan fikirler: güven etiketleri, commit bazlı artımlı tarama, etki alanı (blast radius) mantığı, topluluk tespiti, kritik düğümler ve özet rapor (§9).

---

## 3. Mimari

Tek bir Spring Boot uygulaması (`com.graphify`), özelliğe göre paketlenmiş.

```
┌──────────────────────────── impact-analyzer (Spring Boot) ────────────────────────────┐
│  auth/        LDAP + yerel admin girişi, roller, oturum                               │
│  settings/    DB'deki ayarlar, şifreli secret'lar, önbellek, doğrulama, audit         │
│  scm/         SCM istemci arayüzü + Bitbucket DC uygulaması                           │
│  workspace/   git clone / fetch, çalışma dizini yönetimi                              │
│  classpath/   settings.xml üretimi, mvn dependency:build-classpath, düşme modları     │
│  indexer/     JDT ASTParser → sembol + kullanım çıkarımı                              │
│  store/       Oracle erişimi (JPA + JDBC batch / native SQL)                          │
│  impact/      sembol arama, kullanım sorguları, BFS etki analizi                      │
│  repograph/   repo graf seviyeleri, topluluklar, metrikler, döngüler                  │
│  scheduler/   cron'lu tam senkron, manuel tarama, DB tabanlı tarama kilidi            │
│  api/         REST controller'lar, ProblemDetail hata yönetimi, OpenAPI               │
│  common/      ortak exception'lar, yardımcılar                                        │
└───────────────────────────────────────────────────────────────────────────────────────┘
      │ HTTPS + git            │ Maven                │ JDBC              │ LDAP
 Bitbucket Data Center    Nexus/Artifactory        Oracle DB        Active Directory
```

### 3.1 Teknoloji
- Java 25 (JDK 25 kurulumu ön koşul), Spring Boot 4.1.x, Maven.
- Eclipse JDT Core (`ASTParser`), JGraphT, CWTS `networkanalysis` (Leiden; uygun değilse JGraphT label propagation).
- Oracle, Flyway, Spring Data JPA + `JdbcTemplate` batch.
- Spring Security + Spring LDAP.
- springdoc-openapi.
- Test: JUnit 5, Testcontainers (`gvenzl/oracle-free`), WireMock, UnboundID gömülü LDAP.

### 3.2 İndeksleme akışı (repo başına)
1. SCM'den repo listesi + varsayılan branch + son commit hash alınır.
2. Commit `last_indexed_commit` ile aynıysa ve `force` değilse → `SKIPPED_UNCHANGED`.
3. Clone (ilk sefer) veya fetch + hard reset (sonraki).
4. Maven modülleri keşfedilir (`pom.xml` ağacı, `<modules>`); her modül için `MAVEN_MODULE` ve pom'dan `MODULE_DEPENDENCY` kayıtları hazırlanır.
5. Her modül için classpath çözülür → `FULL` / `PARTIAL` / `NONE` (§8).
6. JDT ile parse: `setResolveBindings(true)`, `setBindingsRecovery(true)`, `setEnvironment(classpath, sourcepath, ...)`. Repo içindeki tüm modüllerin kaynak dizinleri sourcepath'e eklenir.
7. Sembol ve kullanımlar çıkarılır (§4.3).
8. Tek transaction'da: repoya ait eski `USAGE` ve `SYMBOL_DECLARATION` kayıtları silinir, yenileri JDBC batch ile yazılır; `SCM_REPOSITORY.last_indexed_commit` güncellenir.
9. Repo graf analizleri hesaplanır ve saklanır (§9).

Paralellik: sabit boyutlu thread havuzu; boyut `APP_SETTING` (`index.parallelism`).

---

## 4. Veri modeli (Oracle)

```
SCM_CONNECTION ──< SCM_REPOSITORY ──< MAVEN_MODULE ──< MODULE_DEPENDENCY
                        │                   │
                        │                   ├──< SYMBOL_DECLARATION >── SYMBOL ──< SYMBOL (parent)
                        │                   │                             ▲   ▲
                        │                   └──< USAGE ── from_symbol ────┘   │
                        │                            └─── to_symbol ──────────┘
                        ├──< INDEX_RUN_REPO >── INDEX_RUN
                        └──< REPO_GRAPH_COMMUNITY / REPO_GRAPH_METRIC / REPO_GRAPH_CYCLE
```

### 4.1 Kod indeksi tabloları

| Tablo | Kolonlar | Not |
|---|---|---|
| `SCM_REPOSITORY` | `id`, `connection_id`, `project_key`, `slug`, `clone_url`, `default_branch`, `last_indexed_commit`, `last_status`, `last_indexed_at`, `active` | SCM'de artık bulunmayan repo `active=0` yapılır, verisi silinir |
| `MAVEN_MODULE` | `id`, `repo_id`, `group_id`, `artifact_id`, `version`, `path`, `classpath_mode` | Multi-module'de her modül ayrı satır |
| `MODULE_DEPENDENCY` | `module_id`, `group_id`, `artifact_id`, `version`, `scope` | pom'dan; sürüm farkı uyarıları için |
| `SYMBOL` | `id`, `symbol_key` (unique), `kind`, `class_fqn`, `member_name`, `display_signature`, `parent_id`, `origin` (`SOURCE`/`BINARY`), `artifact` | Her sembol bir kez |
| `SYMBOL_DECLARATION` | `symbol_id`, `module_id`, `file_path`, `line` | Aynı FQN birden fazla repoda tanımlıysa hepsi görünür |
| `USAGE` | `id`, `from_symbol_id`, `to_symbol_id`, `module_id`, `kind`, `confidence`, `file_path`, `line`, `col`, `snippet` | Çekirdek tablo |
| `INDEX_RUN` | `id`, `trigger` (`SCHEDULED`/`MANUAL`), `scope`, `started_at`, `finished_at`, `status`, `started_by` | Bir tarama çalıştırması |
| `INDEX_RUN_REPO` | `run_id`, `repo_id`, `commit`, `status`, `classpath_mode`, `error`, `symbol_count`, `usage_count`, `warning_count`, `duration_ms` | Repo bazında sonuç |

### 4.2 `symbol_key` formatı
- Sınıf: `com.corp.common.MoneyUtil`
- Inner sınıf: `com.corp.common.MoneyUtil$Rounding`
- Metot: `com.corp.common.MoneyUtil#format(int)` — parametre tipleri tam nitelikli ve erasure uygulanmış (`#process(java.util.List)`)
- Constructor: `com.corp.common.MoneyUtil#<init>(java.lang.String)`
- Alan: `com.corp.common.MoneyUtil.rate`
- Binding'siz (`NAME_ONLY`) metot: `com.corp.common.MoneyUtil#format/1` (isim + argüman sayısı)

Projeler arası bağlantı bu anahtar üzerinden kurulur; ayrı bir birleştirme adımı yoktur.

### 4.3 Enum değerleri
- `SYMBOL.kind`: `CLASS`, `INTERFACE`, `ENUM`, `RECORD`, `ANNOTATION_TYPE`, `METHOD`, `CONSTRUCTOR`, `FIELD`
- `USAGE.kind`: `CALL`, `INSTANTIATION`, `METHOD_REF`, `TYPE_REF`, `EXTENDS`, `IMPLEMENTS`, `OVERRIDES`, `FIELD_READ`, `FIELD_WRITE`, `ANNOTATION`
- `USAGE.confidence`:
  - `EXACT` — classpath tam, binding kesin
  - `RECOVERED` — classpath eksik, JDT binding recovery ile çözüldü
  - `NAME_ONLY` — binding yok; import + isim + argüman sayısı ile eşleşti
- `from_symbol_id`: kullanımın içinde bulunduğu en yakın metot/constructor; alan başlatıcı veya sınıf düzeyindeki kullanımlarda sınıfın kendisi.

### 4.4 Hacim ve indeksler
- Tahmin: 300 repo → 10–20 milyon `USAGE` satırı.
- İndeksler: `USAGE(to_symbol_id)`, `USAGE(from_symbol_id)`, `USAGE(module_id)`, `SYMBOL(class_fqn, member_name)`, `SYMBOL(symbol_key)` unique.
- Gerekirse `USAGE` `module_id` üzerinden partition'lanır.
- Hiçbir `USAGE` veya `SYMBOL_DECLARATION` tarafından referans verilmeyen `SYMBOL` kayıtları periyodik temizlik işiyle silinir.

### 4.5 Yapılandırma, kimlik ve denetim tabloları
§6 ve §7'de tanımlanmıştır: `SCM_CONNECTION`, `ARTIFACT_REPOSITORY`, `APP_SETTING`, `ENTRY_POINT_ANNOTATION`, `IMPACT_RELATION_RULE`, `LDAP_CONFIG`, `LOCAL_ACCOUNT`, `APP_USER`, `AUDIT_LOG`, `INDEX_LOCK`.

---

## 5. Etki analizi

### 5.1 Akış
1. **Sembol seçimi:** Kullanıcı metin arar (`RestTemplate.exchange`); overload'lar ayrı aday olarak listelenir; biri veya tümü seçilir.
2. **Tohum kümesi** (§5.2).
3. **Seviye 1:** Tohumlara doğrudan `USAGE`'lar.
4. **Seviye 2..N:** Kullananların kullananları (BFS).
5. **Özet:** Repo/modül/sınıf/metot sayıları, seviye ve güven dağılımı, giriş noktaları, sürüm uyarıları.

### 5.2 Tohum kümesi kuralları
| Hedef | Tohuma eklenenler |
|---|---|
| Sınıf | Sınıf, tüm metotları/constructor'ları/alanları, alt sınıfları ve implementasyonları |
| Interface / abstract metot | Metot + onu override eden tüm implementasyonlar |
| Somut metot | Metot + override ettiği üst metot(lar); üst metodun çağıranları `via_dispatch=true` ile işaretlenir |
| Hepsi | Aynı sınıf + isim + argüman sayısıyla eşleşen `NAME_ONLY` kullanımlar ("olası" olarak) |

### 5.3 Değişiklik tipi
- **`SIGNATURE`** (parametre/dönüş tipi değişimi, silme, yeniden adlandırma): yalnızca seviye 1 + override'lar — derlenmeyi bırakacak yerler.
- **`BEHAVIOR`** (varsayılan): seviye 1 + geçişli BFS.

### 5.4 Yayılım kuralları
Hangi `USAGE.kind` değerinin etkiyi bir sonraki seviyeye taşıdığı ve hangisinin yalnızca seviye 1'de gösterildiği `IMPACT_RELATION_RULE` tablosundan okunur. Seed değerleri:

| kind | propagates | shown_at_level1 |
|---|---|---|
| `CALL`, `INSTANTIATION`, `METHOD_REF`, `OVERRIDES` | 1 | 1 |
| `EXTENDS`, `IMPLEMENTS` | 1 | 1 |
| `TYPE_REF`, `ANNOTATION`, `FIELD_READ`, `FIELD_WRITE` | 0 | 1 |

### 5.5 Uygulama
- Java'da seviye seviye BFS; her seviye için tek toplu sorgu (`WHERE to_symbol_id IN (...)`, Oracle'ın 1000 elemanlık `IN` sınırı nedeniyle parçalanarak veya geçici tablo ile).
- Ziyaret edilenler kümesi ile döngü kontrolü.
- Derinlik: varsayılan ve maksimum `APP_SETTING`'den (`impact.default_depth`, `impact.max_depth`).
- Sonuç sınırı: `impact.max_results`; aşılırsa `truncated: true`.

### 5.6 Zenginleştirmeler
- **Giriş noktaları:** BFS'in ulaştığı bir metotta `ENTRY_POINT_ANNOTATION` tablosundaki bir annotation varsa raporlanır (`order-service POST /orders`). Mapping path'leri sınıf + metot annotation değerlerinin birleşiminden okunur.
- **Sürüm farkı:** Etkilenen modülün `MODULE_DEPENDENCY`'deki sürümü, hedef sembolün tanımlandığı modülün sürümünden farklıysa uyarı.
- **Güven dağılımı** ve classpath'i `PARTIAL`/`NONE` olan etkilenmiş repoların listesi.
- **Veri tazeliği:** Her etkilenen repo için `last_indexed_commit` ve `last_indexed_at`.

---

## 6. Yapılandırma yönetimi ("kodda sabit değer yok")

### 6.1 İlke
İş mantığını etkileyen her değer veritabanındadır ve web arayüzünden yönetilir. Kod ve `application.yml` yalnızca ortam değişkeni referansları içerir.

Ortam değişkeninden gelen (arayüzden gelemeyecek) değerler:

| Değişken | Amaç |
|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | Oracle bağlantısı (ayarlar DB'de olduğu için önce DB'ye bağlanmak gerekir) |
| `APP_MASTER_KEY` | Secret'ları şifreleyen anahtar (şifreli verinin yanında durmamalı) |
| `APP_BOOTSTRAP_ADMIN_PASSWORD` | Opsiyonel; ilk admin şifresi (§7.3) |

### 6.2 Tablolar
| Tablo | Kolonlar |
|---|---|
| `SCM_CONNECTION` | `id`, `name`, `type` (`BITBUCKET_DC`), `base_url`, `username`, `secret_enc`, `include_projects`, `exclude_repos`, `enabled`, `last_test_status`, `last_test_at` |
| `ARTIFACT_REPOSITORY` | `id`, `name`, `url`, `username`, `secret_enc`, `mirror_of`, `sort_order`, `enabled` |
| `APP_SETTING` | `key`, `value`, `type` (`INT`/`STRING`/`BOOL`/`CRON`/`LIST`/`DURATION`), `description`, `min_value`, `max_value`, `updated_by`, `updated_at` |
| `ENTRY_POINT_ANNOTATION` | `id`, `annotation_fqn`, `label`, `enabled` |
| `IMPACT_RELATION_RULE` | `usage_kind`, `propagates`, `shown_at_level1` |

### 6.3 `APP_SETTING` anahtarları (seed değerleri Flyway migration'da)
| Anahtar | Tip | Seed |
|---|---|---|
| `index.cron` | CRON | `0 0 2 * * *` |
| `index.parallelism` | INT | 4 |
| `index.workspace_dir` | STRING | `/data/impact-analyzer/repos` |
| `index.parse_batch_size` | INT | 500 |
| `index.maven_timeout` | DURATION | `PT10M` |
| `index.maven_output_tail_lines` | INT | 50 |
| `scm.retry_count` | INT | 3 |
| `scm.retry_backoff` | DURATION | `PT2S` |
| `scm.page_size` | INT | 100 |
| `impact.default_depth` | INT | 3 |
| `impact.max_depth` | INT | 10 |
| `impact.max_results` | INT | 5000 |
| `usage.snippet_max_length` | INT | 500 |
| `api.page_default_size` | INT | 50 |
| `api.page_max_size` | INT | 500 |
| `graph.max_nodes` | INT | 500 |
| `graph.community_seed` | INT | 42 |
| `auth.session_timeout` | DURATION | `PT8H` |
| `auth.max_failed_attempts` | INT | 5 |
| `auth.lock_duration` | DURATION | `PT15M` |
| `cleanup.orphan_symbols_cron` | CRON | `0 0 4 * * SUN` |

`ENTRY_POINT_ANNOTATION` seed'i: Spring `@RequestMapping`, `@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping`, `@PatchMapping`, `@Scheduled`, `@KafkaListener`, `@JmsListener`, `@RabbitListener`, `@EventListener`.

### 6.4 Kurallar
- Secret'lar AES-GCM ile `APP_MASTER_KEY` kullanılarak şifrelenir; API asla döndürmez (`secretSet: true`).
- `PUT` isteğinde `secret` alanı yoksa/`null` ise mevcut değer korunur.
- Ayarlar önbelleklenir; değişince önbellek geçersiz olur. Cron değişince zamanlayıcı yeniden kurulur; paralellik bir sonraki taramada geçerli olur. Yeniden başlatma gerekmez.
- Tip ve `min`/`max` doğrulaması backend'de; geçersiz cron/URL kaydedilemez.
- `settings.xml` her taramada `ARTIFACT_REPOSITORY`'den geçici bir dosyaya (izinler 600) üretilir ve tarama sonunda silinir.
- Her değişiklik `AUDIT_LOG`'a yazılır; secret değerleri yazılmaz.

---

## 7. Kimlik doğrulama ve yetkilendirme

### 7.1 Model
- **Kimlik doğrulama:** Önce `LOCAL_ACCOUNT`, eşleşme yoksa LDAP bind.
- **Roller** uygulamanın kendi `APP_USER` tablosundan gelir; AD grupları kullanılmaz.
- İlk kez LDAP ile giren kullanıcı otomatik olarak `USER` rolüyle `APP_USER`'a eklenir.

### 7.2 Tablolar
| Tablo | Kolonlar |
|---|---|
| `LDAP_CONFIG` | `url`, `base_dn`, `user_search_base`, `user_search_filter`, `bind_dn`, `bind_password_enc`, `display_name_attr`, `email_attr`, `enabled` |
| `LOCAL_ACCOUNT` | `username`, `password_hash` (bcrypt), `must_change_password`, `enabled`, `failed_attempts`, `locked_until` |
| `APP_USER` | `id`, `username`, `source` (`LOCAL`/`LDAP`), `display_name`, `email`, `role` (`ADMIN`/`USER`), `role_granted_by`, `role_granted_at`, `last_login_at`, `active` |
| `AUDIT_LOG` | `id`, `actor`, `action`, `target`, `details`, `at` |

### 7.3 İlk kurulum
1. İlk açılışta `LOCAL_ACCOUNT`'ta hesap yoksa `admin` yerel hesabı `ADMIN` rolüyle oluşturulur. Şifre `APP_BOOTSTRAP_ADMIN_PASSWORD`'den alınır; yoksa rastgele üretilip **yalnızca bir kez** loga yazılır. `must_change_password=1`.
2. İlk girişte şifre değişikliği zorunlu.
3. Admin LDAP, Bitbucket ve Maven ayarlarını girer.
4. Admin, LDAP'ta kullanıcı arayıp istediği kişilere `ADMIN` rolü verir (sınırsız sayıda).

### 7.4 Kurallar
- Sistemde en az bir aktif `ADMIN` kalmalıdır; son admin'in rolü düşürülemez/pasifleştirilemez (`409`).
- Yerel admin acil durum hesabıdır; en az bir aktif LDAP admin varken pasifleştirilebilir.
- Başarısız giriş limiti ve kilit süresi `APP_SETTING`'den.
- Sunucu tarafı oturum, HttpOnly cookie, CSRF koruması; React aynı origin'den servis edilir.

### 7.5 Yetki matrisi
| Yetenek | USER | ADMIN |
|---|---|---|
| Arama, kullanım listesi, etki analizi, export | ✅ | ✅ |
| Repo listesi, tarama durumu/geçmişi, repo grafı | ✅ | ✅ |
| Tarama başlatma/iptal | ❌ | ✅ |
| SCM/Maven/LDAP bağlantıları, ayarlar, kurallar | ❌ | ✅ |
| Kullanıcı rolleri, audit log | ❌ | ✅ |

---

## 8. Hata yönetimi

**İlke:** Bir repodaki hata taramayı durdurmaz ve o reponun önceki geçerli verisini silmez.

| Aşama | Hata | Davranış |
|---|---|---|
| SCM listeleme | 401/403 | Bağlantı `AUTH_FAILED`; diğer bağlantılar devam eder |
| SCM listeleme | 5xx / zaman aşımı | `scm.retry_count` ve `scm.retry_backoff` ile üstel yeniden deneme |
| Clone/fetch | Ağ / yetki | `CLONE_FAILED`; eski indeks korunur |
| Clone/fetch | Bozuk çalışma dizini | Dizin silinip bir kez yeniden klonlanır |
| Proje tipi | `pom.xml` yok, `.java` var | `NONE` classpath ile indekslenir |
| Proje tipi | `.java` yok | `SKIPPED_NOT_JAVA` |
| Classpath | `mvn` hata / zaman aşımı | Modül bazında `PARTIAL`/`NONE`; Maven çıktısının son N satırı `INDEX_RUN_REPO.error`'a |
| Parse | Dosya parse edilemiyor | Dosya atlanır, `warning_count` artar |
| Bellek | Büyük modül | `index.parse_batch_size` ile gruplanarak parse |
| Yazma | DB hatası | Repo transaction'ı geri alınır; eski veri korunur |
| Eşzamanlılık | İkinci tarama isteği | `INDEX_LOCK` tablosu ile tek tarama; ikinci istek `409` |
| Yeniden başlatma | Yarım kalan tarama | Açılışta `INTERRUPTED` işaretlenir; kilit serbest bırakılır |

**Repo durumları:** `SUCCESS`, `SUCCESS_PARTIAL`, `FAILED`, `CLONE_FAILED`, `SKIPPED_UNCHANGED`, `SKIPPED_NOT_JAVA`, `INTERRUPTED`.

**Güvenlik:**
- Log ve hata mesajlarında URL içi kimlik bilgileri ve token'lar maskelenir.
- Maven yalnızca `dependency:build-classpath` hedefiyle, `-B`, `-DskipTests`, üretilen `settings.xml` ile ve kısıtlı yetkili bir kullanıcıyla çalıştırılır. Pom'daki build extension'larının kod çalıştırabilmesi kabul edilmiş bir risktir (repolar kurum içidir).

**API hataları:** RFC 7807 `ProblemDetail`. `400` doğrulama, `401`/`403`, `404`, `409` iş kuralı, `502` dış sistem (mesaj secret içermeyecek şekilde temizlenir).

---

## 9. Repo graf görünümü (ekstra özellik)

### 9.1 Seviyeler
| Seviye | Düğüm | Kenar |
|---|---|---|
| `MODULE` | Maven modülü | Modüller arası kullanım sayısı |
| `PACKAGE` (varsayılan) | Java paketi | Paketler arası ağırlıklı kullanım |
| `CLASS` | Sınıf/interface/enum/record | Ağırlıklı `USAGE` kenarları (kind bazında) |
| `METHOD` | Seçilen sınıfın metotları + komşuları | Çağrılar |

- Düğüm sayısı `graph.max_nodes`'u aşarsa bir üst seviyeye toplanır; `truncated: true` ve detaya inme önerisi döner.
- `includeExternal=true` ile diğer kurum repoları ve üçüncü parti kütüphaneler gruplanmış dış düğümler olarak gösterilir.
- Toplanmış graf saklanmaz; istek anında `USAGE` üzerinden `GROUP BY` ile üretilir.

### 9.2 Analizler (indeksleme sonrası hesaplanır ve saklanır)
| Analiz | Yöntem |
|---|---|
| Topluluklar | Sınıf grafında Leiden (`graph.community_seed` ile deterministik); etiket = topluluktaki en baskın paket adı; bu paketi birden çok topluluk paylaşıyorsa etikete ` · ` ve topluluğun en çok kullanılan class'ının bu pakete göre adı (`x.Foo`) eklenir |
| Kritik sınıflar | Giriş/çıkış derecesi ve repo içi bağımlı sayısı |
| Döngüsel bağımlılıklar | Paket grafında güçlü bağlı bileşenler (JGraphT) |
| Giriş noktaları | `ENTRY_POINT_ANNOTATION` |

### 9.3 Tablolar
| Tablo | Kolonlar |
|---|---|
| `REPO_GRAPH_COMMUNITY` | `repo_id`, `commit`, `symbol_id`, `community_id`, `community_label` |
| `REPO_GRAPH_METRIC` | `repo_id`, `symbol_id`, `in_degree`, `out_degree`, `dependents`, `is_entry_point` |
| `REPO_GRAPH_CYCLE` | `repo_id`, `cycle_id`, `package_name` |

---

## 10. REST API

### 10.1 Genel
- Ön ek `/api/v1`, JSON, RFC 7807 hatalar.
- Sayfalama `?page=&size=&sort=`; varsayılan/maks `APP_SETTING`'den.
- OpenAPI: `/api/v1/openapi.json` (React istemcisi buradan üretilir).
- Uzun işler asenkron: `202 Accepted` + `runId`.

### 10.2 Oturum
| Metot | Yol | Erişim |
|---|---|---|
| POST | `/auth/login` | herkes |
| POST | `/auth/logout` | giriş yapmış |
| GET | `/auth/me` | giriş yapmış |
| POST | `/auth/change-password` | yerel hesap |

### 10.3 Arama ve kullanım (USER)
| Metot | Yol | Açıklama |
|---|---|---|
| GET | `/symbols/search?q=&kind=&repo=` | Adaylar: FQN, imza, origin, kullanıldığı repo sayısı |
| GET | `/symbols/{id}` | Tanımlar, üst sınıf, üyeler, override ilişkileri |
| GET | `/symbols/{id}/usages?confidence=&kind=&repo=` | Seviye 1 kullanımlar, sayfalı |
| GET | `/symbols/{id}/usages/summary` | Repo → modül → sınıf ağacı ve sayılar |

### 10.4 Etki analizi (USER)
| Metot | Yol | Açıklama |
|---|---|---|
| POST | `/impact` | Gövde: `{symbolIds[], changeType, depth, confidences[], includeDispatch}`. Yanıt: `summary`, `levels[]`, `nodes[]`, `edges[]`, `entryPoints[]`, `versionWarnings[]`, `staleness[]`, `truncated` |
| POST | `/impact/export?format=csv` | Aynı gövde, CSV çıktı |

### 10.5 Repo, tarama ve graf
| Metot | Yol | Rol |
|---|---|---|
| GET | `/repositories?status=&q=` | USER |
| GET | `/repositories/{id}` | USER |
| GET | `/repositories/{id}/runs` | USER |
| GET | `/repositories/{id}/graph?level=&focus=&includeExternal=` | USER |
| GET | `/repositories/{id}/graph/report` | USER |
| GET | `/repositories/{id}/graph/export?format=graphml\|json` | USER |
| POST | `/index/runs` — `{scope: ALL\|CONNECTION\|REPOSITORY, id?, force}` | ADMIN |
| GET | `/index/runs/{runId}` | USER |
| POST | `/index/runs/{runId}/cancel` | ADMIN |

### 10.6 Yönetim (ADMIN)
| Kaynak | Uç noktalar |
|---|---|
| SCM bağlantıları | `GET/POST /admin/scm-connections`, `GET/PUT/DELETE /admin/scm-connections/{id}`, `POST /admin/scm-connections/{id}/test` |
| Maven depoları | `GET/POST /admin/artifact-repositories`, `GET/PUT/DELETE /admin/artifact-repositories/{id}`, `POST /admin/artifact-repositories/{id}/test` |
| LDAP | `GET/PUT /admin/ldap`, `POST /admin/ldap/test`, `GET /admin/ldap/users?q=` |
| Ayarlar | `GET /admin/settings`, `PUT /admin/settings/{key}` |
| Giriş noktası annotation'ları | `GET/POST /admin/entry-point-annotations`, `PUT/DELETE /admin/entry-point-annotations/{id}` |
| Etki kuralları | `GET /admin/impact-rules`, `PUT /admin/impact-rules/{kind}` |
| Kullanıcılar | `GET /admin/users`, `PUT /admin/users/{id}/role`, `PUT /admin/users/{id}/active` |
| Audit | `GET /admin/audit?actor=&action=&from=&to=` |

---

## 11. Test stratejisi (TDD)

| Katman | Araç | Kanıtlanan |
|---|---|---|
| JDT indeksleyici | `src/test/resources/fixtures/` Java projeleri | §1.3'teki tüm vakalar; jar classpath'teyken `EXACT`, yokken `RECOVERED`/`NAME_ONLY`; `symbol_key` formatı |
| Etki BFS | Bellek içi sahte repository | Döngü, derinlik, `truncated`, dispatch, `NAME_ONLY`, kural aç/kapa, giriş noktası, `SIGNATURE` vs `BEHAVIOR` |
| Repo graf | Fixture repo | Seviye toplama, `max_nodes` toplanması, bilinen döngü tespiti, sabit seed ile deterministik topluluklar |
| Oracle + migration | Testcontainers `gvenzl/oracle-free` | Flyway, seed verisi, native sorgular, batch yazma, `IN` parçalama |
| Bitbucket DC istemcisi | WireMock | Sayfalama (`isLastPage`/`nextPageStart`), 401, 5xx + yeniden deneme |
| Maven classpath | Fixture proje + dosya tabanlı yerel Maven deposu | Başarı, düşme modları, zaman aşımı |
| LDAP / auth | UnboundID gömülü LDAP | Giriş, arama, yerel admin önceliği, kilitlenme, bootstrap |
| Güvenlik | MockMvc | Rol matrisi, son admin kuralı, yanıtlarda secret olmaması, CSRF |
| Ayarlar | Birim + MockMvc | Tip/min/max doğrulaması, önbellek geçersizleştirme, secret koruma |
| **Uçtan uca kabul** | Yerel bare git repolar (`common-lib`, `order-service`) + WireMock Bitbucket + `file://` clone URL | Tarama → arama → etki; graphify deneyindeki (§2) tüm vakaların doğru bulunması |

---

## 12. Ön koşullar
1. **JDK 25** kurulumu (`brew install openjdk@25` veya eşdeğeri); `pom.xml` `java.version=25` olarak kalır.
2. **Docker** (veya Colima/Podman) — Testcontainers için.
3. Hedef ortamda: Oracle şeması, Bitbucket DC'ye ve Nexus'a ağ erişimi, `mvn` ve `git` kurulu, çalışma dizini için disk alanı (300 repo için tahmini birkaç on GB).

## 13. Sonraki fazlar (ayrı spec'ler)
1. React web arayüzü (arama, etki raporu, repo grafı, yönetim ekranları).
2. LLM katmanı: kullanım amacı tahmini ve yanlış kullanım tespiti (`USAGE.snippet` + çevre kod).
3. Ek SCM'ler (GitHub Enterprise, Bitbucket Cloud) ve branch desteği.
