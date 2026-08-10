package kz.epharm.screens.service

import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.posm.service.DevicePresenceService
import kz.epharm.screens.dto.ConnectedRegistersDto
import kz.epharm.screens.dto.RegisterPresenceDto
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.VerticalAlignment
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Live-инвентаризация POSM: единый snapshot для экрана админки и Excel-выгрузки. */
@Service
class ScreenPresenceService(
    private val devicePresenceService: DevicePresenceService,
    private val pharmacyRepository: PharmacyRepository,
) {
    private val almatyZone = ZoneId.of("Asia/Almaty")
    private val timestampFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")

    fun connected(): ConnectedRegistersDto {
        val devices = devicePresenceService.connected()
        val ids = devices.mapNotNull { it.pharmacyId?.takeIf(String::isNotBlank) }.distinct()
        val byId = if (ids.isEmpty()) emptyMap()
        else pharmacyRepository.findAllById(ids).associateBy { it.id }

        val rows = devices.map { device ->
            val pharmacy = device.pharmacyId?.let(byId::get)
            RegisterPresenceDto(
                deviceId = device.deviceId,
                pharmacyId = device.pharmacyId,
                pharmacyName = pharmacy?.name,
                pharmacyAddress = pharmacy?.let {
                    listOf(it.city, it.addr).filter(String::isNotBlank)
                        .joinToString(", ").takeIf(String::isNotBlank)
                },
                pharmacyCity = pharmacy?.city?.takeIf(String::isNotBlank),
                pharmacyStreetAddress = pharmacy?.addr?.takeIf(String::isNotBlank),
                monitorCount = device.monitorCount,
                hasClientScreen = device.monitorCount?.let { it >= 2 },
                lastSeen = device.lastSeen,
            )
        }.sortedWith(
            compareBy<RegisterPresenceDto>(
                { it.pharmacyCity.orEmpty() },
                { it.pharmacyStreetAddress.orEmpty() },
                { it.pharmacyName.orEmpty() },
                { it.deviceId },
            ),
        )

        return ConnectedRegistersDto(total = rows.size, devices = rows)
    }

    fun exportConnectedXlsx(): ByteArray {
        val snapshot = connected()
        val generatedAt = java.time.ZonedDateTime.now(almatyZone)

        XSSFWorkbook().use { workbook ->
            workbook.properties.coreProperties.apply {
                creator = "ePharm Console"
                title = "Подключённые POSM-кассы и клиентские экраны"
            }
            val sheet = workbook.createSheet("POSM и экраны")
            sheet.createFreezePane(0, HEADER_ROW + 1)
            sheet.setAutoFilter(CellRangeAddress(HEADER_ROW, HEADER_ROW, 0, HEADERS.lastIndex))
            sheet.setMargin(org.apache.poi.ss.usermodel.Sheet.LeftMargin, 0.35)
            sheet.setMargin(org.apache.poi.ss.usermodel.Sheet.RightMargin, 0.35)
            sheet.printSetup.landscape = true
            sheet.printSetup.fitWidth = 1
            sheet.printSetup.fitHeight = 0
            sheet.fitToPage = true

            val titleStyle = workbook.createCellStyle().apply {
                verticalAlignment = VerticalAlignment.CENTER
            }
            val titleFont = workbook.createFont().apply {
                bold = true
                fontHeightInPoints = 16
                color = IndexedColors.DARK_RED.index
            }
            titleStyle.setFont(titleFont)

            val noteStyle = workbook.createCellStyle().apply {
                wrapText = true
                verticalAlignment = VerticalAlignment.TOP
            }
            noteStyle.setFont(workbook.createFont().apply {
                fontHeightInPoints = 10
                color = IndexedColors.GREY_50_PERCENT.index
            })

            val headerStyle = workbook.createCellStyle().apply {
                fillForegroundColor = IndexedColors.DARK_RED.index
                fillPattern = FillPatternType.SOLID_FOREGROUND
                alignment = HorizontalAlignment.CENTER
                verticalAlignment = VerticalAlignment.CENTER
                wrapText = true
                borderBottom = BorderStyle.THIN
                borderTop = BorderStyle.THIN
                borderLeft = BorderStyle.THIN
                borderRight = BorderStyle.THIN
            }
            headerStyle.setFont(workbook.createFont().apply {
                bold = true
                color = IndexedColors.WHITE.index
            })

            val bodyStyle = workbook.createCellStyle().apply {
                verticalAlignment = VerticalAlignment.CENTER
                borderBottom = BorderStyle.HAIR
            }
            val centerStyle = workbook.createCellStyle().apply {
                cloneStyleFrom(bodyStyle)
                alignment = HorizontalAlignment.CENTER
            }
            val yesStyle = statusStyle(workbook, IndexedColors.LIGHT_GREEN)
            val noStyle = statusStyle(workbook, IndexedColors.GREY_25_PERCENT)
            val unknownStyle = statusStyle(workbook, IndexedColors.LIGHT_YELLOW)

            sheet.createRow(0).apply {
                heightInPoints = 28f
                createCell(0).apply {
                    setCellValue("Подключённые POSM-кассы и клиентские экраны")
                    cellStyle = titleStyle
                }
            }
            sheet.addMergedRegion(CellRangeAddress(0, 0, 0, HEADERS.lastIndex))

            sheet.createRow(1).apply {
                heightInPoints = 32f
                createCell(0).apply {
                    setCellValue(
                        "Сформировано: ${generatedAt.format(timestampFormat)} (Алматы). " +
                            "Включены кассы из текущего live-списка. Статус «Не определено» " +
                            "исчезнет автоматически после обновления POSM на кассе.",
                    )
                    cellStyle = noteStyle
                }
            }
            sheet.addMergedRegion(CellRangeAddress(1, 1, 0, HEADERS.lastIndex))

            sheet.createRow(HEADER_ROW).apply {
                heightInPoints = 30f
                HEADERS.forEachIndexed { index, value ->
                    createCell(index).apply {
                        setCellValue(value)
                        cellStyle = headerStyle
                    }
                }
            }

            snapshot.devices.forEachIndexed { index, device ->
                val row = sheet.createRow(HEADER_ROW + 1 + index).apply { heightInPoints = 22f }
                val values = listOf(
                    (index + 1).toString(),
                    device.pharmacyName.orEmpty(),
                    device.pharmacyCity.orEmpty(),
                    device.pharmacyStreetAddress.orEmpty(),
                    device.deviceId,
                    "Подключён",
                    when (device.hasClientScreen) {
                        true -> "Есть"
                        false -> "Нет"
                        null -> "Не определено"
                    },
                    device.monitorCount?.toString().orEmpty(),
                    device.lastSeen.atZone(almatyZone).format(timestampFormat),
                    device.pharmacyId.orEmpty(),
                )
                values.forEachIndexed { column, value ->
                    row.createCell(column).apply {
                        setCellValue(value)
                        cellStyle = when (column) {
                            0, 5, 7, 8 -> centerStyle
                            6 -> when (device.hasClientScreen) {
                                true -> yesStyle
                                false -> noStyle
                                null -> unknownStyle
                            }
                            else -> bodyStyle
                        }
                    }
                }
            }

            COLUMN_WIDTHS.forEachIndexed { index, characters ->
                sheet.setColumnWidth(index, characters * 256)
            }
            sheet.setRepeatingRows(CellRangeAddress(HEADER_ROW, HEADER_ROW, -1, -1))

            return ByteArrayOutputStream().use { output ->
                workbook.write(output)
                output.toByteArray()
            }
        }
    }

    private fun statusStyle(workbook: XSSFWorkbook, color: IndexedColors) =
        workbook.createCellStyle().apply {
            fillForegroundColor = color.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
            borderBottom = BorderStyle.HAIR
            setFont(workbook.createFont().apply { bold = true })
        }

    private companion object {
        private const val HEADER_ROW = 3
        private val HEADERS = listOf(
            "№",
            "Аптека",
            "Город",
            "Адрес",
            "Устройство",
            "POSM модуль",
            "Клиентский экран",
            "Мониторов",
            "Последний heartbeat",
            "ID аптеки",
        )
        private val COLUMN_WIDTHS = listOf(6, 30, 18, 34, 22, 18, 22, 12, 22, 30)
    }
}
