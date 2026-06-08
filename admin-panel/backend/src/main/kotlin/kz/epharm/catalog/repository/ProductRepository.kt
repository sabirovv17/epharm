package kz.epharm.catalog.repository

import kz.epharm.catalog.entity.ProductEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ProductRepository : JpaRepository<ProductEntity, String> {
    fun findAllByOrderByNameAsc(): List<ProductEntity>
    fun findAllByBrandOrderByNameAsc(brand: String): List<ProductEntity>
    fun findAllByMnnOrderByNameAsc(mnn: String): List<ProductEntity>
}
