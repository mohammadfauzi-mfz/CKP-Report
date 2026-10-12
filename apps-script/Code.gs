/**
 * CKP Report - penerima data dari aplikasi Android.
 * Pasang di Google Spreadsheet: menu Ekstensi > Apps Script, tempel seluruh kode ini.
 */

// (Opsional) ID folder Google Drive tujuan foto. Ada di alamat folder: drive.google.com/drive/folders/<ID>
// Jika dikosongkan, folder "CKP Report Foto" dibuat otomatis di My Drive.
const FOLDER_ID = '';

// Nama sheet (tab) tujuan rekap. Dibuat otomatis jika belum ada.
const SHEET_NAME = 'CKP';

// Kode rahasia. HARUS sama dengan yang diisi di Pengaturan aplikasi. Ganti dengan kode Anda sendiri.
const TOKEN = 'GANTI-DENGAN-KODE-RAHASIA';

const HEADERS = ['Waktu', 'Estate', 'Afdeling', 'Blok', 'Problem / Temuan',
                 'Latitude', 'Longitude', 'Link Google Maps', 'Link Foto', 'Pelapor'];

function doGet() {
  return json_({ ok: true, info: 'CKP Report aktif' });
}

function doPost(e) {
  try {
    const d = JSON.parse(e.postData.contents);
    if (d.token !== TOKEN) return json_({ ok: false, error: 'Kode rahasia salah' });

    const tz = 'Asia/Makassar';
    const now = new Date();

    // 1) Simpan foto ke Drive
    let fotoUrl = '';
    if (d.fotoBase64) {
      const stamp = Utilities.formatDate(now, tz, 'yyyyMMdd_HHmmss');
      const nama = stamp + '_' + clean_([d.estate, d.afdeling, d.blok].join('_')) + '.jpg';
      const blob = Utilities.newBlob(Utilities.base64Decode(d.fotoBase64), 'image/jpeg', nama);
      const file = getFolder_().createFile(blob);
      try {
        file.setSharing(DriveApp.Access.ANYONE_WITH_LINK, DriveApp.Permission.VIEW);
      } catch (err1) {
        try { file.setSharing(DriveApp.Access.DOMAIN_WITH_LINK, DriveApp.Permission.VIEW); } catch (err2) {}
      }
      fotoUrl = file.getUrl();
    }

    // 2) Catat ke spreadsheet
    const ss = SpreadsheetApp.getActiveSpreadsheet();
    let sh = ss.getSheetByName(SHEET_NAME);
    if (!sh) sh = ss.insertSheet(SHEET_NAME);
    if (sh.getLastRow() === 0) {
      sh.appendRow(HEADERS);
      sh.getRange(1, 1, 1, HEADERS.length).setFontWeight('bold');
      sh.setFrozenRows(1);
    }
    sh.appendRow([
      Utilities.formatDate(now, tz, 'yyyy-MM-dd HH:mm:ss'),
      d.estate, d.afdeling, d.blok, d.problem,
      d.lat, d.lon, d.mapsUrl, fotoUrl, d.pelapor || ''
    ]);

    return json_({ ok: true, fotoUrl: fotoUrl });
  } catch (err) {
    return json_({ ok: false, error: String(err) });
  }
}

function getFolder_() {
  if (FOLDER_ID) return DriveApp.getFolderById(FOLDER_ID);
  const it = DriveApp.getFoldersByName('CKP Report Foto');
  return it.hasNext() ? it.next() : DriveApp.createFolder('CKP Report Foto');
}

function clean_(s) {
  return String(s).replace(/[^A-Za-z0-9_\-]+/g, '-').substring(0, 60);
}

function json_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}
