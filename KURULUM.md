# Graphify Kurulum Rehberi

Bu rehber, projeyi hiç bilmeyen birinin Graphify'ı GitHub'dan indirip bir WildFly sunucusuna deploy etmesini ve ilk taramayı yapıp kullanmaya başlamasını adım adım anlatır.

Rehberin sonunda şunlar hazır olacak:

- Oracle veritabanı çalışıyor.
- Graphify, WildFly üzerinde `http://<sunucu>:8080/graphify/` adresinde açılıyor.
- `admin` kullanıcısıyla giriş yapılmış, en az bir repo bağlantısı eklenmiş ve ilk tarama tamamlanmış.

Komutlar macOS ve Linux için yazıldı. Windows'ta komut satırı farkları adım adım belirtildi. Windows'ta en kolay yol WSL (Windows Subsystem for Linux) kullanmaktır.

---

## İçindekiler

1. [Gerekenler](#1-gerekenler)
2. [Projeyi indirin](#2-projeyi-indirin)
3. [Oracle veritabanını hazırlayın](#3-oracle-veritabanını-hazırlayın)
4. [WAR dosyasını derleyin](#4-war-dosyasını-derleyin)
5. [WildFly'ı kurun](#5-wildflyı-kurun)
6. [Ortam değişkenlerini tanımlayın](#6-ortam-değişkenlerini-tanımlayın)
7. [Deploy edin ve başlatın](#7-deploy-edin-ve-başlatın)
8. [İlk giriş](#8-ilk-giriş)
9. [Sunucu ayarları](#9-sunucu-ayarları)
10. [Repo bağlantısı ekleyin](#10-repo-bağlantısı-ekleyin)
11. [İlk taramayı başlatın](#11-ilk-taramayı-başlatın)
12. [Uygulamayı kullanın](#12-uygulamayı-kullanın)
13. [İsteğe bağlı: LDAP ve özel Maven repository'leri](#13-isteğe-bağlı-ldap-ve-özel-maven-repositoryleri)
14. [Yeni sürüme güncelleme](#14-yeni-sürüme-güncelleme)
15. [Sorun giderme](#15-sorun-giderme)
16. [Kısa özet](#16-kısa-özet)

---

## 1. Gerekenler

| Araç | Sürüm | Ne için |
|---|---|---|
| **JDK** | 25 | Projeyi derlemek ve WildFly'ı çalıştırmak (eski sürümle derleme başlamaz) |
| **Git** | herhangi bir güncel sürüm | Projeyi GitHub'dan indirmek |
| **Docker** | herhangi bir güncel sürüm | Oracle veritabanını en kolay şekilde çalıştırmak (mevcut bir Oracle'ınız varsa gerekmez) |
| **WildFly** | 41.0.0.Final | Uygulamanın çalışacağı uygulama sunucusu |
| **Maven** | 3.9 veya üstü | Sunucuda, taranan Java projelerinin classpath'ini çözmek için (derleme için gerekmez; proje kendi Maven'ını getirir) |
| **İnternet erişimi** | derleme sırasında | Derleme, web arayüzü için Node.js ve npm paketlerini indirir |

En az 4 GB RAM önerilir. Taranan repolar büyüdükçe WildFly'a daha çok bellek verin (bkz. [6. adım](#6-ortam-değişkenlerini-tanımlayın)).

### Kurulum komutları

**macOS (Homebrew):**

```bash
brew install openjdk@25 git maven
brew install --cask docker   # Docker Desktop; kurulduktan sonra uygulamayı bir kez açın
```

**Linux (Ubuntu/Debian):**

```bash
sudo apt update
sudo apt install -y git maven docker.io
# JDK 25: dağıtımınızda yoksa https://adoptium.net adresinden "Temurin 25" indirin
```

**Windows:** JDK 25'i https://adoptium.net, Git'i https://git-scm.com, Docker Desktop'ı https://www.docker.com ve Maven'ı https://maven.apache.org adresinden kurun.

### Kurulumu kontrol edin

```bash
java -version    # "25" görmelisiniz
git --version
docker --version
mvn -v           # "Apache Maven 3.9.x" görmelisiniz
```

`java -version` 25 göstermiyorsa, `JAVA_HOME` değişkenini JDK 25'e yöneltin:

```bash
# macOS (Homebrew)
export JAVA_HOME=/opt/homebrew/opt/openjdk@25
export PATH="$JAVA_HOME/bin:$PATH"

# Linux (örnek yol; kendi kurulum yolunuzu yazın)
export JAVA_HOME=/usr/lib/jvm/temurin-25-jdk
export PATH="$JAVA_HOME/bin:$PATH"
```

> Bu `export` satırları yalnızca açık olan terminal penceresinde geçerlidir. Kalıcı olması için `~/.zshrc` (macOS) ya da `~/.bashrc` (Linux) dosyasının sonuna ekleyin.

---

## 2. Projeyi indirin

```bash
git clone https://github.com/zeuseng25/code-graphify.git
cd code-graphify
```

Bundan sonraki bütün komutlar bu klasörde (`code-graphify`) çalıştırılır.

---

## 3. Oracle veritabanını hazırlayın

Graphify verilerini Oracle'da tutar. Veritabanının karakter seti **AL32UTF8** olmalıdır. Uygulama açılışta bunu kontrol eder ve başka bir karakter setiyle başlamaz.

### Seçenek A: Docker ile Oracle Free (önerilen, en kolay)

Aşağıdaki komut Oracle 23 Free'yi başlatır ve uygulama için `graphify` adında bir kullanıcı oluşturur. `<SYS_SIFRESI>` ve `<GRAPHIFY_SIFRESI>` yerine kendi şifrelerinizi yazın:

```bash
docker run -d --name graphify-db \
  -p 1521:1521 \
  -e ORACLE_PASSWORD='<SYS_SIFRESI>' \
  -e APP_USER=graphify \
  -e APP_USER_PASSWORD='<GRAPHIFY_SIFRESI>' \
  --restart unless-stopped \
  gvenzl/oracle-free:23-slim-faststart
```

Veritabanının hazır olmasını bekleyin. İlk açılış birkaç dakika sürebilir:

```bash
docker logs -f graphify-db
# "DATABASE IS READY TO USE!" satırını gördüğünüzde Ctrl+C ile çıkın
```

Bu durumda bağlantı bilgileriniz şöyledir (6. adımda kullanacaksınız):

| Bilgi | Değer |
|---|---|
| `DB_URL` | `jdbc:oracle:thin:@//localhost:1521/FREEPDB1` |
| `DB_USER` | `graphify` |
| `DB_PASSWORD` | `<GRAPHIFY_SIFRESI>` |

> Docker veritabanı WildFly'dan farklı bir makinedeyse `localhost` yerine o makinenin adresini yazın.

### Seçenek B: Mevcut bir Oracle veritabanı

Veritabanı yöneticinizden (DBA) boş bir şema isteyin ya da kendiniz oluşturun. `<GRAPHIFY_SIFRESI>` ve `<TABLESPACE>` yerine kendi değerlerinizi yazın; `<TABLESPACE>` genellikle `USERS`'tır:

```sql
CREATE USER graphify IDENTIFIED BY "<GRAPHIFY_SIFRESI>";
GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE TO graphify;
ALTER USER graphify QUOTA UNLIMITED ON <TABLESPACE>;
```

Karakter setini kontrol etmek için:

```sql
SELECT value FROM nls_database_parameters WHERE parameter = 'NLS_CHARACTERSET';
-- Sonuç AL32UTF8 olmalı
```

Tabloları **siz oluşturmazsınız**. Uygulama ilk açılışta tabloları kendisi oluşturur (Flyway migration'ları) ve sonraki sürümlerde günceller.

---

## 4. WAR dosyasını derleyin

Proje klasöründe (`code-graphify`) şu komutu çalıştırın:

```bash
./mvnw -DskipTests package \
  -Dfrontend.node.downloadRoot=https://nodejs.org/dist/ \
  -Dfrontend.npm.registry=https://registry.npmjs.org
```

Windows komut satırında `./mvnw` yerine `mvnw.cmd` yazın ve komutu tek satıra yazın (satır sonlarındaki `\` olmadan).

Bu komut:

- Java kodunu derler.
- Web arayüzü için Node.js'i indirir ve React uygulamasını derler.
- Hepsini tek bir dosyada toplar: **`target/graphify-0.0.1-SNAPSHOT.war`**

İlk derleme, indirmeler yüzünden 5–15 dakika sürebilir; sonrakiler daha hızlıdır. Komut `BUILD SUCCESS` ile bitmelidir.

Parametrelerin anlamı:

- **`-DskipTests`:** Testleri atlar. Testler Docker üzerinde ayrı bir Oracle başlattığı için uzun sürer; kurulum için gerekmez.
- **`-Dfrontend.node.downloadRoot` ve `-Dfrontend.npm.registry`:** Node.js'in ve npm paketlerinin indirileceği adresler. Şirket ağında internete doğrudan çıkış yoksa bunların yerine şirketinizin iç mirror adreslerini yazın. `downloadRoot` adresi `/` ile **bitmeli**, `npm.registry` adresi `/` ile **bitmemelidir**.

---

## 5. WildFly'ı kurun

1. WildFly 41.0.0.Final'ı indirin:
   - Resmi sayfa: https://www.wildfly.org/downloads/
   - Doğrudan bağlantı: https://github.com/wildfly/wildfly/releases/download/41.0.0.Final/wildfly-41.0.0.Final.zip

2. İstediğiniz bir klasöre açın. Örnek:

   ```bash
   mkdir -p ~/servers
   cd ~/servers
   unzip ~/Downloads/wildfly-41.0.0.Final.zip
   ```

   Bu rehberde WildFly klasörüne **`WILDFLY_HOME`** diyeceğiz. Örnekte bu klasör `~/servers/wildfly-41.0.0.Final`'dır.

3. WildFly da **JDK 25** ile çalışmalıdır. WildFly'ı başlattığınız terminalde `java -version` 25 göstermelidir (bkz. [1. adım](#1-gerekenler)).

> WildFly için ek bir veritabanı sürücüsü ya da datasource tanımlamanız **gerekmez**. Oracle sürücüsü WAR dosyasının içindedir ve uygulama bağlantıyı kendisi kurar.

---

## 6. Ortam değişkenlerini tanımlayın

Uygulama, veritabanı bilgilerini ve gizli anahtarını kodda değil, ortam değişkenlerinde arar.

| Değişken | Ne | Örnek |
|---|---|---|
| `DB_URL` | Oracle bağlantı adresi | `jdbc:oracle:thin:@//localhost:1521/FREEPDB1` |
| `DB_USER` | Oracle kullanıcısı | `graphify` |
| `DB_PASSWORD` | Oracle şifresi | `<GRAPHIFY_SIFRESI>` |
| `APP_MASTER_KEY` | Kaydedilen token ve şifreleri şifreleyen anahtar (aşağıda nasıl üretileceği anlatılıyor) | `k3J9...=` |
| `APP_BOOTSTRAP_ADMIN_PASSWORD` | İlk `admin` kullanıcısının geçici şifresi (en az 12 karakter) | `Gecici-Sifre-2026` |
| `SPRING_PROFILES_ACTIVE` | Çalışma profili: canlı ortamda `prod` (daha az log), denemede `dev` | `prod` |

### 6.1 Master key üretin

```bash
openssl rand -base64 32
```

Çıkan değeri (`=` ile biten 44 karakterlik metin) `APP_MASTER_KEY` olarak kullanın.

> **Önemli:** Bu anahtarı güvenli bir yerde saklayın ve **değiştirmeyin**. Uygulamaya kaydettiğiniz GitHub/Bitbucket token'ları, Maven şifreleri ve LDAP şifresi bu anahtarla şifrelenir. Anahtar kaybolursa ya da değişirse bu bilgileri arayüzden yeniden girmeniz gerekir.

### 6.2 Değişkenleri WildFly'a tanımlayın

**macOS / Linux:** `WILDFLY_HOME/bin/standalone.conf` dosyasını bir metin düzenleyiciyle açın ve **en sonuna** şu satırları ekleyin. Değerleri kendi bilgilerinizle değiştirin:

```bash
# --- Graphify ---
export DB_URL="jdbc:oracle:thin:@//localhost:1521/FREEPDB1"
export DB_USER="graphify"
export DB_PASSWORD="<GRAPHIFY_SIFRESI>"
export APP_MASTER_KEY="<openssl ile ürettiğiniz anahtar>"
export APP_BOOTSTRAP_ADMIN_PASSWORD="<en az 12 karakterlik geçici şifre>"
export SPRING_PROFILES_ACTIVE="prod"
```

**Windows:** `WILDFLY_HOME\bin\standalone.conf.bat` dosyasının sonuna aynı değerleri `set` ile ekleyin:

```bat
rem --- Graphify ---
set "DB_URL=jdbc:oracle:thin:@//localhost:1521/FREEPDB1"
set "DB_USER=graphify"
set "DB_PASSWORD=<GRAPHIFY_SIFRESI>"
set "APP_MASTER_KEY=<openssl ile ürettiğiniz anahtar>"
set "APP_BOOTSTRAP_ADMIN_PASSWORD=<en az 12 karakterlik geçici şifre>"
set "SPRING_PROFILES_ACTIVE=prod"
```

> `standalone.conf` dosyasında artık şifreler var. Bu dosyayı yalnızca sunucu yöneticilerinin okuyabileceği şekilde koruyun; Linux'ta örneğin `chmod 600 bin/standalone.conf`.

### 6.3 (Önerilir) WildFly'a daha çok bellek verin

WildFly varsayılan olarak 512 MB bellekle başlar. Büyük repoları tararken bu yetmeyebilir. `standalone.conf` içinde şu satırı bulun:

```bash
   JBOSS_JAVA_SIZING="-Xms64m -Xmx512m"
```

ve şu şekilde değiştirin (örnek 2 GB; sunucunuzun belleğine göre ayarlayın):

```bash
   JBOSS_JAVA_SIZING="-Xms256m -Xmx2g"
```

---

## 7. Deploy edin ve başlatın

### 7.1 WAR dosyasını kopyalayın

Dosyanın adı, uygulamanın adresini belirler: `graphify.war` olarak kopyalarsanız uygulama `/graphify` adresinde açılır.

```bash
cp target/graphify-0.0.1-SNAPSHOT.war WILDFLY_HOME/standalone/deployments/graphify.war
```

(`WILDFLY_HOME` yerine kendi WildFly klasörünüzü yazın.)

### 7.2 WildFly'ı başlatın

```bash
cd WILDFLY_HOME
./bin/standalone.sh        # Windows: bin\standalone.bat
```

Uygulamanın açılışı 1–2 dakika sürebilir. İlk açılışta veritabanı tabloları oluşturulur. Başarılı olduğunu iki yoldan anlayabilirsiniz:

- `standalone/deployments/` klasöründe **`graphify.war.deployed`** dosyası oluşur. `graphify.war.failed` oluşursa [Sorun giderme](#15-sorun-giderme) bölümüne bakın.
- Terminalde ya da `standalone/log/server.log` dosyasında `Deployed "graphify.war"` satırı görünür.

> WildFly'ı arka planda çalıştırmak için (terminal kapansa da çalışsın): `nohup ./bin/standalone.sh > wildfly.out 2>&1 &`

### 7.3 Uygulamayı açın

Tarayıcıda şu adreslerden birini açın:

- **HTTP:** `http://localhost:8080/graphify/`
- **HTTPS:** `https://localhost:8443/graphify/`

HTTPS'te WildFly'ın kendi ürettiği sertifika kullanıldığı için tarayıcı bir güvenlik uyarısı gösterir; deneme ortamında "Devam et" diyebilirsiniz. Canlı ortamda WildFly'a kurumunuzun sertifikasını tanımlayın.

Başka bir makineden erişmek için `localhost` yerine sunucunun adresini yazın. WildFly varsayılan olarak yalnızca `localhost`'u dinler. Dışarıdan erişim için başlatırken `-b 0.0.0.0` ekleyin: `./bin/standalone.sh -b 0.0.0.0`.

---

## 8. İlk giriş

1. Giriş ekranında şunları yazın:
   - **Kullanıcı adı:** `admin`
   - **Şifre:** `APP_BOOTSTRAP_ADMIN_PASSWORD` için yazdığınız geçici şifre
2. Uygulama sizden şifrenizi değiştirmenizi ister. **Mevcut şifre**, **Yeni şifre** ve **Yeni şifre (tekrar)** alanlarını doldurup **Değiştir**'e basın. Yeni şifre en az 12 karakter olmalıdır.
3. Şifreyi değiştirdikten sonra ana ekran (Sembol arama) açılır.

> `APP_BOOTSTRAP_ADMIN_PASSWORD` tanımlamadıysanız, uygulama ilk açılışta rastgele bir şifre üretir ve bunu **bir kez** `server.log` dosyasına yazar.

İlk girişten sonra `APP_BOOTSTRAP_ADMIN_PASSWORD` satırını `standalone.conf`'tan silebilirsiniz; artık kullanılmaz.

---

## 9. Sunucu ayarları

Uygulama repoları sunucuda bir klasöre indirir ve Maven ile derleme bilgilerini çözer. Bu klasörlerin varsayılan değeri (`/data/impact-analyzer/...`) çoğu sunucuda yoktur ya da yazılamaz. Taramadan önce bunları ayarlayın.

1. Sol menüden **Yönetim → Ayarlar**'a gidin.
2. Aşağıdaki üç ayarı bulun. Üstteki arama kutusuna anahtar adını yazabilirsiniz. Satırdaki **Değer** kutusuna yeni değeri yazıp aynı satırdaki **Kaydet**'e basın:

| Ayar (Anahtar) | Ne | Örnek değer |
|---|---|---|
| `index.workspace_dir` | Repoların indirileceği klasör | `/opt/graphify/repos` |
| `index.maven_local_repository` | Maven'ın indirdiği kütüphanelerin klasörü | `/opt/graphify/m2` |
| `index.maven_executable` | Sunucudaki Maven komutunun **tam yolu** | `/usr/bin/mvn` |

Notlar:

- **Mutlak yol yazın.** Klasör yoksa uygulama oluşturur; ancak WildFly'ı çalıştıran kullanıcının o konuma yazma izni olmalıdır.
- **Kaydederken kontrol yapılır.** Uygulama, klasöre deneme dosyası yazabildiğini ve Maven komutunun `-v` ile cevap verdiğini kontrol eder. Bir sorun varsa değer kaydedilmez ve nedeni alanın altında yazar.
- **Maven'ın yolunu bulmak** için sunucuda `which mvn` (macOS/Linux) ya da `where mvn` (Windows) komutunu çalıştırın. macOS'ta Homebrew ile kurduysanız yol genellikle `/opt/homebrew/bin/mvn`'dir.

Diğer ayarlar (tarama saati, aynı anda taranan repo sayısı vb.) aynı ekrandan değiştirilebilir. Her ayarın açıklaması satırında yazar ve değişiklikler yeniden başlatma gerektirmez.

---

## 10. Repo bağlantısı ekleyin

Graphify, taranacak repoları bir "repo bağlantısı" üzerinden bulur.

1. **Yönetim → Repo bağlantıları → Yeni ekle**'ye basın.
2. **Tür** seçin: **GitHub**, **Bitbucket** ya da **Git**. Tür kaydedildikten sonra değiştirilemez.
3. Alanları türe göre doldurun (aşağıdaki tablo).
4. **Kaydet**'e, sonra **Bağlantıyı test et**'e basın. "Bağlantı başarılı." görmelisiniz.

| Alan | GitHub | Bitbucket (Data Center) | Git (herhangi bir Git sunucusu) |
|---|---|---|---|
| **Base URL** | github.com için `https://api.github.com`; GitHub Enterprise için `https://<sunucu>/api/v3` | Bitbucket adresi, örn. `https://bitbucket.sirket.com` | Git sunucusunun adresi, örn. `https://git.sirket.com/scm` |
| **Kullanıcı adı** | boş bırakılabilir | isteğe bağlı | şifre ile giriş yapılacaksa gerekli |
| **Token / şifre** | GitHub token'ı (zorunlu) | Bitbucket token'ı ya da şifre | şifre ya da token (gerekmiyorsa boş) |
| **Organizasyonlar / Dahil edilen project'ler** | Taranacak organizasyon adları (Enter ile ekleyin) | Taranacak project anahtarları (boşsa erişilebilen hepsi) | — |
| **Kendi repolarımı da tara** | Kişisel hesabınızdaki repolar için açın | — | — |
| **Hariç tutulan repolar** | örn. `my-org/legacy-*` | örn. `SHOP/legacy-*` | — |
| **Repo adresleri** | — | — | Her satıra bir clone adresi; hepsi Base URL ile başlamalı |

### GitHub token'ı nasıl alınır?

1. GitHub'da sağ üstteki profil resminiz → **Settings** → **Developer settings** → **Personal access tokens**.
2. İki seçenekten birini kullanın:
   - **Tokens (classic):** **Generate new token (classic)** seçin ve `repo` iznini işaretleyin.
   - **Fine-grained tokens:** Taranacak repolar için **Contents: Read** ve **Metadata: Read** izinlerini verin.
3. Oluşan token'ı kopyalayıp **Token / şifre** alanına yapıştırın.

Repolarınız bir organizasyonda değil de kişisel hesabınızdaysa **Kendi repolarımı da tara** seçeneğini açın. Kişisel hesap adı organizasyon alanına yazılırsa bulunamaz.

> Token'lar ve şifreler veritabanına şifrelenerek kaydedilir ve arayüzde bir daha gösterilmez. Düzenlerken alanı boş bırakırsanız kayıtlı değer korunur.

---

## 11. İlk taramayı başlatın

1. Sol menüden **Taramalar**'a gidin.
2. **Yeni tarama** bölümünde:
   - **Kapsam:** `Tüm repolar` (ya da yalnızca eklediğiniz bağlantı)
   - **Değişmemiş repoları da tara (force):** İlk taramada önemli değil; sonraki taramalarda, değişmemiş repoları da baştan taramak için açın.
3. **Taramayı başlat**'a basın.

Tarama sayfası kendiliğinden yenilenir. Her repo için bir durum görürsünüz:

| Durum | Anlamı |
|---|---|
| **Başarılı** | Repo tam olarak tarandı. |
| **Kısmen başarılı** | Tarandı, ama bazı bağımlılıklar çözülemedi; bu yüzden bazı bağlantılar yalnızca isimle eşleşmiş olabilir. Satırdaki hataya bakın. |
| **Değişmedi** | Son taramadan beri yeni commit yok; atlandı. |
| **Java değil** | Repoda Java kodu yok; atlandı. |
| **Klonlanamadı** | Repo indirilemedi. Satırdaki hata mesajı nedenini söyler (çoğunlukla token ya da adres). |
| **Başarısız** | Repo indirildi ama taranamadı. Satırdaki hata mesajına bakın. |
| **Yarıda kesildi** | Tarama, sunucu kapandığı için yarıda kaldı; bir sonraki taramada yeniden denenir. |

İlk tarama, repo sayısına ve boyutuna göre birkaç dakikadan birkaç saate kadar sürebilir. Sonraki taramalar yalnızca değişen repoları tarar ve çok daha hızlıdır. Uygulama ayrıca her gece otomatik tarama yapar; zamanı **Ayarlar**'daki `index.cron` belirler (varsayılan 02:00).

---

## 12. Uygulamayı kullanın

- **Sembol arama:** Bir class, method ya da field adı yazın (örn. `OrderService` ya da `OrderService.save`). Sonuca tıklayınca sembolün nerede tanımlandığını ve hangi repolarda, nerelerde kullanıldığını görürsünüz.
- **Etki analizi:** Sembol sayfasındaki **Etki analizi** düğmesi, o sembol değişirse nelerin etkileneceğini gösterir: etkilenen repolar ve method'lar, REST endpoint'leri gibi giriş noktaları ve bir graf. Sonucu CSV olarak indirebilir ya da bağlantıyı paylaşabilirsiniz.
- **Repolar:** Taranan repoları, modüllerini ve tarama geçmişini gösterir. Repo sayfasındaki **Grafı göster** düğmesi, repo içi bağımlılık grafını module, package, class ve method seviyelerinde açar.
- **Tema:** Sağ üstteki **Tema** düğmesiyle açık, koyu ya da sistem teması seçebilirsiniz.

---

## 13. İsteğe bağlı: LDAP ve özel Maven repository'leri

### LDAP ile giriş

Kullanıcıların şirket hesaplarıyla girmesi için:

1. **Yönetim → LDAP**'a gidin.
2. LDAP sunucunuzun adresini, bind DN'ini, şifresini ve kullanıcı arama ayarlarını girin.
3. Test edip kaydedin.

LDAP kullanıcıları ilk girişlerinde **USER** rolüyle oluşturulur. **Yönetim → Kullanıcılar**'dan rollerini değiştirebilir ya da dizinden kullanıcı ekleyebilirsiniz.

### Özel Maven repository'leri (Nexus, Artifactory)

Taranan projeler şirket içi bir Maven deposundan kütüphane kullanıyorsa, **Yönetim → Maven repository'leri → Yeni ekle** ile deponun adresini ve kullanıcı bilgilerini ekleyin. Aksi halde bu repolar **Kısmen başarılı** olarak taranır.

---

## 14. Yeni sürüme güncelleme

```bash
cd code-graphify
git pull
./mvnw -DskipTests package \
  -Dfrontend.node.downloadRoot=https://nodejs.org/dist/ \
  -Dfrontend.npm.registry=https://registry.npmjs.org
cp target/graphify-0.0.1-SNAPSHOT.war WILDFLY_HOME/standalone/deployments/graphify.war
```

- WildFly çalışırken dosyanın üzerine kopyalamak yeterlidir. WildFly yeni sürümü kendiliğinden yükler ve `graphify.war.deployed` dosyası yeniden oluşur.
- Veritabanı değişiklikleri açılışta otomatik uygulanır.
- O sırada açık olan tarayıcı sekmeleri, yeni sürüme geçmek için gerekirse kendiliğinden bir kez yenilenir.

---

## 15. Sorun giderme

Her sorunda ilk bakılacak yer **`WILDFLY_HOME/standalone/log/server.log`** dosyasıdır.

| Belirti | Neden ve çözüm |
|---|---|
| Derleme `JDK 25 is required` ile duruyor | `java -version` 25 değil. `JAVA_HOME`'u JDK 25'e yöneltin (1. adım). |
| Derleme `Set -Dfrontend.node.downloadRoot ...` ile duruyor | 4. adımdaki iki `-Dfrontend...` parametresini eklemeyi unuttunuz. |
| Derleme Node.js ya da npm indirirken hata veriyor | Sunucunun internete erişimi yok. Şirketinizin iç mirror adreslerini kullanın (4. adım). |
| `graphify.war.failed` dosyası oluştu | `server.log`'da hatayı arayın. En sık nedenler: ortam değişkenleri eksik ya da yanlış (6. adım), veritabanına ulaşılamıyor, WildFly JDK 25 ile çalışmıyor. |
| Log'da `ORA-12541` / `ORA-12514` / `Connection refused` | Oracle çalışmıyor ya da `DB_URL` yanlış. `docker ps` ile container'ı kontrol edin; Docker'ı yeni başlattıysanız `DATABASE IS READY TO USE!` satırını bekleyin. |
| Log'da `ORA-01017` | `DB_USER` ya da `DB_PASSWORD` yanlış. |
| Log'da karakter seti (AL32UTF8) hatası | Veritabanının karakter seti uygun değil. AL32UTF8 olan bir veritabanı kullanın; Docker'daki Oracle Free zaten AL32UTF8'dir. |
| WildFly başlamıyor: `Address already in use` | 8080 portunu başka bir program kullanıyor. Portları kaydırarak başlatın: `./bin/standalone.sh -Djboss.socket.binding.port-offset=100` (bu durumda adres `http://localhost:8180/graphify/` olur). |
| Giriş reddediliyor | Kullanıcı adını ve şifreyi kontrol edin. Çok sayıda hatalı denemeden sonra hesap bir süre kilitlenir (varsayılan 15 dakika). |
| Tarama: `Read-only file system` ya da klasöre yazılamıyor | `index.workspace_dir` ve `index.maven_local_repository` ayarlarını yazılabilir bir klasöre yöneltin (9. adım). |
| Tarama: Maven bulunamadı (`mvn`) | `index.maven_executable` ayarına Maven'ın tam yolunu yazın (9. adım). |
| Bir repo **Kısmen başarılı** | Satırdaki hataya bakın. Genellikle bir kütüphane indirilemiyordur (özel Maven deposu gerekiyor, 13. adım) ya da proje daha yeni bir Maven sürümü istiyordur (sunucudaki Maven'ı güncelleyin). |
| GitHub bağlantısı: organizasyon bulunamadı (404) | Yazdığınız ad bir organizasyon değil, kişisel hesap olabilir. Organizasyon alanını boşaltıp **Kendi repolarımı da tara**'yı açın (10. adım). |
| GitHub/Bitbucket: yetki hatası | Token'ın süresi dolmuş ya da izinleri eksik. Yeni bir token oluşturup bağlantıya kaydedin. |

---

## 16. Kısa özet

```bash
# 1) İndir
git clone https://github.com/zeuseng25/code-graphify.git && cd code-graphify

# 2) Veritabanı (Docker)
docker run -d --name graphify-db -p 1521:1521 \
  -e ORACLE_PASSWORD='<SYS_SIFRESI>' -e APP_USER=graphify -e APP_USER_PASSWORD='<GRAPHIFY_SIFRESI>' \
  --restart unless-stopped gvenzl/oracle-free:23-slim-faststart

# 3) Derle (JDK 25 ile)
./mvnw -DskipTests package \
  -Dfrontend.node.downloadRoot=https://nodejs.org/dist/ -Dfrontend.npm.registry=https://registry.npmjs.org

# 4) WildFly: bin/standalone.conf sonuna DB_URL, DB_USER, DB_PASSWORD, APP_MASTER_KEY,
#    APP_BOOTSTRAP_ADMIN_PASSWORD, SPRING_PROFILES_ACTIVE export satırlarını ekleyin (6. adım)

# 5) Deploy ve başlat
cp target/graphify-0.0.1-SNAPSHOT.war WILDFLY_HOME/standalone/deployments/graphify.war
WILDFLY_HOME/bin/standalone.sh

# 6) Tarayıcı: http://localhost:8080/graphify/  →  admin ile giriş  →  şifre değiştir
#    → Yönetim > Ayarlar (klasörler ve Maven yolu)  →  Yönetim > Repo bağlantıları  →  Taramalar > Taramayı başlat
```
