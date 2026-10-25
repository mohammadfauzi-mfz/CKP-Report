# CKP Report (Android)
Aplikasi: folder `app` (build lewat GitHub Actions / Android Studio).
Penghubung Google Sheets + Drive: `apps-script/Code.gs` (lihat panduan di chat).

## v2.4 - Rekap Excel
Menu Pengaturan > "Unduh Rekap Excel" menarik laporan terupload dari Spreadsheet (lengkap dengan foto) menjadi file .xlsx, lalu "Share Excel" ke WhatsApp.
Wajib: tempel ulang `apps-script/Code.gs` ke Apps Script, lalu Deploy > Kelola deployment > Edit > Versi baru (URL tetap sama). Isi TOKEN sama seperti sebelumnya.

## v2.5
Share satu tanda (ketuk tanda di peta > Share): WhatsApp (teks + foto), JSON (bisa diimport lewat Pulihkan Data), KML, KMZ + foto.

## v2.6
File JSON/KML/KMZ/GPX/GeoJSON/CSV/zip (backup, paket peta, shapefile) dan GeoPDF bisa dibuka atau dibagikan langsung ke CKP Report dari WhatsApp / File Manager (Buka dengan > CKP Report).

## v2.8 - Sinkronisasi Tim

Temuan bersama satu afdeling: siapa pun bisa menandai temuan, anggota lain langsung menerimanya, dan melapor perbaikan (foto wajib) sampai TUNTAS.

1. Tempel ulang `apps-script/Code.gs` ke Apps Script (TOKEN harus sama dengan yang lama), lalu Deploy > Kelola deployment > Edit > Versi baru (URL tetap).
2. Buat sheet **Pengguna** dengan kolom: `NPK | Nama | Peran | Estate | Afdeling`.
   Peran: Anggota, Supervisi, Manager, Admin. Manager/Admin melihat semua afdeling. Estate/Afdeling boleh beberapa nilai dipisah koma, atau `SEMUA`.
3. Di aplikasi: Pengaturan > Tim Afdeling > isi NPK > Simpan.
4. Tab Peta > **Tim**: daftar temuan, filter, Tandai Tuntas (foto + catatan), Share WA. Saat menandai di peta, centang "Kirim ke Tim".

**Admin** juga punya menu Pengaturan > Kelola Pengguna Tim: tambah, ubah, dan hapus pengguna langsung dari HP (tanpa membuka spreadsheet). Hanya peran Admin yang bisa; NPK Admin pertama tetap diisi manual di sheet Pengguna.

Titik merah = outstanding, hijau = tuntas. Sheet "Temuan" dibuat otomatis.

## v2.7 - Pembaruan dari GitHub
Pengaturan > "Cek & Perbarui Aplikasi". Setiap build di GitHub Actions otomatis membuat Release `v<versionName>` berisi APK. Aplikasi membaca Release terbaru (repo `mohammadfauzi-mfz/CKP-Report`, harus publik), mengunduh APK, lalu memasangnya. Naikkan `versionName` di app/build.gradle.kts setiap rilis. Pengecekan otomatis sekali sehari saat aplikasi dibuka.
