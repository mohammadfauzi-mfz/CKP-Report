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

## v2.7 - Pembaruan dari GitHub
Pengaturan > "Cek & Perbarui Aplikasi". Setiap build di GitHub Actions otomatis membuat Release `v<versionName>` berisi APK. Aplikasi membaca Release terbaru (repo `mohammadfauzi-mfz/CKP-Report`, harus publik), mengunduh APK, lalu memasangnya. Naikkan `versionName` di app/build.gradle.kts setiap rilis. Pengecekan otomatis sekali sehari saat aplikasi dibuka.
