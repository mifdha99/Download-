# Uda Downloader (`com.udadownloader.app`)

**Uda Downloader** adalah aplikasi manajer unduhan (*Download Manager*) Android serbaguna berbasis **Kotlin**, **Jetpack Compose (Material 3)**, **OkHttp Streaming**, **Room Database**, **WorkManager**, dan **Foreground Service**. Aplikasi ini dirancang untuk melakukan transfer file nyata melalui protokol HTTP/HTTPS dengan dukungan jeda & lanjutkan (*HTTP Range 206*), antrean berprioritas, multi-segmen opsional, pemulihan otomatis saat aplikasi/perangkat dibuka kembali, serta penyimpanan publik via **MediaStore (Scoped Storage)**.

---

## Fitur Utama

1. **Mesin Unduh HTTP/HTTPS Nyata (`HttpDownloadEngine`)**
   - Memvalidasi skema URL secara ketat (hanya `http` dan `https`) serta memblokir akses ke alamat internal/loopback (*SSRF protection*).
   - Melakukan pemeriksaan awal (*probe*) melalui permintaan `HEAD` / `GET Range: bytes=0-0` untuk membaca ukuran file (`Content-Length` / `Content-Range`), tipe konten (`MIME type`), nama file (`Content-Disposition`), dan dukungan resume (`Accept-Ranges: bytes`).
   - Menulis aliran byte secara bertahap menggunakan buffer `32 KB` ke file sementara `.part` tanpa pernah memuat seluruh file besar ke dalam RAM.
   - Menghitung **persentase progres nyata**, **kecepatan transfer aktual**, dan **estimasi waktu tersisa (ETA)** hanya berdasarkan byte yang benar-benar diterima.
   - Memverifikasi ukuran byte akhir dan menghitung **SHA-256 Checksum** sebelum memindahkan file `.part` ke folder publik `Download/<Subfolder>`.

2. **Jeda & Lanjutkan (*Pause & Resume*) dengan Validasi Server**
   - Menyimpan offset byte terakhir, lokasi file `.part`, `ETag`, dan `Last-Modified` di Room Database.
   - Mengirimkan header `Range: bytes=<offset>-` dan `If-Range` ketika melanjutkan unduhan.
   - Memvalidasi respons `HTTP 206 Partial Content` dan `Content-Range`. Apabila server mengabaikan `Range` dan membalas `HTTP 200 OK`, mesin unduh tidak menggabungkan data baru ke file parsial lama, melainkan memulai ulang dari byte `0` secara aman dan menampilkan pemberitahuan kepada pengguna.

3. **Manajemen Antrean & Transfer Bersamaan (`DownloadQueueManager`)**
   - Mendukung penambahan satu atau banyak URL sekaligus.
   - Menjalankan hingga **3 unduhan bersamaan** secara default (dapat diubah antara 1 hingga 5 file di menu **Pengaturan**).
   - Menjamin setiap tugas unduhan hanya memiliki **satu worker aktif** (*single active owner*) melalui sinkronisasi `Mutex` dan peta pekerjaan atomik.
   - Mendukung pengaturan prioritas antrean (`+` / `-`), jeda semua, lanjutkan semua, batalkan, dan hapus riwayat tanpa menghapus file selesai secara tidak sengaja.

4. **Eksekusi Latar Belakang & Pemulihan Otomatis**
   - Menggunakan `DownloadForegroundService` (`foregroundServiceType="dataSync"`) dengan notifikasi progres real-time serta tombol **Jeda** dan **Batalkan** langsung dari notifikasi.
   - Menggunakan `QueueRecoveryWorker` (`WorkManager`) dan `BootReceiver` untuk memulihkan antrean ketika jaringan kembali tersedia atau setelah perangkat dihidupkan ulang.

5. **Deteksi Tautan Clipboard & Integrasi Android Sharesheet**
   - Memeriksa clipboard secara lokal dan transparan saat aplikasi berada di foreground serta menampilkan kartu saran unduhan tanpa pernah mengunduh otomatis tanpa persetujuan.
   - Menerima tautan dari browser atau aplikasi lain melalui menu **Bagikan (Sharesheet)** (`ACTION_SEND` & `ACTION_VIEW`).
   - Memberikan peringatan yang jelas apabila tautan mengarah ke halaman web HTML (`text/html`), bukan file unduhan langsung.

---

## Struktur Proyek

```text
├── .github/workflows/
│   └── build-apk.yml                # Pipeline CI/CD GitHub Actions untuk uji & build APK otomatis
├── app/
│   ├── build.gradle.kts             # Konfigurasi modul aplikasi (applicationId = com.udadownloader.app)
│   ├── proguard-rules.pro           # Aturan ProGuard untuk Room & OkHttp
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml  # Deklarasi permission, Sharesheet intent-filter, Service, Receiver, FileProvider
│       │   ├── java/com/example/
│       │   │   ├── MainActivity.kt                      # Entry point Compose, Bottom Navigation, Sharesheet & Clipboard
│       │   │   ├── UdaDownloaderApp.kt                  # Inisialisasi Room, Settings, QueueManager & NotificationChannel
│       │   │   ├── data/
│       │   │   │   ├── AppDatabase.kt                   # Room Database + Migrasi eksplisit non-destruktif (MIGRATION_1_2)
│       │   │   │   ├── DownloadDao.kt                   # DAO reaktif berbasis Kotlin Flow
│       │   │   │   ├── DownloadStatus.kt                # Enum status unduhan & deteksi kategori file
│       │   │   │   ├── DownloadTaskEntity.kt            # Entitas Room beserta kalkulasi progres & ETA nyata
│       │   │   │   └── SettingsRepository.kt            # Penyimpanan preferensi pengguna berbasis Jetpack DataStore
│       │   │   ├── engine/
│       │   │   │   ├── DownloadQueueManager.kt          # Pengelola antrean, batas konkurensi, retry & rekonsiliasi
│       │   │   │   ├── HttpDownloadEngine.kt            # Mesin HTTP/HTTPS streaming, Range 206, Multi-Segmen & SHA-256
│       │   │   │   ├── MediaStorePublisher.kt           # Publikasi Scoped Storage (MediaStore.Downloads) & pembersih .part
│       │   │   │   └── UrlValidator.kt                  # Validasi URL, proteksi SSRF, parser Content-Disposition & HTTP status
│       │   │   ├── service/
│       │   │   │   ├── BootReceiver.kt                  # Pemulihan antrean saat perangkat selesai reboot
│       │   │   │   ├── DownloadForegroundService.kt     # Foreground Service dengan notifikasi progres & aksi Jeda/Batal
│       │   │   │   ├── DownloadNotificationReceiver.kt  # BroadcastReceiver untuk aksi tombol pada notifikasi
│       │   │   │   └── QueueRecoveryWorker.kt           # WorkManager Worker untuk retry & pemulihan berbasis jaringan
│       │   │   ├── ui/
│       │   │   │   ├── DownloaderViewModel.kt           # ViewModel utama (MVVM)
│       │   │   │   ├── components/
│       │   │   │   │   └── DownloadItemCard.kt          # Kartu item unduhan lengkap dengan progres, kontrol & dialog detail
│       │   │   │   ├── screens/
│       │   │   │   │   ├── HomeScreen.kt                # Halaman Beranda
│       │   │   │   │   ├── QueueScreen.kt               # Halaman Unduhan & Antrean
│       │   │   │   │   ├── CompletedScreen.kt           # Halaman Selesai
│       │   │   │   │   └── SettingsScreen.kt            # Halaman Pengaturan
│       │   │   │   └── theme/
│       │   │   │       ├── Color.kt                     # Palet warna Material 3 & indikator status
│       │   │   │       ├── Theme.kt                     # Tema terang, gelap & sistem
│       │   │   │       └── Type.kt                      # Tipografi Plus Jakarta Sans & JetBrains Mono
│       │   │   └── util/
│       │   │       └── FormatUtils.kt                   # Pemformatan ukuran byte, kecepatan, ETA, dan tanggal
│       │   └── res/                                     # String Bahasa Indonesia, ikon adaptif, font, dan file_paths.xml
│       └── test/java/com/example/
│           ├── ExampleUnitTest.kt                       # Unit test validasi URL, status HTTP, progres, duplikat, Range 206 & 200
│           └── ExampleRobolectricTest.kt                # Pengujian Robolectric untuk antrean, retry 503, jeda/batal & pemulihan
├── build.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
└── settings.gradle.kts
```

---

## Langkah Uji Manual pada Perangkat Android

1. **Uji Validasi & Pemeriksaan Info File (Probe)**
   - Buka halaman **Beranda**, masukkan URL tidak valid seperti `ftp://contoh.com/file.zip` atau `http://localhost/test`. Pastikan pesan kesalahan Bahasa Indonesia tampil dan tombol unduh menolak URL tersebut.
   - Masukkan URL file langsung yang valid (misalnya file PDF atau ZIP publik), lalu tekan **Cek Info**. Pastikan nama file, ukuran byte, tipe MIME, dan status dukungan `HTTP Range` ditampilkan.
2. **Uji Transfer Nyata, Jeda, dan Lanjutkan (Resume)**
   - Tekan **Unduh** pada file berukuran menengah/besar (10–100 MB).
   - Amati progres byte nyata, persentase, kecepatan (`KB/dtk` atau `MB/dtk`), dan sisa waktu.
   - Tekan **Jeda**, tunggu beberapa detik, lalu tekan **Lanjutkan**. Pastikan unduhan meneruskan dari jumlah byte terakhir tanpa mengulang dari nol (jika server mendukung `Range`).
3. **Uji Antrean & Batas Unduhan Bersamaan**
   - Tekan **Banyak URL** di halaman Beranda dan masukkan 5 URL sekaligus.
   - Buka tab **Unduhan**: pastikan maksimal 3 file diunduh bersamaan (sesuai pengaturan awal) dan 2 file lainnya berstatus **Dalam Antrean**. Ubah prioritas tugas di antrean menggunakan tombol panah atas/bawah.
4. **Uji Latar Belakang & Notifikasi**
   - Saat unduhan sedang berjalan, tekan tombol Home pada HP Android untuk memindahkan aplikasi ke latar belakang.
   - Buka panel notifikasi Android: pastikan notifikasi **Uda Downloader** menampilkan progres dan tombol **Jeda** / **Batalkan** berfungsi.
5. **Uji Penyimpanan Publik & Pembukaan File**
   - Setelah unduhan berstatus **Selesai**, buka tab **Selesai** lalu tekan **Buka File** atau **Bagikan**.
   - Buka aplikasi *Files* / *File Manager* bawaan HP pada folder `Download/UdaDownloader` dan pastikan file fisik benar-benar tersimpan di sana.

---

## Panduan Langkah demi Langkah: Mengunggah Proyek ke GitHub dari HP Android & Membangun APK via GitHub Actions

### Langkah 1: Siapkan Repositori di GitHub (Melalui Browser HP)
1. Buka browser (Chrome/Firefox) di HP Android Anda dan masuk ke akun [GitHub](https://github.com).
2. Ketuk ikon **+** di pojok kanan atas, lalu pilih **New repository**.
3. Isi nama repositori, misalnya `uda-downloader`, pilih **Public** atau **Private**, lalu ketuk **Create repository**.

### Langkah 2: Kirim Kode Proyek ke GitHub
* **Cara A (Langsung dari Google AI Studio di Browser HP):**
  1. Buka menu **Settings / Export** di bagian atas antarmuka AI Studio.
  2. Pilih **Push to GitHub** atau **Export to ZIP**.
  3. Jika memilih **Push to GitHub**, hubungkan akun GitHub Anda dan pilih repositori `uda-downloader` pada branch `main`.
* **Cara B (Menggunakan Aplikasi Termux / Git di HP Android dari File ZIP):**
  1. Ekstrak file ZIP proyek ke folder di HP Anda.
  2. Jalankan perintah berikut di terminal:
     ```bash
     git init
     git branch -M main
     git add .
     git commit -m "Initial commit: Uda Downloader Android app"
     git remote add origin https://github.com/<USERNAME_ANDA>/uda-downloader.git
     git push -u origin main
     ```

### Langkah 3: Jalankan Build APK Otomatis di GitHub Actions
1. Begitu kode didorong (*push*) ke branch `main` atau `master`, workflow **Build Uda Downloader APK** (`.github/workflows/build-apk.yml`) akan berjalan secara otomatis.
2. Anda juga dapat memicunya secara manual kapan saja dari HP:
   - Buka repositori Anda di GitHub.
   - Ketuk tab **Actions**.
   - Pilih workflow **Build Uda Downloader APK** di daftar kiri/atas.
   - Ketuk tombol **Run workflow** -> pilih branch `main` -> ketuk **Run workflow**.

### Langkah 4: Unduh File APK dari Bagian Artifacts
1. Pada tab **Actions** di repositori GitHub Anda, ketuk baris *workflow run* paling atas yang sudah berstatus selesai (ikon centang hijau ✅).
2. Gulir ke bagian paling bawah halaman ringkasan (*Summary*) hingga menemukan bagian **Artifacts**.
3. Ketuk **`Uda-Downloader-Debug-APK`** untuk mengunduh file arsip `.zip` yang berisi APK.
4. Buka file `.zip` yang telah terunduh di aplikasi File Manager HP Android Anda, ekstrak file **`app-debug.apk`**, lalu ketuk file APK tersebut untuk memasang (**Install**) **Uda Downloader** di HP Android Anda.
