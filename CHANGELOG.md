
# Changelog

## [5.0] - 2026-08-09
### Added

#### Lyrics 2.0
- LRC dosyası otomatik algılama (şarkı yolundaki `.lrc` dosyasını okur)
- Senkronize / karaoke modu — çalan satır vurgulanır, otomatik scroll
- Lyrics editörü RecyclerView ile yeniden yazıldı; satır silme ve zaman damgası düzenleme
- LRC import (SAF dosya seçici) ve export (M3U uyumlu `[mm:ss.xx]` formatı)

#### Tag Editor
- Title, Artist, Album, Genre, Year, Track ve Cover Art düzenleme
- Çoklu şarkı seçimi — boş bırakılan alanlar mevcut değerleri korur
- Cover art harici dosyadan seçilip uygulama özel alanına kopyalanır
- Düzenlenen tag'ler `song_tags` Room tablosunda saklanır; orijinal dosya dokunulmaz

#### Folder Explorer
- Cihaz depolama alanı gezinme (ortak kök otomatik tespit edilir)
- Klasörü oynat / shuffle / kuyruğa ekle
- Şarkıyı playlist'e ekle diyaloğu
- Dosya yeniden adlandırma ve silme (çöp kutusuna da kaydedilir)
- Şarkı context menüsünden Tag Editor'a direkt erişim

#### Playlist Yöneticisi
- Oluştur, yeniden adlandır, sil, sabitle (pin)
- Sürükle-bırak ile sıralama (ItemTouchHelper)
- Kaydır-sil (swipe-to-delete)
- M3U, M3U8 (UTF-8) ve JSON formatında export
- M3U / M3U8 / JSON import — yerel kütüphaneyle dosya adı eşleştirmesi

#### Community / Ortak Playlist
- Trending, Popular, New sekmeleri (çevrimiçi veya demo verisi)
- Metin arama (çevrimiçi veya yerel önbellekten)
- Playlist ID veya link ile import
- Metadata eşleştirme — yerel kütüphanedeki şarkıları otomatik bulur
- Eksik şarkılar import özet diyaloğunda gösterilir
- Tüm veriler `community_playlists` tablosuna önbelleğe alınır; offline çalışır
- Yalnızca metadata paylaşımı — ses dosyası yükleme yok

#### Android TV Modu
- İki panelli arayüz: sol büyük Now Playing, sağ kütüphane listesi
- Songs / Albums / Artists / Playlists / Folders sekmeleri
- D-pad ve uzaktan kumanda tam desteği (`onKeyDown` override)
- 10 saniye ileri / geri atla butonları
- Shuffle ve Repeat kontrolleri
- TV cihazlarında 3 sütunlu grid, telefon/tablette liste görünümü
- `android.software.leanback` feature bildirimi ve TV launcher intent-filter

#### Power User Modu
- Gapless playback switch
- ReplayGain switch (mevcut `song_gain` veritabanıyla entegre)
- Crossfade süresi slider (0–10 saniye)
- Ses normalizasyonu toggle (PlayerService ile senkron)
- Sessizlik kırpma toggle (PlayerService ile senkron)
- Audio Focus yönetimi (diğer uygulamalar ses çalarken duraklat/devam)
- Uygulama açılışında son kaldığı yerden devam
- Fade In / Fade Out süresi slider (0–5000 ms)
- Tüm ayarlar `power_settings` Room tablosunda kalıcı

### Changed
- `LyricsEditorActivity` sıfırdan yeniden yazıldı — LinearLayout yerine RecyclerView
- Şarkı context menüsüne "Playlist'e Ekle", "Tag Düzenle", "Lyrics Düzenle" eklendi
- Tools menüsüne Folder Explorer, Playlist, Community, TV Modu, Power User Modu eklendi
- `NowPlayingActivity` Lyrics butonu artık sanatçı bilgisini de aktarıyor
- `AndroidManifest` — INTERNET, WRITE_EXTERNAL_STORAGE (≤29), TV özellikleri eklendi

### Database
- Versiyon 4 → 5 (Migration 4_5 eklendi, mevcut 1→2→3→4 korundu)
- Yeni tablolar: `song_tags`, `community_playlists`, `power_settings`
- `playlists` tablosuna `description`, `coverPath`, `shareId`, `isPublic` kolonları eklendi

---

## [4.0-fdroid] - 2026-07-07
### Added
- Çok dil desteği (Türkçe, İngilizce, Almanca, Çince, Japonca, İspanyolca)
- F-Droid metadata ve ekran görüntüleri

## [4.0] - 2026-06-24
### Added
- Başarım sistemi
- Uyku zamanlayıcı
- LRC formatında lyrics editörü
- Zil sesi yapıcı
- Kopya şarkı temizleyici
- Klasör kara listesi
- Çöp kutusu / geri dönüşüm kutusu
- Waveform seek bar
- A-B döngü
- Crossfade
- Dinleme istatistikleri
- Dinamik albüm kapağı renk teması