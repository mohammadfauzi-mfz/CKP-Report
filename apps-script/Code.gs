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

const HEADERS = [
  'Waktu', 'Estate', 'Afdeling', 'Blok', 'Activity',
  'Losses: Nama Ancak', 'Losses: Luas CKP (Ha)', 'Losses: Brondol Tinggal (Kg)', 'Losses: Janjang Tinggal (JJG)',
  'Losses: BJR (Kg)', 'Losses: Losses JJG (Kg)', 'Losses: Total Losses (Kg)', 'Losses: Losses/Ha (Kg/Ha)',
  'Grading: No TPH', 'Grading: Nama Pemanen', 'Grading: Kode Pemanen', 'Grading: Ripe (JJG)', 'Grading: Ripe (%)', 'Grading: Mengkal (JJG)', 'Grading: Mengkal (%)',
  'Grading: Mentah (JJG)', 'Grading: Mentah (%)', 'Grading: Overripe (JJG)', 'Grading: Overripe (%)',
  'Grading: Total JJG', 'Grading: BJR (Kg)', 'Grading: Tonase (Ton)', 'Grading: Brondol (Kg)', 'Grading: Brondol (%)', 'Grading: Koordinat',
  'Kalibrasi: No TPH', 'Kalibrasi: Nama Pemanen', 'Kalibrasi: Kode Pemanen', 'Kalibrasi: Jumlah Janjang (JJG)', 'Kalibrasi: Tonase (Kg)', 'Kalibrasi: BJR (Kg)', 'Kalibrasi: Koordinat',
  'Problem / Temuan', 'Perbaikan', 'Cahaya (Lux)', 'Latitude', 'Longitude', 'Koordinat (salin)', 'Link Google Maps',
  'Link Foto', 'Pelapor', 'ID Laporan'
];
const COL_FOTO = HEADERS.indexOf('Link Foto') + 1;
const COL_ID = HEADERS.length;
const PERCENT_COLS = ['Grading: Ripe (%)', 'Grading: Mengkal (%)', 'Grading: Mentah (%)',
                      'Grading: Overripe (%)', 'Grading: Brondol (%)'].map(function (h) { return HEADERS.indexOf(h) + 1; });

function doGet(e) {
  const p = (e && e.parameter) || {};
  if (!p.action) return json_({ ok: true, info: 'CKP Report aktif' });
  try {
    if (p.token !== TOKEN) return json_({ ok: false, error: 'Kode rahasia salah' });
    if (p.action === 'list') return rekap_(p);
    if (p.action === 'foto') return foto_(p);
    return json_({ ok: false, error: 'Aksi tidak dikenal' });
  } catch (err) {
    return json_({ ok: false, error: String(err) });
  }
}

const MAX_BARIS = 500;

function waktuTeks_(v) {
  if (v instanceof Date) return Utilities.formatDate(v, 'Asia/Makassar', 'yyyy-MM-dd HH:mm:ss');
  return String(v === undefined || v === null ? '' : v);
}

/** Ambil baris laporan sesuai filter: from/to (yyyy-MM-dd), estate, afdeling, blok, pelapor. */
function rekap_(p) {
  const sh = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(SHEET_NAME);
  if (!sh || sh.getLastRow() < 2) return json_({ ok: true, headers: HEADERS, rows: [], total: 0, terpotong: false });
  const data = sh.getRange(2, 1, sh.getLastRow() - 1, HEADERS.length).getValues();
  const ix = function (h) { return HEADERS.indexOf(h); };
  const cocok = function (nilai, kunci) {
    return !kunci || String(nilai).toUpperCase().indexOf(String(kunci).toUpperCase()) >= 0;
  };
  const rows = [];
  let total = 0;
  for (let i = data.length - 1; i >= 0; i--) {            // terbaru dulu
    const r = data[i];
    const w = waktuTeks_(r[0]);
    const tgl = w.substring(0, 10);
    if (p.from && tgl < p.from) continue;
    if (p.to && tgl > p.to) continue;
    if (!cocok(r[ix('Estate')], p.estate) || !cocok(r[ix('Afdeling')], p.afdeling) || !cocok(r[ix('Blok')], p.blok)) continue;
    if (p.pelapor && String(r[ix('Pelapor')]).toUpperCase() !== String(p.pelapor).toUpperCase()) continue;
    total++;
    if (rows.length < MAX_BARIS) {
      const v = r.map(function (c) { return c instanceof Date ? waktuTeks_(c) : c; });
      v[0] = w;
      rows.push(v);
    }
  }
  rows.reverse();                                          // urut lama -> baru di Excel
  return json_({ ok: true, headers: HEADERS, rows: rows, total: total, terpotong: total > rows.length });
}

/** Kirim foto (base64) milik laporan dengan ID tertentu, dibaca dari link Drive di sheet. */
function foto_(p) {
  const sh = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(SHEET_NAME);
  if (!sh || sh.getLastRow() < 2) return json_({ ok: false, error: 'Data kosong' });
  const ids = sh.getRange(2, COL_ID, sh.getLastRow() - 1, 1).getValues();
  for (let i = 0; i < ids.length; i++) {
    if (ids[i][0] === p.id) {
      const url = String(sh.getRange(i + 2, COL_FOTO).getValue() || '');
      const m = url.match(/[-\w]{25,}/);
      if (!m) return json_({ ok: true, b64: '' });
      const blob = DriveApp.getFileById(m[0]).getBlob();
      return json_({ ok: true, b64: Utilities.base64Encode(blob.getBytes()) });
    }
  }
  return json_({ ok: true, b64: '' });
}

function prepareSheet_(ss) {
  let sh = ss.getSheetByName(SHEET_NAME);
  // Sheet versi lama (kolom berbeda): ganti namanya agar data lama aman, lalu buat sheet baru
  if (sh && sh.getLastRow() > 0 && sh.getRange(1, 1, 1, HEADERS.length).getValues()[0].join('|') !== HEADERS.join('|')) {
    sh.setName(SHEET_NAME + ' (lama ' + Utilities.formatDate(new Date(), 'Asia/Makassar', 'yyyyMMdd-HHmmss') + ')');
    sh = null;
  }
  if (!sh) sh = ss.insertSheet(SHEET_NAME);
  if (sh.getLastRow() === 0) {
    sh.appendRow(HEADERS);
    sh.getRange(1, 1, 1, HEADERS.length).setFontWeight('bold');
    sh.setFrozenRows(1);
  }
  return sh;
}

function salin_(lat, lon) {
  if (lat === undefined || lat === null || lat === '' || lon === undefined || lon === null || lon === '') return '';
  return Number(lat).toFixed(6) + ',' + Number(lon).toFixed(6);
}

function num_(v) {
  return (v === undefined || v === null || v === '') ? '' : Number(v);
}

function doPost(e) {
  const lock = LockService.getScriptLock();
  try {
    const d = JSON.parse(e.postData.contents);
    if (d.token !== TOKEN) return json_({ ok: false, error: 'Kode rahasia salah' });

    lock.waitLock(30000);
    const tz = 'Asia/Makassar';

    // Sheet tujuan
    const ss = SpreadsheetApp.getActiveSpreadsheet();
    const sh = prepareSheet_(ss);

    // Cegah data ganda: jika laporan dengan ID yang sama sudah tercatat (mis. respon sebelumnya terputus
    // karena sinyal hilang), jangan dicatat lagi - cukup kembalikan link foto yang sudah ada.
    if (d.id && sh.getLastRow() > 1) {
      const ids = sh.getRange(2, COL_ID, sh.getLastRow() - 1, 1).getValues();
      for (let i = 0; i < ids.length; i++) {
        if (ids[i][0] === d.id) {
          return json_({ ok: true, duplicate: true, fotoUrl: String(sh.getRange(i + 2, COL_FOTO).getValue() || '') });
        }
      }
    }

    // Waktu kejadian (dari HP, bukan waktu upload) agar laporan antrean tetap bercatat waktu yang benar
    const waktu = d.waktu || Utilities.formatDate(new Date(), tz, 'yyyy-MM-dd HH:mm:ss');

    // 1) Simpan foto ke Drive
    let fotoUrl = '';
    if (d.fotoBase64) {
      const stamp = String(waktu).replace(/[^0-9]/g, '').substring(0, 14);
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
    const L = d.losses || {}, G = d.grading || {}, K = d.kalibrasi || {};
    const lat = num_(d.lat), lon = num_(d.lon);
    const salin = salin_(lat, lon);
    sh.appendRow([
      waktu, d.estate, d.afdeling, d.blok, d.activity || '',
      L.namaAncak || '', num_(L.luas), num_(L.brondol), num_(L.janjang), num_(L.bjr),
      num_(L.lossesJjg), num_(L.total), num_(L.perHa),
      G.noTph || '', G.namaPemanen || '', G.kodePemanen || '', num_(G.ripe), num_(G.pRipe), num_(G.mengkal), num_(G.pMengkal),
      num_(G.mentah), num_(G.pMentah), num_(G.overripe), num_(G.pOverripe),
      num_(G.total), num_(G.bjr), num_(G.tonase), num_(G.brondol), num_(G.pBrondol), salin_(G.lat, G.lon),
      K.noTph || '', K.namaPemanen || '', K.kodePemanen || '', num_(K.janjang), num_(K.tonaseKg), num_(K.bjr), salin_(K.lat, K.lon),
      d.problem, d.perbaikan || '', num_(d.lux), lat, lon, salin, d.mapsUrl, fotoUrl, d.pelapor || '', d.id || ''
    ]);
    const row = sh.getLastRow();
    PERCENT_COLS.forEach(function (c) { sh.getRange(row, c).setNumberFormat('0.00%'); });
    // Koordinat sebagai teks agar tidak diubah Sheets (mis. koma jadi desimal)
    ['Koordinat (salin)', 'Grading: Koordinat', 'Kalibrasi: Koordinat'].forEach(function (h) {
      const c = HEADERS.indexOf(h) + 1;
      const v = sh.getRange(row, c).getValue();
      sh.getRange(row, c).setNumberFormat('@').setValue(v);
    });

    return json_({ ok: true, fotoUrl: fotoUrl });
  } catch (err) {
    return json_({ ok: false, error: String(err) });
  } finally {
    try { lock.releaseLock(); } catch (x) {}
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
