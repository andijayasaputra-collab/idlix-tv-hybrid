# Konfigurasi sumber remote

## Temuan audit

Aplikasi ini memakai JSON API, bukan Jsoup. Tidak ada selector CSS katalog/episode atau extractor iframe HTML di Android. `core/data/api/IdlixApiService.kt` mendefinisikan kontrak Retrofit; `IdlixRepository.kt` menangani play-info, countdown, verifikasi pengguna, claim, redeem, dan URL HLS. Backend Node terpisah memakai Puppeteer dan API JSON.

Perubahan ini mempertahankan layout, tema, komponen, navigasi, countdown, dan dialog verifikasi. Activity menunda pembuatan konten Compose sampai konfigurasi selesai dipilih; saat offline, jendela launch bisa menunggu sampai timeout fetch delapan detik. Kesamaan visual belum diverifikasi di perangkat.

| Parameter semula | Lokasi | Penanganan sekarang |
| --- | --- | --- |
| Domain default dan origin cadangan | `core/data/build.gradle.kts`, `ApiClient.kt`, `TurnstileVerificationActivity.kt` | `baseUrl` di JSON |
| Default URL tombol reset TV | `app-tv/build.gradle.kts`, `SettingsScreen.kt` | Default konfigurasi aktif |
| Katalog, detail, episode, genre, pencarian | Anotasi `IdlixApiService.kt` | Pemetaan `endpoints`; anotasi tetap menjadi identifier kontrak |
| Play-info, resolver, claim, auth | `IdlixApiService.kt`, `ApiClient.kt`, `IdlixRepository.kt` | Pemetaan endpoint juga berlaku untuk refresh autentikasi dan claim |
| Redeem fallback Majorplay, prefiks API/watch | `IdlixRepository.kt` | `player.redeemPath`, `fallbackRedeemUrls`, `apiPrefix`, `watchPath` |
| Regex owner gate dan cookie DID | `IdlixRepository.kt`, `ApiClient.kt` | `player.gateOwnerRegex`, `didCookieRegex` |
| User-agent API dan stream/download | `ApiClient.kt`, player TV/mobile, downloader | `userAgent` dan `player.userAgent` |
| Link share mobile | `DetailScreen.kt` | Mengikuti `baseUrl` sumber |

URL SubDL, TMDB, DNS-over-HTTPS, script verifikasi Cloudflare, dan manifest pembaruan APK merupakan integrasi lain; perubahan ini tidak memindahkannya. Regex standar playlist HLS dan pembersihan judul subtitle juga tetap di kode. Backend Node dan pengaturan deploy-nya belum membaca file remote ini; bila memakai proxy tersebut, operator harus memperbarui server secara terpisah.

Audit juga menemukan perbedaan kontrak yang sudah ada: backend menyediakan `/api/watch/redeem`, sedangkan Android biasanya memakai `/api/watch/session/redeem`. Mengubah `redeemPath` tidak cukup untuk mengadaptasi format body/response backend yang berbeda. Proxy memerlukan audit sendiri untuk origin/cookie dan pembatasan URL redeem sebelum dibuka ke jaringan publik.

## File yang menjadi sumber default

Edit `remote-source.json` di root. Gradle menanamkan isi file tersebut sebagai fallback APK melalui `BuildConfig.BUNDLED_REMOTE_SOURCE_JSON`; tidak ada salinan default domain di Kotlin.

`schemaVersion` harus `1`. Naikkan `version` setiap kali menerbitkan perubahan; aplikasi memilih versi valid tertinggi dari bundled, cache, dan remote. Remote menang jika versinya sama. Untuk mengembalikan konfigurasi lama, terbitkan isinya dengan nomor versi baru yang lebih tinggi.

`selectors.catalogue`, `selectors.episode`, dan `embedRegexes` hanya field cadangan. Nilainya kosong karena aplikasi tidak punya parser HTML; mengisi field ini tidak mengaktifkan scraping. JSON ini bukan modul executable seperti ekstensi CloudStream: perubahan bentuk respons JSON, algoritma ekstraksi, protokol player, atau parser HTML baru tetap memerlukan kode dan rebuild APK. Tidak ada kode remote yang dieksekusi, dan alur verifikasi/otorisasi tetap berlaku.

## Publikasi GitHub Raw

URL bawaan:

```
https://raw.githubusercontent.com/andijayasaputra-collab/idlix-tv-hybrid/main/remote-source.json
```

Repo harus publik agar URL Raw terbaca tanpa token. Untuk memakai repo lain, tentukan URL Raw sendiri **sebelum build APK pertama** di `local.properties`:

```properties
remoteSourceConfigUrl=https://raw.githubusercontent.com/OWNER/REPO/main/remote-source.json
```

Sesudah APK memakai URL itu, perubahan isi JSON pada URL yang sama tidak perlu rebuild. Mengubah URL bootstrap-nya sendiri masih perlu rebuild. URL fetch harus HTTPS tanpa query, fragment, atau kredensial. Redirect tidak diikuti; gunakan URL Raw langsung.

Jaga hak tulis repo konfigurasi. Konfigurasi remote bisa mengganti domain tujuan API yang menerima data akun. TLS melindungi transport tetapi bukan pengganti tanda tangan konfigurasi; versi ini belum menerapkan signature pinning.

## Urutan startup dan fallback

`MainActivity` TV/mobile memanggil `RemoteConfigManager.initialize()` sebelum `AppNavigation`/`MainScaffold` dibuat. Manager membaca cache SharedPreferences, mencoba fetch terbaru menggunakan client terpisah tanpa cookie atau authenticator, lalu memilih snapshot valid. `ApiClient.applyRemoteConfig()` memasang user-agent dan membuang instance Retrofit lama sebelum layar pertama dibuat.

Fetch dibatasi delapan detik dan 256 KiB, termasuk respons chunked/gzip. JSON rusak, schema tak dikenal, URL tidak aman, endpoint ambigu, placeholder berubah, regex tidak valid, respons HTTP gagal, atau jaringan offline tidak menggantikan last-known-good cache. Instalasi baru tanpa jaringan memakai JSON bawaan APK. Cache konfigurasi tidak membuat katalog atau video tersedia offline.

Aplikasi mengecek remote sekali per proses. Tutup proses dan buka ulang untuk mengambil pembaruan berikutnya; tidak ada polling selama playback. Rotasi/recreate Activity tidak mem-fetch ulang. Server kustom yang pengguna simpan di Settings tetap menang atas `baseUrl`; pilih default terbaru lalu simpan jika ingin kembali mengikuti domain remote. Mirror yang ditemukan melalui redirect tidak lagi disimpan sebagai pilihan server kustom.

## Mengubah endpoint

Key `endpoints` adalah identifier tetap yang cocok dengan kontrak Retrofit; ubah nilainya, bukan key-nya. Contoh:

```json
"api/movies/{slug}": "v2/catalog/movie/{slug}"
```

Placeholder harus tetap sama, meskipun urutannya boleh berbeda. Nilai harus path relatif tanpa query/fragment, traversal, atau domain. Query `page`, `limit`, `sort`, `genre`, dan parameter lain tetap mengikuti kontrak aplikasi. Routing mempertahankan segmen percent-encoded dan tidak menyentuh request ke host player/subtitle eksternal.

`player.sourceHostSuffixes` memakai batas domain: `mirror.example.com` cocok dengan `example.com`, tetapi `evilexample.com` tidak. Sertakan domain mirror baru sebelum memakainya.

## Build dan pengujian

Proyek meminta JDK 21 dan Android SDK 35. Gunakan `JAVA_HOME` yang benar dan override properti Java bawaan repo bila Android Studio tidak terpasang:

```sh
JAVA_HOME=/path/to/jdk21 ANDROID_HOME=/path/to/android-sdk \
  ./gradlew -Dorg.gradle.java.home=/path/to/jdk21 assembleDebug

JAVA_HOME=/path/to/jdk21 ANDROID_HOME=/path/to/android-sdk \
  ./gradlew -Dorg.gradle.java.home=/path/to/jdk21 :core:data:testDebugUnitTest
```

Beberapa runtime Java/Gradle bermasalah dengan direktori workspace yang mengandung `:` karena pemisah classpath Unix. Gunakan checkout di path tanpa `:` bila wrapper gagal menemukan `GradleWrapperMain`. Jangan mengubah target Java proyek hanya untuk mengatasi JDK lokal yang belum tersedia.
