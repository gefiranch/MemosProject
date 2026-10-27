# Financial Goals

## Konfigurasi Supabase

1. Pada proyek Supabase yang dipakai aplikasi, jalankan seluruh isi
   `supabase/migrations/202610270001_financial_goals.sql` melalui **SQL Editor**.
   Alternatif untuk proyek yang telah ditautkan ke Supabase CLI: `supabase db push`.
   Migrasi ini dijalankan sekali; bila tabel `goals` sudah dibuat manual, sesuaikan
   skemanya sebelum menjalankan migrasi. Migrasi tidak mengubah Auth atau Transactions.
2. Verifikasi tabel `public.goals` memiliki RLS aktif beserta empat policy `goals_*_own`.
   Target menggunakan `numeric(18,2)` dan wajib > 0; tabungan wajib >= 0.
   Pemilik baris wajib sama dengan `auth.uid()` untuk semua operasi.
3. Verifikasi bucket **goal-images** bersifat **Private**, memiliki batas 5 MiB,
   MIME `image/jpeg`, `image/png`, `image/webp`, dan empat policy `goal_images_*_own`
   di `storage.objects`. Policy yang dibuat hanya berlaku untuk bucket ini.
   Bila proyek sebelumnya memiliki policy Storage yang memberikan akses ke semua
   bucket/pengguna, batasi policy tersebut agar tidak ikut mengizinkan `goal-images`.
4. Gunakan konfigurasi yang sudah ada di `local.properties`:

   ```properties
   sdk.dir=C\:\\Android\\Sdk
   SUPABASE_URL=https://YOUR_PROJECT.supabase.co
   SUPABASE_PUBLISHABLE_KEY=YOUR_CLIENT_PUBLISHABLE_KEY
   ```

   Gunakan publishable/anon client key, jangan service-role/secret key. File ini
   sudah masuk `.gitignore`. Login/Register dan pengaturan provider Auth tetap
   menggunakan implementasi yang sudah ada.
5. Sync Gradle, build, login, lalu buka tab **Goals**. Tidak perlu mengaktifkan
   Realtime atau menjadikan bucket publik.

Referensi: [akses Storage dan RLS](https://supabase.com/docs/guides/storage/security/access-control),
[bucket privat](https://supabase.com/docs/guides/storage/buckets/fundamentals),
[download Kotlin terautentikasi](https://supabase.com/docs/reference/kotlin/storage-from-download).

## Implementasi

- `GoalsScreen.kt`: grid adaptif, detail, form tambah/edit, photo picker Android,
  preview, retry gambar, konfirmasi hapus, snackbar, loading/error/empty state.
- `GoalsViewModel.kt`: state UI, navigasi internal Goals, validasi, pemrosesan
  gambar dan operasi CRUD. Scope ViewModel bertahan saat rotasi/pindah tab dan
  dibersihkan saat logout. State form belum disimpan bila proses Android dihentikan;
  semua goal yang sudah disimpan dimuat ulang dari Supabase.
- `GoalsRepository.kt`: akses tabel `goals` dan Storage melalui client/sesi yang
  sudah ada, filter pemilik, pagination, dan koordinasi penyimpanan gambar.
- `Goal.kt`: model serializable dan nominal `BigDecimal` agar tidak kehilangan
  presisi. Progress angka dapat melebihi 100%; progress bar dibatasi 0–100%.
- `GoalImageFiles.kt`: pembacaan file maksimal 5 MiB, pengecekan format/gambar,
  decode preview berukuran terbatas di background. Tidak meminta izin akses galeri.
- `GoalImageCleanup.kt`: antrean penghapusan lokal yang persisten dan dipisahkan
  per user, tanpa menyimpan token atau isi gambar.

Gambar disimpan dengan key `<user UUID>/<goal UUID>/<image UUID>.<ext>`.
Database hanya menyimpan `image_path`. Tampilan memakai `downloadAuthenticated`,
sehingga tidak bergantung pada public URL atau signed URL yang kedaluwarsa.
Gambar baru diunggah sebelum path goal diganti. File lama dihapus sesudah operasi
database berhasil. Kandidat cleanup ditulis sebelum request jaringan dan diperiksa
lagi terhadap baris goal sebelum penghapusan, termasuk bila request sebelumnya
timeout. Jika penghapusan Storage gagal, snackbar menjelaskan bahwa cleanup akan
dicoba ulang saat memuat/menyimpan/menghapus goals berikutnya. Antrean lokal ini
bertahan setelah aplikasi dibuka ulang, tetapi tidak setelah data aplikasi dihapus
atau aplikasi di-uninstall. Penghapusan akun langsung dari dashboard/server juga
memerlukan cleanup Storage oleh server/admin.

Jumlah terkumpul dapat diubah manual, boleh melampaui target, dan tidak membuat
atau mengubah Transactions. Nominal menerima paling banyak 16 digit dan 2 desimal;
masukkan tanpa pemisah ribuan, gunakan koma atau titik sebagai desimal.

### Kompatibilitas database yang sudah ada

Migrasi bawaan menggunakan kolom `name`. Database Mome lama yang menggunakan
`title` juga didukung: model membaca keduanya, dan Repository mencoba `title`
hanya ketika Supabase menolak `name` dengan `PGRST204` sebelum menulis data.
Pilihan kolom disimpan selama scope Repository aktif dan dipakai untuk edit
berikutnya. Tidak perlu mengganti nama kolom atau menghapus tabel lama.
Jika kolom lain tidak sesuai, aplikasi menampilkan pesan struktur database,
bukan menganggapnya sebagai kegagalan internet. Error debug di tag `MomeGoals`
hanya mencatat jenis error, kode, status, dan pesan server tanpa header/token.

## Verifikasi

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
```

Uji manual dengan dua akun Supabase:

1. Tambah goal tanpa gambar dan goal dengan gambar; buka detail dan tutup/buka
   ulang aplikasi. Data dan gambar tersimpan harus muncul lagi.
2. Edit semua field, ubah tabungan ke 0, sama dengan target, dan di atas target.
   Nilai progress harus benar, bar maksimal 100%, Transactions tetap sama.
3. Coba target 0/negatif, tabungan negatif, desimal > 2 digit, nama kosong, file
   non-gambar dan file > 5 MiB. Input harus ditolak; form tetap tersedia saat gagal.
4. Ganti gambar, simpan, lalu hapus gambar melalui form dan simpan lagi. Periksa
   Storage: file lama terhapus, hanya gambar aktif yang tetap dipakai.
5. Batalkan dialog hapus, lalu konfirmasi hapus. Baris goal dan gambar harus
   terhapus. Putuskan jaringan saat CRUD/download dan gunakan retry setelah online.
6. Logout lalu login sebagai akun lain. Goals akun pertama tidak boleh terlihat.
   Dengan JWT akun kedua, akses REST ke goal akun pertama harus menghasilkan nol
   baris untuk select/update/delete; insert dengan `user_id` pertama harus ditolak.
   Download/upload/update/delete object folder akun pertama juga harus ditolak.
   Gunakan client JWT, bukan SQL Editor yang berjalan sebagai admin.
7. Putar perangkat saat mengedit dan pindah tab lalu kembali ke Goals; form tetap
   tersedia. Logout harus membersihkan scope Goals.

Unit test mencakup presisi desimal, batas nominal, progress melewati target,
validasi form, operasi sukses/gagal, perubahan gambar, dan penghapusan terkonfirmasi.
RLS/Storage harus diverifikasi pada proyek Supabase sesudah migrasi diterapkan.

Uji Repository terhadap Supabase asli bersifat opt-in dan memerlukan sesi login
di emulator. Jalankan langsung melalui ADB agar instalasi dan sesi aplikasi tetap
tersimpan sesudah pengujian. Jangan memakai `connectedDebugAndroidTest` pada
emulator dengan sesi yang ingin dipertahankan karena runner dapat menghapus APK
beserta data lokal sesudah pengujian.

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
adb install -r -t app\build\outputs\apk\debug\app-debug.apk
adb install -r -t app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
# Login di aplikasi bila belum ada sesi, kemudian:
adb shell am instrument -w -r -e class com.example.memosproject.goals.GoalsRepositoryIntegrationTest -e goalsIntegration true com.example.memosproject.test/androidx.test.runner.AndroidJUnitRunner
```

Uji ini membuat goals sementara dengan UUID tersendiri, memverifikasi simpan,
pemuatan ulang, edit tabungan, upload/penggantian/penghapusan gambar privat,
dan hapus goal, lalu membersihkan baris serta gambar uji tersebut.
