# Mome

Aplikasi Android manajemen keuangan pribadi yang dibuat untuk proyek akhir Pemrograman Aplikasi Mobile. Menggunakan Kotlin, Jetpack Compose, ViewModel, dan Supabase.

## Status fitur

- Auth: register, login, dan logout.
- Goals: kelola target tabungan beserta gambar. Lihat `docs/financial-goals.md`.
- Journal: check-in harian, riwayat, edit/hapus, current streak dan longest streak.
- Transactions, Budgets, dan Bills: masih placeholder pada versi dasar ini.

## Menjalankan secara lokal

1. Buka project di Android Studio dan instal SDK yang diminta Gradle. Sync project.
2. Buat `local.properties` di root project (Android Studio dapat mengisinya dengan `sdk.dir`):

   ```properties
   sdk.dir=/path/ke/Android/sdk
   SUPABASE_URL=https://YOUR_PROJECT.supabase.co
   SUPABASE_PUBLISHABLE_KEY=YOUR_PUBLISHABLE_KEY
   ```

   Minta nilai Supabase kepada PM. Gunakan client publishable key, jangan service-role key. `local.properties` diabaikan Git; jangan commit password, token, atau file konfigurasi lokal.
3. Koordinasikan dengan PM sebelum menjalankan migrasi SQL dalam `supabase/migrations` di proyek Supabase bersama. Migrasi Goals membutuhkan bucket dan policies seperti dijelaskan di `docs/financial-goals.md`. Migrasi Journal membuat tabel baru dan tidak boleh dijalankan jika tabel serupa sudah ada tanpa penyesuaian skema.
4. Jalankan aplikasi, register akun pribadi, lalu login. Implementasi auth saat ini bergantung pada pengaturan Confirm email di Supabase dan belum menyediakan reset password.

## Journal dan streak

Di tab Journal, buat maksimal satu check-in per tanggal lokal perangkat. Refleksi dan rencana wajib diisi. Riwayat menampilkan tanggal terbaru lebih dahulu. Streak aktif menghitung tanggal berurutan berakhir hari ini atau kemarin; longest streak adalah rangkaian terpanjang di riwayat. Penghapusan membuat streak dihitung ulang.

Status dipilih manual sementara. Integrasi otomatis dengan transaksi/budget belum tersedia karena kedua fitur dan skema datanya belum ada pada versi dasar. Setelah keduanya siap, sepakati bersama tim cara menggabungkan kategori, periode, total belanja, ambang hampir melampaui, serta perilaku saat data belum cukup atau terjadi perubahan; jangan menyimpulkan status otomatis tanpa data yang sesuai.

## Pengembangan dan pengujian

Gunakan branch per fitur. Periksa `git status` dan isi perubahan sebelum membuat commit; jangan memasukkan `local.properties` atau credentials. Contoh pengujian lokal: `./gradlew :app:testDebugUnitTest :app:assembleDebug`. Pengujian CRUD dan RLS memerlukan migrasi yang diterapkan dan dua akun uji di Supabase; verifikasi pembatasan satu check-in, isolasi antar akun, dan penanganan jaringan secara manual.
