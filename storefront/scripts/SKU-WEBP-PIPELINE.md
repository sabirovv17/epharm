# Подготовка SKU-изображений в WebP

`prepare-sku-webp.mjs` безопасно преобразует распакованный пакет SKU-изображений
из JPEG/PNG в WebP перед загрузкой через `import-sku-images.mjs`.

Исходные файлы не изменяются. По умолчанию выполняется только dry-run:

```powershell
node scripts/prepare-sku-webp.mjs `
  --images-dir "C:\staging\sku_images" `
  --output-dir "C:\staging\sku_images_webp" `
  --manifest "C:\staging\sku-webp-dry-run.jsonl"
```

Dry-run проверяет UUID, сигнатуру и декодирование каждого изображения, считает
коллизии и формирует append-only JSONL-манифест, но не создаёт WebP-файлы.

## Canary и полный запуск

Сначала преобразуйте небольшой canary:

```powershell
node scripts/prepare-sku-webp.mjs `
  --images-dir "C:\staging\sku_images" `
  --output-dir "C:\staging\sku_images_webp" `
  --apply --limit 5 `
  --manifest "C:\staging\sku-webp-apply.jsonl"
```

После проверки canary продолжите по тому же манифесту. Полный запуск без
`--limit` требует отдельного подтверждения `--confirm-all`:

```powershell
node scripts/prepare-sku-webp.mjs `
  --images-dir "C:\staging\sku_images" `
  --output-dir "C:\staging\sku_images_webp" `
  --apply --confirm-all `
  --resume "C:\staging\sku-webp-apply.jsonl"
```

Затем используйте подготовленную папку в существующем импортёре:

```powershell
node scripts/import-sku-images.mjs `
  --images-dir "C:\staging\sku_images_webp" `
  --manifest "C:\staging\sku-image-import-dry-run.jsonl"
```

## Гарантии

- не более 8 одновременных задач; по умолчанию не более 4;
- `sharp.rotate()` применяет EXIF orientation;
- метаданные EXIF/ICC/XMP не переносятся и отсутствие метаданных проверяется;
- качество WebP по умолчанию — 82;
- вход ограничен 25 MiB и 100 мегапикселями, выход — 5 MiB;
- результат сначала создаётся во временном файле и публикуется атомарно;
- существующий результат никогда не перезаписывается без проверенного resume;
- разные файлы с одним UUID помещаются в карантин;
- resume сверяет SHA-256 исходника и уже созданного WebP;
- полный apply нельзя запустить случайно: нужен `--confirm-all`.

## Проверенный пакет

Инвентаризация `sku_images.zip` от 2026-07-27 без распаковки и изменения архива:

- 9 483 изображения;
- 8 602 JPEG и 881 PNG;
- 9 483 валидных UUID-имени;
- 9 483 уникальных имени;
- 0 пустых файлов, коллизий имён и файлов больше 25 MiB.

Следовательно, структурно конвертируемы все 9 483 файла. Фактическое
декодирование всего архива следует подтвердить полным dry-run после распаковки в
staging. По текущей сверке каталога 9 459 файлов связаны с товарами, 24 файла не
имеют безопасного точного соответствия, а у 18 475 товаров источник изображения
в переданном архиве отсутствует. Конвертация не может восстановить отсутствующие
фотографии.
