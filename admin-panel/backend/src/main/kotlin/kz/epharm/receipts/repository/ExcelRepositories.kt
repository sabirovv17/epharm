package kz.epharm.receipts.repository

import kz.epharm.receipts.entity.ExcelImportEntity
import kz.epharm.receipts.entity.ExcelSaleRowEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ExcelImportRepository : JpaRepository<ExcelImportEntity, String>

@Repository
interface ExcelSaleRowRepository : JpaRepository<ExcelSaleRowEntity, String>
