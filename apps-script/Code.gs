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
  'Link Foto', 'Pelapor', 'ID Laporan',
  'Losses: Blok', 'Grading: Blok', 'Kalibrasi: Blok'   // ditambahkan di ujung agar sheet lama tidak perlu dibuat ulang
];
const COL_FOTO = HEADERS.indexOf('Link Foto') + 1;
const COL_ID = HEADERS.indexOf('ID Laporan') + 1;
const PERCENT_COLS = ['Grading: Ripe (%)', 'Grading: Mengkal (%)', 'Grading: Mentah (%)',
                      'Grading: Overripe (%)', 'Grading: Brondol (%)'].map(function (h) { return HEADERS.indexOf(h) + 1; });

function doGet(e) {
  const p = (e && e.parameter) || {};
  if (!p.action) return json_({ ok: true, info: 'CKP Report aktif' });
  try {
    if (p.token !== TOKEN) return json_({ ok: false, error: 'Kode rahasia salah' });
    if (p.action === 'list') return rekap_(p);
    if (p.action === 'foto') return foto_(p);
    if (p.action === 'tm_me') return tmMe_(p);
    if (p.action === 'tm_list') return tmList_(p);
    if (p.action === 'tm_foto') return tmFoto_(p);
    if (p.action === 'tm_users') return tmUsers_(p);
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
  // Sheet lama yang judul kolomnya hanya kurang di ujung: cukup ditambah judul barunya (data tetap di sheet yang sama).
  if (sh && sh.getLastRow() > 0) {
    const cur = sh.getRange(1, 1, 1, Math.max(sh.getLastColumn(), 1)).getValues()[0].map(String);
    while (cur.length && cur[cur.length - 1] === '') cur.pop();
    const awal = cur.length <= HEADERS.length && HEADERS.slice(0, cur.length).join('|') === cur.join('|');
    if (awal) {
      if (cur.length < HEADERS.length) {
        sh.getRange(1, cur.length + 1, 1, HEADERS.length - cur.length).setValues([HEADERS.slice(cur.length)]).setFontWeight('bold');
      }
    } else {
      sh.setName(SHEET_NAME + ' (lama ' + Utilities.formatDate(new Date(), 'Asia/Makassar', 'yyyyMMdd-HHmmss') + ')');
      sh = null;
    }
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

    if (d.action && String(d.action).indexOf('tm_') === 0) {
      lock.waitLock(30000);
      return tmPost_(d);
    }

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
      d.problem, d.perbaikan || '', num_(d.lux), lat, lon, salin, d.mapsUrl, fotoUrl, d.pelapor || '', d.id || '',
      L.blok || '', G.blok || '', K.blok || ''
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


// =====================================================================
// TIM: temuan bersama per afdeling (login dengan NPK)
// Sheet "Pengguna": NPK | Nama | Peran | Estate | Afdeling
//   Peran   : Anggota, Supervisi, Manager, Admin  (Manager & Admin melihat SEMUA estate/afdeling)
//   Estate / Afdeling : nama (boleh beberapa, pisahkan koma) atau SEMUA
// Sheet "Temuan": dibuat otomatis.
// =====================================================================

const SHEET_USER = 'Pengguna';
const SHEET_TEMUAN = 'Temuan';
const HEADERS_U = ['NPK', 'Nama', 'Peran', 'Estate', 'Afdeling'];
const HEADERS_T = [
  'ID', 'Waktu', 'Estate', 'Afdeling', 'Blok', 'Judul', 'Ikon', 'Keterangan', 'Volume', 'Satuan',
  'Latitude', 'Longitude', 'Akurasi (m)', 'Pembuat NPK', 'Pembuat', 'Status', 'Waktu Tuntas',
  'Penyelesai NPK', 'Penyelesai', 'Catatan Perbaikan', 'Foto Temuan', 'Foto Perbaikan',
  'Diubah (ms)', 'Dihapus', 'WaktuMs', 'TuntasMs', 'Koordinat (salin)', 'Link Google Maps'
];
const TC = {};
HEADERS_T.forEach(function (h, i) { TC[h] = i; });

function tmSheet_(nama, headers) {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  let sh = ss.getSheetByName(nama);
  if (!sh) sh = ss.insertSheet(nama);
  if (sh.getLastRow() === 0) {
    sh.appendRow(headers);
    sh.getRange(1, 1, 1, headers.length).setFontWeight('bold');
    sh.setFrozenRows(1);
  }
  return sh;
}

function normNpk_(v) {
  return String(v === undefined || v === null ? '' : v).trim().toUpperCase().replace(/^0+(?=.)/, '');
}

function daftar_(v) {
  return String(v === undefined || v === null ? '' : v).split(/[,;]+/).map(function (x) {
    return x.trim().toUpperCase();
  }).filter(function (x) { return x; });
}

function semua_(list) { return list.indexOf('SEMUA') >= 0 || list.indexOf('ALL') >= 0; }

/** Cari pengguna dari NPK. null bila tidak terdaftar. */
function tmUser_(npk) {
  const n = normNpk_(npk);
  if (!n) return null;
  const sh = tmSheet_(SHEET_USER, HEADERS_U);
  if (sh.getLastRow() < 2) return null;
  const data = sh.getRange(2, 1, sh.getLastRow() - 1, HEADERS_U.length).getValues();
  for (let i = 0; i < data.length; i++) {
    if (normNpk_(data[i][0]) === n) {
      const peran = String(data[i][2] || 'Anggota').trim();
      const pu = peran.toUpperCase();
      const luas = (pu === 'MANAGER' || pu === 'ADMIN');
      return {
        npk: String(data[i][0]).trim(), nama: String(data[i][1]).trim(), peran: peran,
        luas: luas, estate: daftar_(data[i][3]), afdeling: daftar_(data[i][4]),
        supervisi: (pu === 'SUPERVISI' || luas), admin: luas
      };
    }
  }
  return null;
}

function tmBolehLihat_(u, est, afd) {
  if (u.luas) return true;
  const e = String(est || '').trim().toUpperCase();
  const a = String(afd || '').trim().toUpperCase();
  const okE = semua_(u.estate) || u.estate.indexOf(e) >= 0;
  const okA = semua_(u.afdeling) || u.afdeling.indexOf(a) >= 0;
  return okE && okA;
}

function tmUserInfo_(u) {
  return { npk: u.npk, nama: u.nama, peran: u.peran, luas: u.luas, estate: u.estate, afdeling: u.afdeling };
}

/** Pastikan token tim + NPK sah. Mengembalikan {u} atau {err}. */
function tmAuth_(p) {
  if (p.token !== TOKEN) return { err: json_({ ok: false, code: 'AUTH', error: 'Kode rahasia salah' }) };
  const u = tmUser_(p.npk);
  if (!u) return { err: json_({ ok: false, code: 'NPK', error: 'NPK tidak terdaftar. Hubungi admin untuk didaftarkan di sheet Pengguna.' }) };
  return { u: u };
}

function tmMs_(v) { return v === '' || v === undefined || v === null ? 0 : Number(v); }

function tmText_(ms) {
  return ms ? Utilities.formatDate(new Date(ms), 'Asia/Makassar', 'yyyy-MM-dd HH:mm:ss') : '';
}

function tmRowObj_(r) {
  return {
    id: String(r[TC['ID']]), waktu: tmMs_(r[TC['WaktuMs']]), est: String(r[TC['Estate']]), afd: String(r[TC['Afdeling']]),
    blok: String(r[TC['Blok']]), judul: String(r[TC['Judul']]), icon: String(r[TC['Ikon']] || 'warn'),
    ket: String(r[TC['Keterangan']]), volume: Number(r[TC['Volume']]) || 0, satuan: String(r[TC['Satuan']]),
    lat: Number(r[TC['Latitude']]), lon: Number(r[TC['Longitude']]),
    acc: r[TC['Akurasi (m)']] === '' ? -1 : Number(r[TC['Akurasi (m)']]),
    pNpk: String(r[TC['Pembuat NPK']]), pNama: String(r[TC['Pembuat']]),
    status: String(r[TC['Status']] || 'OUTSTANDING'), tuntas: tmMs_(r[TC['TuntasMs']]),
    sNpk: String(r[TC['Penyelesai NPK']]), sNama: String(r[TC['Penyelesai']]),
    catatan: String(r[TC['Catatan Perbaikan']]),
    fotoT: !!r[TC['Foto Temuan']], fotoP: !!r[TC['Foto Perbaikan']],
    upd: tmMs_(r[TC['Diubah (ms)']]), del: String(r[TC['Dihapus']]) === '1'
  };
}

function tmCari_(sh, id) {
  if (sh.getLastRow() < 2) return -1;
  const ids = sh.getRange(2, 1, sh.getLastRow() - 1, 1).getValues();
  for (let i = 0; i < ids.length; i++) if (String(ids[i][0]) === String(id)) return i + 2;
  return -1;
}

function tmRow_(sh, rowNo) {
  return sh.getRange(rowNo, 1, 1, HEADERS_T.length).getValues()[0];
}

function tmSimpanFoto_(b64, nama) {
  const blob = Utilities.newBlob(Utilities.base64Decode(b64), 'image/jpeg', nama);
  const file = getFolder_().createFile(blob);
  try {
    file.setSharing(DriveApp.Access.ANYONE_WITH_LINK, DriveApp.Permission.VIEW);
  } catch (e1) {
    try { file.setSharing(DriveApp.Access.DOMAIN_WITH_LINK, DriveApp.Permission.VIEW); } catch (e2) {}
  }
  return file.getUrl();
}

function tmMe_(p) {
  const a = tmAuth_(p);
  if (a.err) return a.err;
  return json_({ ok: true, user: tmUserInfo_(a.u) });
}

// ---------- kelola pengguna (khusus peran Admin) ----------

const PERAN_OK = ['ANGGOTA', 'SUPERVISI', 'MANAGER', 'ADMIN'];

function tmAdmin_(u) { return String(u.peran).toUpperCase() === 'ADMIN'; }

function tmUsers_(p) {
  const a = tmAuth_(p);
  if (a.err) return a.err;
  if (!tmAdmin_(a.u)) return json_({ ok: false, code: 'FORBIDDEN', error: 'Hanya Admin yang boleh mengelola pengguna' });
  const sh = tmSheet_(SHEET_USER, HEADERS_U);
  const rows = [];
  if (sh.getLastRow() >= 2) {
    sh.getRange(2, 1, sh.getLastRow() - 1, HEADERS_U.length).getValues().forEach(function (r) {
      if (String(r[0]).trim() === '') return;
      rows.push({ npk: String(r[0]).trim(), nama: String(r[1]).trim(), peran: String(r[2] || 'Anggota').trim(),
                  estate: String(r[3] || '').trim(), afdeling: String(r[4] || '').trim() });
    });
  }
  return json_({ ok: true, rows: rows });
}

function tmUserPost_(d, u) {
  if (!tmAdmin_(u)) return json_({ ok: false, code: 'FORBIDDEN', error: 'Hanya Admin yang boleh mengelola pengguna' });
  const sh = tmSheet_(SHEET_USER, HEADERS_U);
  const npk = String(d.target === undefined || d.target === null ? '' : d.target).trim().toUpperCase();
  if (!npk) return json_({ ok: false, code: 'DATA', error: 'NPK pengguna wajib diisi' });
  // cari baris
  let rn = 0;
  if (sh.getLastRow() >= 2) {
    const col = sh.getRange(2, 1, sh.getLastRow() - 1, 1).getValues();
    for (let i = 0; i < col.length; i++) if (normNpk_(col[i][0]) === normNpk_(npk)) { rn = i + 2; break; }
  }
  if (d.action === 'tm_user_del') {
    if (normNpk_(npk) === normNpk_(u.npk)) return json_({ ok: false, code: 'DATA', error: 'Tidak bisa menghapus akun sendiri' });
    if (!rn) return json_({ ok: false, code: 'DATA', error: 'NPK tidak ditemukan' });
    sh.deleteRow(rn);
    return json_({ ok: true });
  }
  const nama = String(d.nama || '').trim();
  const peranU = String(d.peran || 'Anggota').trim().toUpperCase();
  if (!nama) return json_({ ok: false, code: 'DATA', error: 'Nama wajib diisi' });
  if (PERAN_OK.indexOf(peranU) < 0) return json_({ ok: false, code: 'DATA', error: 'Peran tidak dikenal' });
  const peran = peranU.charAt(0) + peranU.slice(1).toLowerCase();
  const luas = (peranU === 'MANAGER' || peranU === 'ADMIN');
  const estate = String(d.estate || '').trim().toUpperCase();
  const afdeling = String(d.afdeling || '').trim().toUpperCase();
  if (!luas && (!estate || !afdeling)) return json_({ ok: false, code: 'DATA', error: 'Estate dan Afdeling wajib diisi (atau SEMUA)' });
  if (rn && normNpk_(npk) === normNpk_(u.npk) && peranU !== 'ADMIN') {
    return json_({ ok: false, code: 'DATA', error: 'Tidak bisa menurunkan peran akun sendiri' });
  }
  if (!rn) rn = Math.max(sh.getLastRow(), 1) + 1;
  sh.getRange(rn, 1).setNumberFormat('@');
  sh.getRange(rn, 1, 1, HEADERS_U.length).setValues([[npk, nama, peran, luas && !estate ? 'SEMUA' : estate, luas && !afdeling ? 'SEMUA' : afdeling]]);
  return json_({ ok: true });
}

/** Daftar temuan yang boleh dilihat pengguna, yang diubah setelah `since` (ms). */
function tmList_(p) {
  const a = tmAuth_(p);
  if (a.err) return a.err;
  const u = a.u;
  const since = Number(p.since) || 0;
  const sh = tmSheet_(SHEET_TEMUAN, HEADERS_T);
  const now = new Date().getTime();
  const out = [];
  if (sh.getLastRow() >= 2) {
    const data = sh.getRange(2, 1, sh.getLastRow() - 1, HEADERS_T.length).getValues();
    for (let i = 0; i < data.length; i++) {
      const r = data[i];
      if (tmMs_(r[TC['Diubah (ms)']]) <= since) continue;
      if (!tmBolehLihat_(u, r[TC['Estate']], r[TC['Afdeling']])) continue;
      out.push(tmRowObj_(r));
    }
  }
  return json_({ ok: true, user: tmUserInfo_(u), now: now, rows: out });
}

function tmFoto_(p) {
  const a = tmAuth_(p);
  if (a.err) return a.err;
  const sh = tmSheet_(SHEET_TEMUAN, HEADERS_T);
  const rn = tmCari_(sh, p.id);
  if (rn < 0) return json_({ ok: true, b64: '' });
  const r = tmRow_(sh, rn);
  if (!tmBolehLihat_(a.u, r[TC['Estate']], r[TC['Afdeling']])) return json_({ ok: false, code: 'FORBIDDEN', error: 'Tidak boleh melihat temuan ini' });
  const url = String(r[TC[p.jenis === 'p' ? 'Foto Perbaikan' : 'Foto Temuan']] || '');
  const m = url.match(/[-\w]{25,}/);
  if (!m) return json_({ ok: true, b64: '' });
  return json_({ ok: true, b64: Utilities.base64Encode(DriveApp.getFileById(m[0]).getBlob().getBytes()) });
}

/** Tulis (create/status/hapus). Dipanggil dalam lock. */
function tmPost_(d) {
  try {
    const a = tmAuth_(d);
    if (a.err) return a.err;
    const u = a.u;
    if (d.action === 'tm_user_set' || d.action === 'tm_user_del') return tmUserPost_(d, u);
    const sh = tmSheet_(SHEET_TEMUAN, HEADERS_T);
    const now = new Date().getTime();

    if (d.action === 'tm_create') {
      const ada = tmCari_(sh, d.id);
      if (ada > 0) return json_({ ok: true, duplicate: true, row: tmRowObj_(tmRow_(sh, ada)) });   // kiriman ulang: jangan dobel
      if (!d.judul || !String(d.judul).trim()) return json_({ ok: false, code: 'DATA', error: 'Judul kosong' });
      if (!d.est || !d.afd) return json_({ ok: false, code: 'DATA', error: 'Estate dan Afdeling wajib diisi' });
      if (!tmBolehLihat_(u, d.est, d.afd)) return json_({ ok: false, code: 'FORBIDDEN', error: 'Anda tidak terdaftar di ' + d.est + ' / ' + d.afd });
      const waktuMs = Number(d.waktu) || now;
      let fotoUrl = '';
      if (d.fotoBase64) fotoUrl = tmSimpanFoto_(d.fotoBase64, 'TMN_' + d.id + '_t.jpg');
      const row = new Array(HEADERS_T.length).fill('');
      row[TC['ID']] = d.id; row[TC['Waktu']] = tmText_(waktuMs); row[TC['Estate']] = String(d.est).trim().toUpperCase();
      row[TC['Afdeling']] = String(d.afd).trim().toUpperCase(); row[TC['Blok']] = d.blok || ''; row[TC['Judul']] = d.judul;
      row[TC['Ikon']] = d.icon || 'warn'; row[TC['Keterangan']] = d.ket || '';
      row[TC['Volume']] = d.volume === '' || d.volume === undefined ? '' : Number(d.volume); row[TC['Satuan']] = d.satuan || '';
      row[TC['Latitude']] = Number(d.lat); row[TC['Longitude']] = Number(d.lon);
      row[TC['Akurasi (m)']] = (d.acc === undefined || d.acc === null || Number(d.acc) < 0) ? '' : Number(d.acc);
      row[TC['Pembuat NPK']] = u.npk; row[TC['Pembuat']] = u.nama; row[TC['Status']] = 'OUTSTANDING';
      row[TC['Foto Temuan']] = fotoUrl; row[TC['Diubah (ms)']] = now; row[TC['Dihapus']] = '';
      row[TC['WaktuMs']] = waktuMs;
      row[TC['Koordinat (salin)']] = Number(d.lat).toFixed(6) + ',' + Number(d.lon).toFixed(6);
      row[TC['Link Google Maps']] = 'https://www.google.com/maps?q=' + Number(d.lat).toFixed(6) + ',' + Number(d.lon).toFixed(6);
      sh.appendRow(row);
      const rn = sh.getLastRow();
      sh.getRange(rn, TC['Koordinat (salin)'] + 1).setNumberFormat('@').setValue(row[TC['Koordinat (salin)']]);
      return json_({ ok: true, row: tmRowObj_(tmRow_(sh, rn)) });
    }

    const rn = tmCari_(sh, d.id);
    if (rn < 0) return json_({ ok: false, code: 'NOTFOUND', error: 'Temuan tidak ditemukan di server' });
    const r = tmRow_(sh, rn);
    if (!tmBolehLihat_(u, r[TC['Estate']], r[TC['Afdeling']])) return json_({ ok: false, code: 'FORBIDDEN', error: 'Tidak boleh mengubah temuan ini' });

    if (d.action === 'tm_status') {
      const baru = String(d.status).toUpperCase() === 'TUNTAS' ? 'TUNTAS' : 'OUTSTANDING';
      if (baru === 'TUNTAS') {
        let fotoUrl = String(r[TC['Foto Perbaikan']] || '');
        if (d.fotoBase64) fotoUrl = tmSimpanFoto_(d.fotoBase64, 'TMN_' + d.id + '_p.jpg');
        const tMs = Number(d.waktuTuntas) || now;
        r[TC['Status']] = 'TUNTAS'; r[TC['Waktu Tuntas']] = tmText_(tMs); r[TC['TuntasMs']] = tMs;
        r[TC['Penyelesai NPK']] = u.npk; r[TC['Penyelesai']] = u.nama;
        r[TC['Catatan Perbaikan']] = d.catatan || ''; r[TC['Foto Perbaikan']] = fotoUrl;
      } else {
        r[TC['Status']] = 'OUTSTANDING'; r[TC['Waktu Tuntas']] = ''; r[TC['TuntasMs']] = '';
        r[TC['Penyelesai NPK']] = ''; r[TC['Penyelesai']] = ''; r[TC['Catatan Perbaikan']] = '';
        r[TC['Foto Perbaikan']] = '';
      }
      r[TC['Diubah (ms)']] = now;
      sh.getRange(rn, 1, 1, HEADERS_T.length).setValues([r]);
      sh.getRange(rn, TC['Koordinat (salin)'] + 1).setNumberFormat('@');
      return json_({ ok: true, row: tmRowObj_(tmRow_(sh, rn)) });
    }

    if (d.action === 'tm_hapus') {
      const pembuat = normNpk_(r[TC['Pembuat NPK']]) === normNpk_(u.npk);
      if (!(u.supervisi || pembuat)) return json_({ ok: false, code: 'FORBIDDEN', error: 'Hanya pembuat atau supervisi yang boleh menghapus' });
      r[TC['Dihapus']] = '1'; r[TC['Diubah (ms)']] = now;
      sh.getRange(rn, 1, 1, HEADERS_T.length).setValues([r]);
      return json_({ ok: true, row: tmRowObj_(tmRow_(sh, rn)) });
    }

    return json_({ ok: false, code: 'DATA', error: 'Aksi tidak dikenal' });
  } catch (err) {
    return json_({ ok: false, code: 'ERR', error: String(err) });
  } finally {
    try { LockService.getScriptLock().releaseLock(); } catch (x) {}
  }
}
