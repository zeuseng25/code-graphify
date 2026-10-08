# Taranan repoların artifact'leri ve alt klasördeki Maven projeleri — Tasarım

- **Tarih:** 2026-10-08
- **Dayanak:** `2026-10-05-impact-analyzer-design.md` §3.2 (indeksleme akışı), §6 (şema).
- **Neden:** İlk gerçek GitHub taramasında iki sorun görüldü.
  - `zeus-sample-*` repolarının parent POM'ları (`com.zeus:*-parent:2.0.0-SNAPSHOT`) başka bir taranan repoda (`zeus-fw`) tanımlı. Hiçbir Maven deposunda yayınlanmadıkları için classpath çözülemedi.
  - `api-gateway-service` ve `Jwt-Authentication-with-Spring-Boot-3.1` repolarında kökte `pom.xml` yok, bu yüzden classpath hiç çözülmedi.

## 1. Kapsam

1. Bir taranan reponun ürettiği artifact'ler (parent POM, BOM, jar), başka bir taranan repo classpath'ini çözmeden önce graphify'ın yerel Maven deposuna (`index.maven_local_repository`) kurulur. Kurulum, bağımlılık sırasına göre `install` ile yapılır.
2. Kökte `pom.xml` olmayan repolarda alt klasörlerdeki bağımsız Maven projeleri bulunur ve her biri için classpath ayrı çözülür.

Kapsam dışı:
- Gradle;
- kurulan artifact'lerin uzak bir depoya yayınlanması;
- testlerin çalıştırılması.

## 2. Tanımlar

- **Proje kökü:** Kendi Maven reactor'ü çalıştırılan bir `pom.xml` klasörü. Bir repoda bir ya da daha fazla proje kökü olabilir.
- **Modül:** Bugünkü `maven_module` satırı. `path` repoya göredir; koordinatları `groupId:artifactId:version` (GAV) ve `packaging` alanlarıdır.
- **Sağlayıcı ve tüketici:** Repo B'deki bir modülün parent'ı, `dependencyManagement` içindeki `import` kapsamlı BOM'u ya da bir bağımlılığı, repo A'daki bir modülün GAV'ıyla **tam** eşleşiyorsa A sağlayıcı, B tüketicidir. Bir repo kendisine sağlayıcı sayılmaz; aynı reactor'deki modüller bugün de birbirini görüyor.

## 3. Tarama akışı

`IndexRunExecutor` taramayı üç aşamada yürütür.

### 3.1 Hazırlık (paralel, `index.parallelism`)

- Her repo için bugünkü `ls-remote` ve checkout yapılır, ardından `MavenProjectReader` ile projeler okunur (§5).
- "Değişmedi, atla" (SKIPPED_UNCHANGED) kararı bu aşamada uygulanmaz, yalnızca hesaplanıp not edilir. Projelerin okunabilmesi için checkout gerekir.
  - Mevcut ölçüm: değişmemiş repo checkout'u yalnızca `fetch` ve `reset`, yani ucuzdur.
- Okunan modül koordinatları bellekte tutulur. Veritabanına bugünkü gibi indeksleme sonunda yazılır.

### 3.2 Sağlayıcıları kur

**1. Eşleştirme.**
- Taramadaki her tüketici modülün parent, BOM import ve bağımlılık koordinatları iki kaynakla karşılaştırılır:
  - taramadaki repoların hazırlıkta okunan modülleri;
  - taramada olmayan aktif repoların veritabanındaki `maven_module` satırları.
- Karşılaştırma tam GAV eşleşmesiyle yapılır. Sürümü çözülemeyen (`null`) koordinatlar eşleşmez.
- **Taramada olmayan sağlayıcı:** Son indekslenen commit'i checkout edilir ve projeleri okunur. Yalnızca artifact'i yerel depoda yoksa kurulur (aşağıdaki "Atlama" maddesi).

**2. Sıralama.**
- Sağlayıcı repolar bağımlılık grafiğine göre katmanlara ayrılır. Kahn algoritması kullanılır; aynı katmanın içi `project_key, slug` sırasıyla dizilir.
- Döngüdeki repolar tek bir son katmanda kurulmaya çalışılır. Her biri için "döngüsel bağımlılık" uyarısı kaydedilir.

**3. Kurulum.**
- Katmanlar sırayla işlenir; bir katmanın içi `index.parallelism` kadar paralel çalışır.
- Bir sağlayıcı repo, **yalnızca tüketilen modüllerini içeren proje kök(ler)inde** kurulur. Komut:
  ```
  <index.maven_executable> -B -q -fae -Dmaven.test.skip=true
    -s <geçici settings.xml> -Dmaven.repo.local=<index.maven_local_repository> install
  ```
  - Çalışma dizini proje köküdür.
  - Ortam, bugünkü `ClasspathResolver.childEnvironment` ile aynıdır.
  - Zaman aşımı `index.maven_timeout` ayarından gelir. Süre dolarsa süreç ağacı öldürülür.
  - Çıktının son `index.maven_output_tail_lines` satırı saklanır.
- **Atlama:** Sağlayıcı reponun commit'i son başarılı kurulumla aynıysa ve tüketilen bütün artifact'leri yerel depoda varsa kurulum yapılmaz; durum `UP_TO_DATE` olur.
  - Yerel depoda artifact varlığı `<repo>/<groupPath>/<artifactId>/<version>/<artifactId>-<version>.pom` dosyasıyla kontrol edilir. `pom` dışındaki paketlemeler için `.jar` dosyası da aranır.
  - Son başarılı kurulumun commit'i `scm_repository.last_installed_commit` kolonunda tutulur (§6).
- **Hata:** Kurulumu başarısız olan sağlayıcının durumu `FAILED` olur. Tarama durmaz:
  - Sağlayıcı yine kendi indekslemesine girer.
  - Tüketicileri bugünkü gibi çözülmeye çalışılır ve kısmi kalabilir.
  - Hata metni kaydedilir.

### 3.3 İndeksleme (paralel)

- Bugünkü `RepositoryIndexer` adımları çalışır: classpath çözülür, kod parse edilir, sonuç yazılır ve graf analizi yapılır.
- Hazırlıkta "değişmedi" diye not edilen ve `SUCCESS_PARTIAL` olmayan repolar `SKIPPED_UNCHANGED` olur. Bugünkü kural aynen korunur.
- **İstisna:** Sağlayıcılarından biri bu taramada `INSTALLED` olan bir tüketici, değişmemiş olsa bile yeniden indekslenir. Sebebi, classpath'inin değişmiş olabilmesidir.

## 4. Görünürlük

- `index_run_repo` tablosuna iki kolon eklenir (§6):
  - `artifact_install`: `INSTALLED`, `UP_TO_DATE`, `FAILED`, `CYCLE_FAILED` ya da NULL. NULL, reponun sağlayıcı olmadığı anlamına gelir.
  - `artifact_install_error`: hata çıktısının son satırları, maskelenmiş olarak.
- Taramada olmayan bir sağlayıcı kurulursa onun için de bir `index_run_repo` satırı açılır:
  - `status = SKIPPED_UNCHANGED`;
  - `artifact_install` kurulum sonucuyla doldurulur.
  - Böylece admin, taramada hangi repoların kurulduğunu görür.
- **API:** Tarama detayındaki her repo satırı bu iki alanı taşır.
- **Arayüz** (tarama detayında, her repo satırında):
  - "Kuruldu" / "Güncel" / "Kurulum başarısız" / "Döngüsel bağımlılık" rozeti;
  - hata varsa açılabilir hata metni.
  - Metinler `tr.ts` içinde tutulur; yazılım terimleri İngilizce kalır (artifact, install).

## 5. Alt klasördeki projeler

- `MavenProjectReader.read(checkout, sourceRoots)` iki durumu ayırır:
  - **Kökte `pom.xml` var:** Davranış bugünküyle aynıdır. Tek proje kökü `.`'dır.
  - **Kökte `pom.xml` yok:** Klasör ağacında `pom.xml` aranır.
    - Arama derinliği `index.pom_search_depth` ayarından gelir (yeni, INTEGER, varsayılan 3, min 1, max 10).
    - `.git`, `target`, `node_modules`, `build` ve `.` ile başlayan klasörler atlanır. Sembolik bağlantılar izlenmez.
    - Bulunan POM'lar en sığdan en derine işlenir. Daha önce bulunmuş bir proje kökünün `<modules>` ağacında yer alan POM ayrı kök sayılmaz; diğerleri ayrı proje kökü olur.
    - Hiç POM bulunmazsa bugünkü "POM yok" davranışı (`.` modülü, classpath yok) korunur.
- **Proje sonucu:** `MavenProject` artık proje köklerinin listesini taşır. Modüllerin `path` değeri repoya göredir, örneğin `payment-service` ya da `Jwt Authentication with Spring Boot 3.1`. Aynı repoda iki modülün `path` değeri hiçbir zaman çakışmaz.
- **Classpath çözümü:** `ClasspathResolver.resolve` her proje kökü için bugünkü komutu o kökün klasöründe ayrı çalıştırır. Kökler sırayla işlenir; paralellik repo düzeyinde kalır. Hatalar kök başına toplanır.
- **Durum:** `SUCCESS` ve `SUCCESS_PARTIAL` kuralı değişmez. Java kaynağı olan bütün modüller classpath aldıysa sonuç `SUCCESS` olur.

## 6. Şema (V13)

- `scm_repository.last_installed_commit VARCHAR2(64)`, NULL olabilir.
- `index_run_repo.artifact_install VARCHAR2(20)`, NULL olabilir. CHECK: `INSTALLED`, `UP_TO_DATE`, `FAILED`, `CYCLE_FAILED`.
- `index_run_repo.artifact_install_error CLOB`, NULL olabilir.
- `maven_module.packaging VARCHAR2(40)`, NULL olabilir. Okuyucu bu değeri doldurur; değer yoksa `jar` kabul edilir.
- `app_setting` tohumu: `index.pom_search_depth` = 3, INTEGER, min 1, max 10, açıklaması "Kökte pom.xml yoksa alt klasörlerde aranacak en fazla derinlik".

## 7. Güvenlik ve maliyet

- **Güven modeli:** `install`, repoların build plugin'lerini (`compile`, `package`, `install` aşamaları) bugünkü `compile` + `maven.main.skip` çalıştırmasından daha kapsamlı çalıştırır. Repoları admin seçtiği için güven modeli değişmez.
  - Testler çalışmaz (`maven.test.skip`).
  - Ortam kısıtlıdır; `APP_MASTER_KEY` ve DB şifresi görünmez.
  - `settings.xml`, bugünkü geçici, sahibine özel dosyadır.
- **Maliyet:** Yalnızca başka bir taranan repoya artifact sağlayan repolar derlenir. Diğerlerinin süresi değişmez.
  - **Paylaşılan yerel depo:** Kurulumlar katman sırasıyla yapılır ve indeksleme aşamasından önce biter. İndeksleme aşaması yerel depoya yalnızca okumak için erişir; yalnızca dış bağımlılıkları indirir.

## 8. Test

- **Backend:** Gerçek Maven ile, `MavenFixtures` / `ShopScm` tarzı dosya tabanlı fixture'lar.
  - Sağlayıcı yalnızca parent POM veriyor; tüketici classpath alır ve sonuç SUCCESS olur.
  - Sağlayıcı bir jar veriyor; tüketicinin kullanımı EXACT olarak bağlanır.
  - Sağlayıcı taramada değil (REPOSITORY scope) ve artifact yerel depoda yok; sağlayıcı kurulur.
  - Sağlayıcı değişmemiş ve artifact var; `UP_TO_DATE` olur, Maven çalışmaz.
  - Sağlayıcının artifact'i yerel depodan silinmiş; değişmemiş olsa bile yeniden kurulur.
  - Sağlayıcının kurulumu başarısız; sağlayıcı yine indekslenir, tüketici kısmi kalır ve hata kaydedilir.
  - Döngü; `CYCLE_FAILED` ya da `INSTALLED` olur ve uyarı yazılır.
  - Tüketici değişmemiş ama sağlayıcısı bu taramada kuruldu; tüketici yeniden indekslenir.
  - **Alt klasör:** kökte POM yok ve iki bağımsız alt proje var (biri boşluklu bir klasörde); ikisi de classpath alır. Derinlik sınırı ve atlanan klasörler test edilir. Bir alt projenin modülü ayrı kök sayılmaz.
- **Frontend:** Rozetler ve hata metni; API tiplerinin güncellenmesi.

## 9. Ek (uygulama sırasında, Plan 16 Task 3 incelemesi)

- **Sağlayıcı ilişkisi proje kökleri arasında kurulur.** Yalnızca aynı proje kökünün (aynı reactor'ün) içindeki referanslar hariç tutulur.
  - Bir reponun bir kökü, aynı reponun başka bir kökündeki bir koordinatı kullanıyorsa repo kendi kökü için bir kurulum alır. Bu "öz-kurulum", repo indekslenmeden önce yapılır.
  - Repo, `providersOf` içinde kendi sağlayıcısı olarak yer almaz.
- **Kurulacak kökler sıralıdır.** Sağlayıcının kurulacak kökleri, aynı repo içindeki kökler arası referansların kapanışıdır ve ihtiyaç duyulan kök önce gelecek şekilde sıralanır. Kurulum bu sırayla yapılır.
- **Döngüler.** Döngüler güçlü bağlı bileşenlerle (SCC) bulunur. Birden fazla üyeli her bileşen hazır olduğunda kendi katmanında kurulur; ona bağlı repolar sonraki katmanlarda kurulur. `CYCLE_FAILED` yalnızca döngü üyeleri için kullanılır.
- **Yeniden indeksleme.** Bir tüketicinin yeniden indekslenme kararında doğrudan sağlayıcıları değil, sağlayıcılarının kapanışı (`transitiveProvidersOf`) kullanılır.
- **Kendi koordinatı.** Bir koordinatı tüketicinin kendi reposu da tanımlıyorsa o referans için başka bir repo sağlayıcı sayılmaz.
