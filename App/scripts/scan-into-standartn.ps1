# scan-into-standartn.ps1 — ЭМУЛЯТОР СКАНЕРА: печатает штрих-код в АКТИВНОЕ окно (как настоящий сканер).
# Сканер ШК = клавиатура: набирает цифры + Enter. Этот скрипт делает то же самое.
#
#   .\scan-into-standartn.ps1                 — напечатает Аскорбинку (4870004560307)
#   .\scan-into-standartn.ps1 733739006905    — любой штрих-код
#
# ВАЖНО: товар реально добавится В САМОЙ Стандарт-Н (в её чек), а не в наш файл.
# Порядок: запусти скрипт → за 5 сек кликни в поле ввода товара/ШК в окне Стандарт-Н → он сам напечатает.

param([string]$barcode = '4870004560307')
Add-Type -AssemblyName System.Windows.Forms

Write-Host "Штрих-код для 'скана': $barcode" -ForegroundColor Cyan
Write-Host ">>> БЫСТРО кликни мышкой в поле ввода товара/штрих-кода в окне Стандарт-Н <<<" -ForegroundColor Yellow
for ($i = 5; $i -ge 1; $i--) { Write-Host "  печатаю через $i..." ; Start-Sleep -Seconds 1 }

# набираем цифры по одной + Enter (как сканер). Цифры безопасны для SendKeys без экранирования.
[System.Windows.Forms.SendKeys]::SendWait($barcode)
[System.Windows.Forms.SendKeys]::SendWait('{ENTER}')

Write-Host "Готово. Если курсор был в поле товара Стандарт-Н — товар добавлен в ЕЁ чек (как настоящий скан)." -ForegroundColor Green
Write-Host "Теперь Стандарт-Н должна отдать событие наружу — но НАШ экран среагирует, только когда настроим канал (ТМС/дисплей/БД)." -ForegroundColor DarkGray
